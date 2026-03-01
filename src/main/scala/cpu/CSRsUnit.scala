package cpu

import chisel3._
import chisel3.util._

class CSRsUnit extends Module with CPUConfig {
  val io = IO(new Bundle {
    // CSR 指令访问
    val csrReadReq = Flipped(Decoupled(new CsrReadReq))
    val csrReadResp = Decoupled(new CsrReadResp)
    val csrWriteReq = Flipped(Decoupled(new CsrWriteReq))
    val csrWriteResp = Decoupled(new CsrWriteResp)
    
    // ROB 提交信号
    val exception = Input(new Exception)
    val pc = Input(UInt(32.W))
    val isCSR = Input(Bool())
    val mret = Input(Bool())
    val sret = Input(Bool())
    
    // 特权级控制
    val privMode = Output(UInt(2.W))
    
    // 全局控制
    val globalFlush = Output(Bool())
    val globalFlushPC = Output(UInt(32.W))
    
    // 内存控制
    val pmpConfig = Output(Vec(16, UInt(32.W)))
    val pmpAddr = Output(Vec(64, UInt(32.W)))
  })

  // ============================================================================
  // 1. 物理寄存器定义
  // ============================================================================

  // M-mode CSR 寄存器
  class MModeCSRs extends Bundle with CPUConfig {
    val misa = UInt(32.W)         // ISA 标志
    val mstatus = UInt(32.W)      // 机器状态寄存器
    val mie = UInt(32.W)          // 机器中断使能寄存器
    val mip = UInt(32.W)          // 机器中断挂起寄存器
    val mepc = UInt(32.W)         // 机器异常程序计数器
    val mcause = UInt(32.W)       // 机器异常原因
    val mtval = UInt(32.W)        // 机器陷阱值
    val mtvec = UInt(32.W)        // 机器陷阱向量基地址
    val mscratch = UInt(32.W)     // 机器临时寄存器
    val medeleg = UInt(32.W)      // 机器异常委托寄存器
    val mideleg = UInt(32.W)      // 机器中断委托寄存器
    val mcycle = UInt(64.W)       // 机器周期计数器
    val minstret = UInt(64.W)     // 机器指令计数器: Not Implemented
  }

  // S-mode CSR 寄存器
  class SModeCSRs extends Bundle with CPUConfig {
    val sepc = UInt(32.W)         // 监督者异常程序计数器
    val scause = UInt(32.W)       // 监督者异常原因
    val stval = UInt(32.W)        // 监督者陷阱值
    val stvec = UInt(32.W)        // 监督者陷阱向量基地址
    val sscratch = UInt(32.W)     // 监督者临时寄存器
    val satp = UInt(32.W)         // 地址翻译和保护: Not Implemented
  }

  // PMP 寄存器
  class PMPCSRs extends Bundle with CPUConfig {
    val pmpcfg = Vec(16, UInt(32.W))
    val pmpaddr = Vec(64, UInt(32.W))
  }

  // 完整物理资源集合
  class CSRCoreState extends Bundle with CPUConfig {
    val mMode = new MModeCSRs
    val sMode = new SModeCSRs
    val pmp = new PMPCSRs
    val privMode = UInt(2.W)
  }

  // 初始化物理 CSR 寄存器
  val resetState = Wire(new CSRCoreState)
  resetState := 0.U.asTypeOf(new CSRCoreState)
  resetState.mMode.misa := "h40000100".U
  resetState.privMode   := 3.U           // 3 = Machine Mode (11)

  val globalFlush = RegInit(false.B)
  val globalFlushPC = RegInit(0.U(32.W))

  val physCSRs = RegInit(resetState)

  // ============================================================================
  // 2. CSR 字段类型和描述符
  // ============================================================================

  // 字段类型
  sealed trait FieldType
  case object RW extends FieldType           // 读写
  case object RO extends FieldType           // 只读
  case object WPRI extends FieldType         // 写保留，读忽略
  case class WARL(legalize: (UInt, UInt) => UInt) extends FieldType  // 写任意值，读合法值

  // CSR 字段描述符
  case class CSRField(name: String, msb: Int, lsb: Int, fType: FieldType) {
    def mask: BigInt = ((BigInt(1) << (msb - lsb + 1)) - 1) << lsb
  }

  // CSR 描述符
  case class CSRDesc(
    addr: Int,
    name: String,
    physReg: UInt,             // 指向 CSRCoreState 中物理寄存器
    fields: Seq[CSRField],      // 位字段列表
  )

  // ============================================================================
  // 3. CSR 注册表构建
  // ============================================================================

  def buildCSRRegistry(phys: CSRCoreState): Seq[CSRDesc] = {
    Seq(
      // M-mode 标准寄存器
      CSRDesc(0x300, "mstatus", phys.mMode.mstatus, Seq(
        CSRField("SD", 31, 31, RO),
        CSRField("WPRI", 30, 23, WPRI),
        CSRField("TSR", 22, 22, RW),
        CSRField("TW", 21, 21, RW),
        CSRField("TVM", 20, 20, RW),
        CSRField("MXR", 19, 19, RW),
        CSRField("SUM", 18, 18, RW),
        CSRField("MPRV", 17, 17, RW),
        CSRField("XS", 16, 15, RO),
        CSRField("FS", 14, 13, WPRI),
        CSRField("MPP", 12, 11, WARL((old, raw) => Mux(raw === 2.U, 0.U, raw))),
        CSRField("WPRI", 10, 9, WPRI),
        CSRField("SPP", 8, 8, RW),
        CSRField("MPIE", 7, 7, RW),
        CSRField("WPRI", 6, 6, WPRI),
        CSRField("SPIE", 5, 5, RW),
        CSRField("WPRI", 4, 4, WPRI),
        CSRField("MIE", 3, 3, RW),
        CSRField("WPRI", 2, 2, WPRI),
        CSRField("SIE", 1, 1, RW),
        CSRField("WPRI", 0, 0, WPRI)
      )),
      CSRDesc(0x304, "mie", phys.mMode.mie, Seq(
        CSRField("WPRI", 31, 12, WPRI),
        CSRField("MEIE", 11, 11, RW),
        CSRField("WPRI", 10, 10, WPRI),
        CSRField("SEIE", 9, 9, RW),
        CSRField("WPRI", 8, 8, WPRI),
        CSRField("MTIE", 7, 7, RW),
        CSRField("WPRI", 6, 6, WPRI),
        CSRField("STIE", 5, 5, RW),
        CSRField("WPRI", 4, 4, WPRI),
        CSRField("MSIE", 3, 3, RW),
        CSRField("WPRI", 2, 2, WPRI),
        CSRField("SSIE", 1, 1, RW),
        CSRField("WPRI", 0, 0, WPRI)
      )),
      CSRDesc(0x344, "mip", phys.mMode.mip, Seq(
        CSRField("WPRI", 31, 12, WPRI),
        CSRField("MEIP", 11, 11, RO),
        CSRField("WPRI", 10, 10, WPRI),
        CSRField("SEIP", 9, 9, RW),
        CSRField("WPRI", 8, 8, WPRI),
        CSRField("MTIP", 7, 7, RO),
        CSRField("WPRI", 6, 6, WPRI),
        CSRField("STIP", 5, 5, RW),
        CSRField("WPRI", 4, 4, WPRI),
        CSRField("MSIP", 3, 3, RO),
        CSRField("WPRI", 2, 2, WPRI),
        CSRField("SSIP", 1, 1, RW),
        CSRField("WPRI", 0, 0, WPRI)
      )),
      CSRDesc(0x305, "mtvec", phys.mMode.mtvec, Seq(
        CSRField("BASE", 31, 2, WARL((old, raw) => raw & "hFFFF_FFFC".U)),
        CSRField("MODE", 1, 0, WARL((old, raw) => Mux(raw === 2.U, 0.U, raw)))
      )),
      CSRDesc(0x341, "mepc", phys.mMode.mepc, Seq(
        CSRField("BASE", 31, 0, WARL((old, raw) => raw & "hFFFF_FFFC".U))
      )),
      CSRDesc(0x342, "mcause", phys.mMode.mcause, Seq(
        CSRField("Interrupt", 31, 31, RO),
        CSRField("ExceptionCode", 30, 0, RW)
      )),
      CSRDesc(0x343, "mtval", phys.mMode.mtval, Seq(
        CSRField("Value", 31, 0, RW)
      )),
      CSRDesc(0x340, "mscratch", phys.mMode.mscratch, Seq(
        CSRField("Value", 31, 0, RW)
      )),
      CSRDesc(0x302, "medeleg", phys.mMode.medeleg, Seq(
        CSRField("Value", 31, 0, RW)
      )),
      CSRDesc(0x303, "mideleg", phys.mMode.mideleg, Seq(
        CSRField("Value", 31, 0, RW)
      )),
      CSRDesc(0xB00, "mcycle", phys.mMode.mcycle(31, 0), Seq(
        CSRField("Value", 31, 0, RO)
      )),
      CSRDesc(0xB02, "minstret", phys.mMode.minstret(31, 0), Seq(
        CSRField("Value", 31, 0, RO)
      )),

      // S-mode 标准寄存器（别名）
      CSRDesc(0x100, "sstatus", phys.mMode.mstatus, Seq(
        CSRField("WPRI", 31, 23, WPRI),
        CSRField("XS", 16, 15, RO),
        CSRField("FS", 14, 13, WPRI),
        CSRField("WPRI", 12, 9, WPRI),
        CSRField("SPP", 8, 8, RW),
        CSRField("WPRI", 7, 6, WPRI),
        CSRField("SPIE", 5, 5, RW),
        CSRField("WPRI", 4, 4, WPRI),
        CSRField("WPRI", 3, 3, WPRI),
        CSRField("WPRI", 2, 2, WPRI),
        CSRField("SIE", 1, 1, RW),
        CSRField("WPRI", 0, 0, WPRI)
      )),
      CSRDesc(0x104, "sie", phys.mMode.mie, Seq(
        CSRField("WPRI", 31, 12, WPRI),
        CSRField("WPRI", 11, 11, WPRI),
        CSRField("WPRI", 10, 10, WPRI),
        CSRField("SEIE", 9, 9, WARL((old, raw) => Mux(phys.mMode.mideleg(9), raw, old))),
        CSRField("WPRI", 8, 8, WPRI),
        CSRField("WPRI", 7, 7, WPRI),
        CSRField("WPRI", 6, 6, WPRI),
        CSRField("STIE", 5, 5, WARL((old, raw) => Mux(phys.mMode.mideleg(5), raw, old))),
        CSRField("WPRI", 4, 4, WPRI),
        CSRField("WPRI", 3, 3, WPRI),
        CSRField("WPRI", 2, 2, WPRI),
        CSRField("SSIE", 1, 1, WARL((old, raw) => Mux(phys.mMode.mideleg(1), raw, old))),
        CSRField("WPRI", 0, 0, WPRI)
      )),
      CSRDesc(0x144, "sip", phys.mMode.mip, Seq(
        CSRField("WPRI", 31, 12, WPRI),
        CSRField("WPRI", 11, 11, WPRI),
        CSRField("WPRI", 10, 10, WPRI),
        CSRField("SEIP", 9, 9, WARL((old, raw) => Mux(phys.mMode.mideleg(9), raw, old))),
        CSRField("WPRI", 8, 8, WPRI),
        CSRField("WPRI", 7, 7, WPRI),
        CSRField("WPRI", 6, 6, WPRI),
        CSRField("STIP", 5, 5, WARL((old, raw) => Mux(phys.mMode.mideleg(5), raw, old))),
        CSRField("WPRI", 4, 4, WPRI),
        CSRField("WPRI", 3, 3, WPRI),
        CSRField("WPRI", 2, 2, WPRI),
        CSRField("SSIP", 1, 1, WARL((old, raw) => Mux(phys.mMode.mideleg(1), raw, old))),
        CSRField("WPRI", 0, 0, WPRI)
      )),
      CSRDesc(0x180, "satp", phys.sMode.satp, Seq(
        CSRField("MODE", 31, 31, RW),
        CSRField("ASID", 30, 22, RW),  // ASID 不支持
        CSRField("PPN", 21, 0, RW)
      )),
      CSRDesc(0x105, "stvec", phys.sMode.stvec, Seq(
        CSRField("BASE", 31, 2, WARL((old, raw) => raw & "hFFFF_FFFC".U)),
        CSRField("MODE", 1, 0, RW)
      )),
      CSRDesc(0x141, "sepc", phys.sMode.sepc, Seq(
        CSRField("Value", 31, 0, WARL((old, raw) => raw & "hFFFF_FFFC".U))
      )),
      CSRDesc(0x142, "scause", phys.sMode.scause, Seq(
        CSRField("Interrupt", 31, 31, RW),
        CSRField("Code", 30, 0, WARL((old, raw) => raw(4, 0)))
      )),
      CSRDesc(0x143, "stval", phys.sMode.stval, Seq(
        CSRField("Value", 31, 0, RW)
      )),
      CSRDesc(0x140, "sscratch", phys.sMode.sscratch, Seq(
        CSRField("Value", 31, 0, RW)
      ))
    ) ++ (0 until 16).map { i =>
      CSRDesc(0x3A0 + i, s"pmpcfg$i", phys.pmp.pmpcfg(i), Seq(
        CSRField("cfg0", 7, 0, WARL((old, raw) => Mux(old(7), old, raw))),
        CSRField("cfg1", 15, 8, WARL((old, raw) => Mux(old(7), old, raw))),
        CSRField("cfg2", 23, 16, WARL((old, raw) => Mux(old(7), old, raw))),
        CSRField("cfg3", 31, 24, WARL((old, raw) => Mux(old(7), old, raw))))
      )
    } ++ (0 until 64).map { i =>
      CSRDesc(0x3B0 + i, s"pmpaddr$i", phys.pmp.pmpaddr(i), Seq(
        CSRField(s"addr", 31, 0, WARL((old, raw) => {
          val cfgRegIdx = i / 4
          val cfgByteOffset = (i % 4) * 8
          val lockBit = phys.pmp.pmpcfg(cfgRegIdx)(cfgByteOffset + 7)
          Mux(lockBit, old, raw)
        }))
      ))
    }
  }

  // ============================================================================
  // 4. CSR 读写逻辑生成器
  // ============================================================================

  def generateCSRRWLogic(
    cmdAddr: UInt,
    wdata: UInt,
    isWrite: Bool,
    phys: CSRCoreState,
    registry: Seq[CSRDesc]
  ): (UInt, Exception, CSRCoreState) = {
    printf(p"cmdAddr${cmdAddr}")
    val rdata = WireDefault(0.U(32.W))
    val exception = WireDefault(0.U.asTypeOf(new Exception))
    val nextPhysRegs = WireDefault(phys)
    
    // 计算每个 CSR 的读取和写入结果
    val evalResults = registry.map { desc =>
      val isMatch = (cmdAddr === desc.addr.U)
      
      // 读取逻辑：计算掩码
      val readMaskBigInt = desc.fields.foldLeft(BigInt(0)) { (acc, f) => 
        f.fType match {
          case RW | RO | WARL(_) => acc | f.mask
          case WPRI              => acc
        }
      }
      val readMask = readMaskBigInt.U(32.W)
      val readData = desc.physReg & readMask
      
      // 写入逻辑：分字段应用掩码
      val nextData = desc.fields.foldLeft(desc.physReg) { (accWire, field) =>
        field.fType match {
          case RW =>
            val fMask = field.mask.U(32.W)
            (accWire & ~fMask) | (wdata & fMask)
          case WARL(legalizeFunc) =>
            val oldSlice = desc.physReg(field.msb, field.lsb)
            val rawSlice = wdata(field.msb, field.lsb)
            val legalSlice = legalizeFunc(oldSlice, rawSlice)
            val fMask = field.mask.U(32.W)
            (accWire & ~fMask) | (legalSlice << field.lsb)
          case _ => 
            accWire
        }
      }

      when(isMatch) {
        // The 'p' interpolator prints hardware values
        printf(p"Dynamic Check - CSR ${desc.name}: Read=0x${Hexadecimal(readData)} wdata=0x${Hexadecimal(wdata)} Next=0x${Hexadecimal(nextData)}\n")
      }
      (isMatch, readData, nextData, desc)
    }
    
    // 检查是否有地址匹配
    val addressMatched = evalResults.map(_._1).reduce(_ || _)
    exception.valid := !addressMatched
    exception.cause := Mux(!addressMatched, ExceptionCause.ILLEGAL_INSTRUCTION, 0.U)
    exception.tval := Mux(!addressMatched, cmdAddr, 0.U)
    
    // 计算读取结果
    rdata := Mux1H(evalResults.map(x => x._1 -> x._2))
    
    // 将写入合并到下一周期物理寄存器状态包中
    when (isWrite && addressMatched) {
      evalResults.foreach { case (isMatch, _, nextData, desc) =>
        when (isMatch) {
          desc.name match {
            case "mstatus" => nextPhysRegs.mMode.mstatus := nextData
            case "sstatus" => nextPhysRegs.mMode.mstatus := nextData
            case "mie"     => nextPhysRegs.mMode.mie := nextData
            case "sie"     => nextPhysRegs.mMode.mie := nextData
            case "mip"     => nextPhysRegs.mMode.mip := nextData
            case "sip"     => nextPhysRegs.mMode.mip := nextData
            case "mtvec"   => nextPhysRegs.mMode.mtvec := nextData
            case "mepc"    => nextPhysRegs.mMode.mepc := nextData
            case "mcause"  => nextPhysRegs.mMode.mcause := nextData
            case "mtval"   => nextPhysRegs.mMode.mtval := nextData
            case "mscratch"=> nextPhysRegs.mMode.mscratch := nextData
            case "medeleg" => nextPhysRegs.mMode.medeleg := nextData
            case "mideleg" => nextPhysRegs.mMode.mideleg := nextData
            case "satp"    => nextPhysRegs.sMode.satp := nextData
            case "stvec"   => nextPhysRegs.sMode.stvec := nextData
            case "sepc"    => nextPhysRegs.sMode.sepc := nextData
            case "scause"  => nextPhysRegs.sMode.scause := nextData
            case "stval"   => nextPhysRegs.sMode.stval := nextData
            case "sscratch"=> nextPhysRegs.sMode.sscratch := nextData
            case name if name.startsWith("pmpcfg") => 
              val idx = name.replace("pmpcfg", "").toInt
              nextPhysRegs.pmp.pmpcfg(idx) := nextData
            case name if name.startsWith("pmpaddr") => 
              val idx = name.replace("pmpaddr", "").toInt
              nextPhysRegs.pmp.pmpaddr(idx) := nextData
            case _ =>
          }
        }
      }
    }
    
    printf(p"Mstatus=0x${Hexadecimal(nextPhysRegs.mMode.mstatus)}\n")
    (rdata, exception, nextPhysRegs)
  }

  // ============================================================================
  // 5. Trap 处理辅助函数
  // ============================================================================

  // M-mode Trap 进入：更新 mstatus
  def updateMstatusOnTrapEntry(currentMstatus: UInt, prevPriv: UInt): UInt = {
    val mpie = currentMstatus(3)  // Old MIE becomes MPIE
    val mpp  = prevPriv           // Old Priv Mode
    val mie  = false.B            // Interrupts disabled
    
    val mask = "h_FFFF_E777".U(32.W)
    (currentMstatus & mask) | (mpp << 11) | (mpie.asUInt << 7) | (mie.asUInt << 3)
  }

  // S-mode Trap 进入：更新 mstatus（sstatus 是别名）
  def updateSstatusOnTrapEntry(currentMstatus: UInt, prevPriv: UInt): UInt = {
    val spie = currentMstatus(1)  // Old SIE becomes SPIE
    val spp  = prevPriv(0)        // Old Priv (0=U, 1=S)
    val sie  = false.B            // Disable interrupts
    
    val mask = "h_FFFF_FEDD".U(32.W)
    (currentMstatus & mask) | (spp << 8) | (spie.asUInt << 5) | (sie.asUInt << 1)
  }

  // MRET 返回：恢复 mstatus
  def updateMstatusOnMret(currentMstatus: UInt): (UInt, UInt) = {
    val mpp  = currentMstatus(12, 11)  // Restore Privilege
    val mie  = currentMstatus(7)       // Restore MIE from MPIE
    val mpie = true.B                  // Set MPIE to 1
    val newMpp = 0.U(2.W)             // Set MPP to U
    
    val mask = "h_FFFF_E777".U(32.W)
    val nextMstatus = (currentMstatus & mask) | (newMpp << 11) | (mpie.asUInt << 7) | (mie.asUInt << 3)
   
    (nextMstatus, mpp)
  }

  // SRET 返回：恢复 mstatus（sstatus 是别名）
  def updateSstatusOnSret(currentMstatus: UInt): (UInt, UInt) = {
    val spp  = currentMstatus(8)       // Restore Privilege (0=U, 1=S)
    val sie  = currentMstatus(5)       // Restore SIE from SPIE
    val spie = true.B                  // Set SPIE to 1
    val newSpp = 0.U(1.W)              // Set SPP to U
    
    val mask = "h_FFFF_FEDD".U(32.W)
    val nextMstatus = (currentMstatus & mask) | (newSpp << 8) | (spie.asUInt << 5) | (sie.asUInt << 1)
    
    (nextMstatus, Cat(0.U(1.W), spp))
  }

  // ============================================================================
  // 6. Trap 处理函数
  // ============================================================================

  def handleTrap(
    pc: UInt,
    cause: UInt,
    tval: UInt,
    isInterrupt: Bool,
    currentPriv: UInt,
    phys: CSRCoreState
  ): (CSRCoreState, UInt) = {
    
    val nextPhys = WireDefault(phys)
    val targetPC = Wire(UInt(32.W))
    
    val causeCode = cause(4, 0)
    
    // 计算是否代理到 S-mode 处理 Trap
    val delegateToS = (currentPriv =/= PrivMode.M.asUInt) && 
      Mux(isInterrupt, phys.mMode.mideleg(causeCode), phys.mMode.medeleg(causeCode))
    
    when (delegateToS) {
      // S-Mode Trap Entry
      nextPhys.mMode.mstatus := updateSstatusOnTrapEntry(phys.mMode.mstatus, currentPriv)
      nextPhys.sMode.sepc    := pc
      nextPhys.sMode.scause  := Cat(isInterrupt, causeCode)
      nextPhys.sMode.stval   := tval
      nextPhys.privMode      := PrivMode.S.asUInt
      
      val base = Cat(phys.sMode.stvec(31, 2), 0.U(2.W))
      val isVectored = phys.sMode.stvec(0)
      targetPC := Mux(isInterrupt && isVectored, base + (causeCode << 2), base)
      
    } .otherwise {
      // M-Mode Trap Entry
      nextPhys.mMode.mstatus := updateMstatusOnTrapEntry(phys.mMode.mstatus, currentPriv)
      nextPhys.mMode.mepc    := pc
      nextPhys.mMode.mcause  := Cat(isInterrupt, causeCode)
      nextPhys.mMode.mtval   := tval
      nextPhys.privMode      := PrivMode.M.asUInt
      
      val base = Cat(phys.mMode.mtvec(31, 2), 0.U(2.W))
      val isVectored = phys.mMode.mtvec(0)
      targetPC := Mux(isInterrupt && isVectored, base + (causeCode << 2), base)
    }
    
    (nextPhys, targetPC)
  }

  // ============================================================================
  // 7. 返回指令处理函数
  // ============================================================================

  object InsType extends ChiselEnum {
    val MRET, SRET = Value
  }

  def handleReturn(
    instType: InsType.Type,
    phys: CSRCoreState
  ): (CSRCoreState, UInt) = {
    
    val nextPhys = WireDefault(phys)
    val targetPC = WireDefault(0.U(32.W))
    
    switch (instType) {
      is (InsType.MRET) {
        val (newMstatus, targetPriv) = updateMstatusOnMret(phys.mMode.mstatus)
        nextPhys.mMode.mstatus := newMstatus
        nextPhys.privMode      := targetPriv
        targetPC               := phys.mMode.mepc
      }
      is (InsType.SRET) {
        val (newMstatus, targetPriv) = updateSstatusOnSret(phys.mMode.mstatus)
        nextPhys.mMode.mstatus := newMstatus
        nextPhys.privMode      := targetPriv
        targetPC               := phys.sMode.sepc
      }
    }
    
    (nextPhys, targetPC)
  }

  // ============================================================================
  // 8. 更新优先级和合并逻辑
  // ============================================================================

  object UpdateSource extends ChiselEnum {
    val None, CsrRW, Trap, Return = Value
  }

  // 异常条件判断
  val isException = io.exception.valid

  // 中断条件判断
  val mstatusMIE = physCSRs.mMode.mstatus(3)
  val pendingInterrupts = physCSRs.mMode.mie & physCSRs.mMode.mip
  val hasInterrupt = (pendingInterrupts =/= 0.U) && mstatusMIE
  val isInterrupt = !isException && hasInterrupt

  // 返回指令条件判断
  val isReturn = io.mret || io.sret

  // CSR 读写条件判断
  val isCsrRW = io.csrWriteReq.fire || io.csrReadReq.fire

  // 优先级：异常 > 中断 > 返回指令 > CSR 读写
  val updateSource = MuxCase(UpdateSource.None, Seq(
    (isException || isInterrupt) -> UpdateSource.Trap,
    isReturn                     -> UpdateSource.Return,
    isCsrRW                      -> UpdateSource.CsrRW
  ))

  // ============================================================================
  // 9. 结果合并和更新
  // ============================================================================

  val currentState = WireDefault(physCSRs)

  // 构建 CSR 注册表
  val registry = buildCSRRegistry(physCSRs)

  // 1. 计算 CSR Read/Write 结果
  val cmdRAddr = Mux(io.csrReadReq.valid, io.csrReadReq.bits.csrAddr, 0.U)
  val cmdWAddr = Mux(io.csrWriteReq.valid, io.csrWriteReq.bits.csrAddr, 0.U)
  val (rdata, rwException, csrRWResult) = generateCSRRWLogic(
    cmdRAddr | cmdWAddr, 
    io.csrWriteReq.bits.data, 
    io.csrWriteReq.valid, 
    currentState, 
    registry
  )

  // 2. 计算 Trap Handling 结果
  val (csrTrapResult, globalTrapPC) = handleTrap(
    io.pc,
    io.exception.cause,
    io.exception.tval, 
    isInterrupt, 
    physCSRs.privMode, 
    currentState
  )

  // 3. 计算 xRet 指令结果
  val retType = Mux(io.mret, InsType.MRET, InsType.SRET)
  val (csrRetState, globalRetPC) = handleReturn(
    retType, 
    currentState
  )

  // 4. Mux 确定结果
  val nextPhysCSRs = WireDefault(currentState)
  val flushFlag = WireDefault(false.B)
  val flushPC = WireDefault(0.U(32.W))

  switch (updateSource) {
    is (UpdateSource.Trap) {
      nextPhysCSRs := csrTrapResult
      flushFlag := true.B
      flushPC := globalTrapPC
    }
    is (UpdateSource.Return) {
      nextPhysCSRs := csrRetState
      flushFlag := true.B
      flushPC := globalRetPC
    }
    is (UpdateSource.CsrRW) {
      nextPhysCSRs := Mux(rwException.valid, currentState, csrRWResult)
      flushFlag := false.B 
      flushPC := 0.U
    }
    is (UpdateSource.None) {
      nextPhysCSRs := currentState
      flushFlag := io.isCSR
      flushPC := Mux(io.isCSR, io.pc, 0.U)
    }
  }
  
  nextPhysCSRs.mMode.mcycle := physCSRs.mMode.mcycle + 1.U

  // 5. 更新物理寄存器
  physCSRs := nextPhysCSRs
  globalFlush := flushFlag
  globalFlushPC := flushPC

  // ============================================================================
  // 10. 输出接口实现
  // ============================================================================

  // CSR 读响应
  io.csrReadResp.valid := io.csrReadReq.valid
  io.csrReadResp.bits.data := rdata
  io.csrReadResp.bits.exception := rwException

  // CSR 写响应
  io.csrWriteResp.valid := io.csrWriteReq.valid
  io.csrWriteResp.bits.exception := rwException

  io.csrReadReq.ready := !globalFlush
  io.csrWriteReq.ready := !globalFlush

  // 输出当前特权级
  io.privMode := physCSRs.privMode

  // 输出全局冲刷信号
  io.globalFlush := globalFlush
  io.globalFlushPC := globalFlushPC

  // 输出 PMP 配置寄存器
  io.pmpConfig := physCSRs.pmp.pmpcfg

  // 输出 PMP 地址寄存器
  io.pmpAddr := physCSRs.pmp.pmpaddr
  printf(p"UpdateSource=${updateSource}, MStatus=0x${Hexadecimal(physCSRs.mMode.mstatus)}\n")
}
