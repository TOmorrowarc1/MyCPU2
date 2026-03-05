package cpu

import chisel3._
import chisel3.util._

/** AGU (地址生成单元)
  *
  * 负责为所有内存访问指令计算物理地址并进行访问权限检查。
  *
  * 功能：
  *   - 地址计算：PA = BaseAddr + Offset
  *   - 对齐检查：根据访存位宽验证地址是否正确对齐
  *   - 权限检查：与 PMPChecker 配合进行访问权限验证
  *   - 异常生成：生成对齐异常或访问异常
  *
  * 异常优先级：对齐异常 > 访问异常
  */
class AGU extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 LSU 的请求
    val req = Input(Valid(new AGUReq))

    // 来自 CSRsUnit 的 PMP 配置
    val pmpcfg = Input(Vec(16, UInt(8.W)))
    val pmpaddr = Input(Vec(16, UInt(32.W)))

    // 输出
    val resp = Output(Valid(new AGUResp))
  })

  // 默认输出：无效响应
  io.resp.valid := false.B
  io.resp.bits := 0.U.asTypeOf(new AGUResp)

  // 当请求无效时，直接返回
  when(!io.req.valid) {
    io.resp.valid := false.B
  }.otherwise {
    // 提取请求信息
    val baseAddr = io.req.bits.baseAddr
    val offset = io.req.bits.offset
    val memWidth = io.req.bits.memWidth
    val memOp = io.req.bits.memOp
    val privMode = io.req.bits.privMode
    val ctx = io.req.bits.ctx

    // 1. 物理地址计算
    val pa = baseAddr + offset

    // 2. 对齐检查
    // BYTE: 无需对齐
    // HALF: 最低 1 位必须为 0（2 字节对齐）
    // WORD: 最低 2 位必须为 0（4 字节对齐）
    val misaligned = MuxCase(
      false.B,
      Seq(
        (memWidth === LSUWidth.BYTE) -> false.B, // Byte 无需对齐
        (memWidth === LSUWidth.HALF) -> pa(0), // Half: 最低 1 位必须为 0
        (memWidth === LSUWidth.WORD) -> (pa(1) | pa(0)) // Word: 最低 2 位必须为 0
      )
    )

    // 生成对齐异常
    val alignException = Wire(new Exception)
    alignException.valid := misaligned
    alignException.cause := Mux(
      memOp === LSUOp.LOAD,
      ExceptionCause.LOAD_ADDRESS_MISALIGNED,
      ExceptionCause.STORE_ADDRESS_MISALIGNED
    )
    alignException.tval := pa

    // 3. PMP 检查
    // 实例化 PMPChecker 模块
    val pmpChecker = Module(new PMPChecker)

    // 生成 PMP 检查请求
    val pmpCheckReq = Wire(Valid(new PMPCheckReq))
    pmpCheckReq.valid := !misaligned // 只有对齐检查通过时才进行 PMP 检查
    pmpCheckReq.bits.addr := pa
    pmpCheckReq.bits.memOp := memOp
    pmpCheckReq.bits.privMode := privMode

    // 连接 PMPChecker
    pmpChecker.io.req := pmpCheckReq
    pmpChecker.io.pmpcfg := io.pmpcfg
    pmpChecker.io.pmpaddr := io.pmpaddr

    // 生成访问异常
    val accessException = Wire(new Exception)
    accessException.valid := pmpChecker.io.exception.valid
    accessException.cause := pmpChecker.io.exception.cause
    accessException.tval := pmpChecker.io.exception.tval

    // 4. 异常优先级处理
    // 对齐异常优先于访问异常
    val finalException = Wire(new Exception)
    finalException.valid := alignException.valid || accessException.valid
    finalException.cause := Mux(
      alignException.valid,
      alignException.cause,
      accessException.cause
    )
    finalException.tval := Mux(
      alignException.valid,
      alignException.tval,
      accessException.tval
    )

    // 5. 输出响应
    io.resp.valid := true.B
    io.resp.bits.pa := pa
    io.resp.bits.exception := finalException
    io.resp.bits.ctx := ctx
  }
}
