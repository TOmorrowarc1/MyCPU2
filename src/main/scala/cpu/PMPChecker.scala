package cpu

import chisel3._
import chisel3.util._

/** PMPChecker (物理内存保护检查器)
  *
  * 负责执行 RISC-V PMP 权限检查，根据特权级和配置验证访问权限，生成访问异常。
  *
  * 功能：
  *   - 支持四种地址匹配模式：OFF, TOR, NA4, NAPOT
  *   - 实现优先级仲裁（小编号优先）
  *   - 执行读/写权限检查
  *   - 处理不同特权级的默认策略
  */
class PMPChecker extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 AGU 的请求
    val req = Input(Valid(new PMPCheckReq))

    // 来自 CSRsUnit 的 PMP 配置
    val pmpcfg = Input(Vec(16, UInt(32.W))) // 16 个 PMP 配置寄存器（每个 32 位，包含 4 个 8 位配置）
    val pmpaddr = Input(Vec(64, UInt(32.W))) // 64 个 PMP 地址寄存器

    // 输出
    val exception = Output(new Exception)
    val pmpMatch = Output(Bool())
  })

  // 默认输出：无异常，无匹配
  val exception = WireDefault(0.U.asTypeOf(new Exception))
  val pmpMatch = WireDefault(false.B)

  // 当请求无效时，直接返回
  when(!io.req.valid) {
    exception.valid := false.B
    exception.cause := 0.U
    exception.tval := 0.U
    pmpMatch := false.B
  }.otherwise {
    // 提取请求信息
    val addr = io.req.bits.addr >> 2.U // 以 4 字节为单位对齐地址
    val memOp = io.req.bits.memOp
    val privMode = io.req.bits.privMode

    // 解析 PMP 配置寄存器
    // bit[7] = L (锁定位)
    // bit[4:3] = A (地址匹配模式)
    // bit[2] = X (执行权限)
    // bit[1] = W (写权限)
    // bit[0] = R (读权限)
    val pmpR = Wire(Vec(64, Bool()))
    val pmpW = Wire(Vec(64, Bool()))
    val pmpX = Wire(Vec(64, Bool()))
    val pmpL = Wire(Vec(64, Bool()))
    val pmpA = Wire(Vec(64, UInt(4.W)))

    // 从 16 个 pmpcfg 寄存器（每个 32 位）中提取 64 个 8 位配置
    for (i <- 0 until 64) {
      val cfgRegIdx = i / 4
      val cfgByteOffset = (i % 4) * 8
      val cfg = io.pmpcfg(cfgRegIdx)(cfgByteOffset + 7, cfgByteOffset)
      pmpR(i) := cfg(0)
      pmpW(i) := cfg(1)
      pmpX(i) := cfg(2)
      pmpL(i) := cfg(7)
      pmpA(i) := cfg(4, 3)
    }

    // 地址匹配标志
    val pmpMatchSign = Wire(Vec(64, Bool()))

    // 预先计算前一个条目的地址（用于 TOR 模式）
    val prevPmpaddr = Wire(Vec(64, UInt(32.W)))
    prevPmpaddr(0) := 0.U
    for (i <- 1 until 64) {
      prevPmpaddr(i) := io.pmpaddr(i - 1)
      printf(p"prevPmpaddr($i) = 0x${Hexadecimal(prevPmpaddr(i))}\n")
    }

    for (i <- 0 until 64) {
      when(pmpA(i) === 0.U) { // OFF 模式：禁用，不匹配
        pmpMatchSign(i) := false.B
      }.elsewhen(pmpA(i) === 1.U) { // TOR 模式：Top of Range
        // 匹配范围 [lower, upper)
        // Entry 0 的下限为 0，其他条目的下限为 pmpaddr[i-1]
        val lower = Mux(i.U === 0.U, 0.U, prevPmpaddr(i))
        val upper = io.pmpaddr(i)
        pmpMatchSign(i) := (addr >= lower && addr < upper)
      }.elsewhen(pmpA(i) === 2.U) { // NA4 模式：Naturally Aligned 4-byte
        // 匹配单个 4 字节区域
        val baseAddr = io.pmpaddr(i)
        pmpMatchSign(i) := ((addr ^ baseAddr) === 0.U)
      }.elsewhen(pmpA(i) === 3.U) { // NAPOT 模式：Naturally Aligned Power-of-Two
        // 匹配 2^(2+NAPOT) 字节区域
        // 根据 pmpaddr 中的低位 1 的个数计算掩码
        // NAPOT 格式：低位为 1 的个数 + 3 = 区域大小以 2 为底的对数
        val pmpaddrVal = io.pmpaddr(i)
        // 计算掩码：找到从右向左第一个 0 的位置
        val mask = ~((pmpaddrVal + 1.U) ^ pmpaddrVal)
        // 使用掩码
        val baseAddr = pmpaddrVal & mask
        pmpMatchSign(i) := ((addr & mask) === baseAddr)
        printf(p"PMP Entry $i NAPOT: base=0x${Hexadecimal(baseAddr)}, mask=0x${Hexadecimal(mask)}, addr=0x${Hexadecimal(addr)}, match=${pmpMatchSign(i)}\n")
      }.otherwise {
        pmpMatchSign(i) := false.B
      }
      printf(p"PMP Entry $i: A=${pmpA(i)}, R=${pmpR(i)}, W=${pmpW(i)}, X=${pmpX(i)}, L=${pmpL(i)}, Match=${pmpMatchSign(i)}\n")
    }

    printf(p"pmpMatchSign: ${pmpMatchSign}\n")

    // 权限需求
    val needR = (memOp === LSUOp.LOAD)
    val needW = (memOp === LSUOp.STORE)

    // 默认策略
    // privMode < M 时，默认拒绝访问（除非匹配到 PMP 条目且权限允许）
    // privMode == M 时，默认允许访问（除非匹配到 PMP 条目且权限不允许）
    val defaultDeny = (privMode =/= PrivMode.M)

    val accessGranted = WireDefault(!defaultDeny)
    
    // 检查每个匹配的条目，使用小编号优先策略
    val hasMatch = pmpMatchSign.reduce(_ || _)

    when(hasMatch) {
      val firstMatch = PriorityEncoderOH(pmpMatchSign)

      val pmpLMatch = Mux1H(firstMatch, pmpL)
      val pmpRMatch = Mux1H(firstMatch, pmpR)
      val pmpWMatch = Mux1H(firstMatch, pmpW)

      val enforceCheck = pmpLMatch || (privMode =/= PrivMode.M)

      when(enforceCheck) {
        val rOk = !needR || pmpRMatch
        val wOk = !needW || pmpWMatch
        accessGranted := rOk && wOk
      }.otherwise {
        // M-mode 且 L=0，允许访问
        accessGranted := true.B
      }
    }

    // 生成异常
    when(!accessGranted) {
      exception.valid := true.B
      exception.tval := io.req.bits.addr

      // 根据操作类型设置异常原因
      when(needW) {
        exception.cause := ExceptionCause.STORE_ACCESS_FAULT
      }.elsewhen(needR) {
        exception.cause := ExceptionCause.LOAD_ACCESS_FAULT
      }
    }.otherwise {
      exception.valid := false.B
      exception.cause := 0.U
      exception.tval := 0.U
    }

    // 输出匹配标志
    pmpMatch := hasMatch
  }

  printf(p"pmpChecker: addr=0x${Hexadecimal(io.req.bits.addr)}, memOp=${io.req.bits.memOp}, privMode=${io.req.bits.privMode}, exception.valid=${exception.valid}, exception.cause=${exception.cause}, exception.tval=0x${Hexadecimal(exception.tval)}, pmpMatch=${pmpMatch}\n")
  io.exception := exception
  io.pmpMatch := pmpMatch
}
