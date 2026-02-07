package cpu

import chisel3._
import chisel3.util._

class Decoder extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 Icache 的输入
    val in = Flipped(Decoupled(new IDecodePacket))

    // 来自 ROB 的控制信号
    val freeRobID = Input(RobTag)
    val globalFlush = Input(Bool())
    val csrPending = Input(Bool())

    // 来自 BRU 的控制信号
    val branchFlush = Input(Bool())

    // 向 RAT 发送重命名请求
    val renameReq = Decoupled(new RenameReq)

    // 向 ROB 发送初始化信息
    val robInit = Decoupled(new ROBInitControlPacket)

    // 向 RS 发送分派信息
    val dispatch = Decoupled(new DispatchPacket)

    // 向 Fetcher 发送 Stall 信号
    val ifStall = Output(Bool())
  })

  // ========== 指令字段提取 ==========
  val inst = io.in.bits.inst
  val opcode = inst(6, 0)
  val rd = inst(11, 7)
  val funct3 = inst(14, 12)
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val funct7 = inst(31, 25)
  val funct12 = inst(31, 20)
  val csrAddr = funct12

  // ========== 元数据透传 ==========
  val pc = io.in.bits.instMetadata.pc
  val instEpoch = io.in.bits.instMetadata.instEpoch
  val prediction = io.in.bits.instMetadata.prediction
  val inputException = io.in.bits.instMetadata.exception
  val privMode = io.in.bits.instMetadata.privMode

  // ========== 译码控制信号 ==========
  val aluOp = Wire(ALUOp())
  val op1Src = Wire(Src1Sel())
  val op2Src = Wire(Src2Sel())
  val lsuOp = Wire(LSUOp())
  val lsuWidth = Wire(LSUWidth())
  val lsuSign = Wire(LSUsign())
  val bruOp = Wire(BRUOp())
  val immType = Wire(ImmType())
  val zicsrOp = Wire(ZicsrOp())
  val canWB = Wire(Bool())

  // 指令类型标志
  val specialInstr = Wire(SpecialInstr())
  val isCsr = Wire(Bool())
  val isEcall = Wire(Bool())
  val isEbreak = Wire(Bool())
  val isMret = Wire(Bool())
  val isSret = Wire(Bool())
  val isSFENCE = Wire(Bool())
  val isWFI = Wire(Bool())

  // 立即数
  val imm = Wire(UInt(32.W))

  // 异常信号
  val decodeException = Wire(new Exception)
  val hasException = Wire(Bool())

  // 流水线控制信号
  val robFull = Wire(Bool())
  val rsFull = Wire(Bool())
  val ratFull = Wire(Bool())
  val needStall = Wire(Bool())
  val needFlush = Wire(Bool())
  val decoderValid = Wire(Bool())

  // ========== 指令解码 ==========
  val decodeEntry = MuxCase(
    Instructions.defaultEntry,
    Instructions.table.map { case (pat, entry) =>
      (inst === pat) -> entry
    }
  )

  // 将查找到的解码信息分配到各个控制信号
  aluOp := decodeEntry.aluOp
  op1Src := decodeEntry.op1Src
  op2Src := decodeEntry.op2Src
  lsuOp := decodeEntry.lsuOp
  lsuWidth := decodeEntry.lsuWidth
  lsuSign := decodeEntry.lsuSign
  bruOp := decodeEntry.bruOp
  immType := decodeEntry.immType
  specialInstr := decodeEntry.specialInstr
  zicsrOp := decodeEntry.zicsrOp
  canWB := decodeEntry.canWB

  // 特殊指令标志
  isCsr := (specialInstr === SpecialInstr.CSR)
  isEcall := (specialInstr === SpecialInstr.ECALL)
  isEbreak := (specialInstr === SpecialInstr.EBREAK)
  isMret := (specialInstr === SpecialInstr.MRET)
  isSret := (specialInstr === SpecialInstr.SRET)
  isSFENCE := (specialInstr === SpecialInstr.SFENCE)
  isWFI := (specialInstr === SpecialInstr.WFI)

  // ========== 立即数生成 ==========
  // I-Type: imm[11:0] 符号扩展
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  // S-Type: imm[11:5] | imm[4:0] 符号扩展
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  // B-Type: imm[12|10:5] | imm[4:1|11] 符号扩展
  val immB = Cat(
    Fill(19, inst(31)),
    inst(31),
    inst(7),
    inst(30, 25),
    inst(11, 8),
    0.U(1.W)
  )
  // U-Type: imm[31:12] 左移12位，低位补0
  val immU = Cat(inst(31, 12), 0.U(12.W))
  // J-Type: imm[20|10:1|11|19:12] 符号扩展
  val immJ = Cat(
    Fill(11, inst(31)),
    inst(31),
    inst(19, 12),
    inst(20),
    inst(30, 21),
    0.U(1.W)
  )
  // Z-Type: 零扩展立即数（用于 Zicsr 指令的 uimm[4:0]）
  val immZ = Cat(0.U(27.W), rs1)

  imm := MuxCase(
    0.U(32.W),
    Seq(
      (immType === ImmType.I_TYPE) -> immI,
      (immType === ImmType.S_TYPE) -> immS,
      (immType === ImmType.B_TYPE) -> immB,
      (immType === ImmType.U_TYPE) -> immU,
      (immType === ImmType.J_TYPE) -> immJ,
      (immType === ImmType.Z_TYPE) -> immZ
    )
  )

  // ========== 异常检测 ==========
  decodeException.valid := false.B
  decodeException.cause := 0.U(4.W)
  decodeException.tval := 0.U(32.W)

  // 非法指令异常（基于完整 32 位 bitpattern）
  when(!decodeEntry.isLegal) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
    decodeException.tval := pc
  }.elsewhen(isCsr) {
    // 1. CSR 基础信息提取
    val csrRequiredPriv = csrAddr(9, 8) // [9:8] 指示最低特权级
    val csrReadOnly = csrAddr(11, 10) === "b11".U // [11:10] 为 11 表示只读
    // 2. 特权级合法性检查 (Privilege Level Check)
    // 规则：当前特权级 (privMode) >= CSR 所需特权级 (csrRequiredPriv)
    val isPrivLegal = MuxCase(
      false.B,
      Seq(
        (csrRequiredPriv === "b00".U) -> true.B,
        (csrRequiredPriv === "b01".U) -> (privMode === PrivMode.S || privMode === PrivMode.M),
        (csrRequiredPriv === "b11".U) -> (privMode === PrivMode.M)
      )
    )
    // 3. 写入行为判定 (Write Attempt Check)
    // - CSRRW / CSRRWI (funct3[1:0] = 01): 总是写入
    // - CSRRS / CSRRC / CSRRSI / CSRRCI (funct3[1:0] = 10 or 11): 仅当 rs1/uimm != 0 时写入
    val isCsrWrite = (funct3(1) === 0.B) || (rs1 =/= 0.U)
    // 4. 读写权限合法性检查：如果 CSR 是只读的，且当前指令尝试写入，则非法
    val isRwLegal = !(csrReadOnly && isCsrWrite)
    // 5. 综合判定：必须同时满足特权级要求 和 读写权限要求
    val isCsrAccessLegal = isPrivLegal && isRwLegal

    // CSR 访问异常处理
    when(!isCsrAccessLegal) {
      decodeException.valid := true.B
      decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
      decodeException.tval := pc
    }
  }.elsewhen(isEcall) {
    decodeException.valid := true.B
    decodeException.cause := MuxCase(
      ExceptionCause.ECALL_FROM_M_MODE,
      Seq(
        (privMode === PrivMode.U) -> ExceptionCause.ECALL_FROM_U_MODE,
        (privMode === PrivMode.S) -> ExceptionCause.ECALL_FROM_S_MODE,
        (privMode === PrivMode.M) -> ExceptionCause.ECALL_FROM_M_MODE
      )
    )
    decodeException.tval := pc
  }.elsewhen(isEbreak) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.BREAKPOINT
    decodeException.tval := pc
  }.elsewhen(isMret) {
    // MRET 指令仅允许在特权级别 M 执行
    when(privMode =/= PrivMode.M) {
      decodeException.valid := true.B
      decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
      decodeException.tval := pc
    }
  }.elsewhen(isSret || isSFENCE || isWFI) {
    // SFENCE.VMA 指令仅允许在特权级别 S 或 M 执行
    when(privMode === PrivMode.U) {
      decodeException.valid := true.B
      decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
      decodeException.tval := pc
    }
  }

  // 合并输入异常和译码异常
  hasException := io.in.valid && (inputException.valid || decodeException.valid)

  val finalException = Wire(new Exception)
  when(inputException.valid) {
    finalException := inputException
  }.otherwise {
    finalException := decodeException
  }

  // ========== 流水线控制 ==========
  // 检测下游模块是否已满
  robFull := !io.robInit.ready
  rsFull := !io.dispatch.ready
  ratFull := !io.renameReq.ready

  // CSR 和特权指令需要串行化执行
  val csrDecoding = specialInstr === SpecialInstr.CSR ||
    specialInstr === SpecialInstr.ECALL ||
    specialInstr === SpecialInstr.EBREAK ||
    specialInstr === SpecialInstr.MRET ||
    specialInstr === SpecialInstr.SRET ||
    specialInstr === SpecialInstr.WFI ||
    specialInstr === SpecialInstr.SFENCE

  // Stall 条件
  needStall := robFull || rsFull || ratFull || io.csrPending
  // Flush 条件
  needFlush := io.branchFlush || io.globalFlush
  // 向 Fetcher 发送 Stall 信号
  io.ifStall := (needStall || csrDecoding) && !needFlush
  // 向 Icache 发送 ready 信号
  io.in.ready := !(needStall || needFlush)
  // 生成该阶段 valid 信号
  decoderValid := io.in.valid && !needStall && !needFlush

  // ========== 输出数据打包 ==========
  // 构建重命名请求
  io.renameReq.valid := decoderValid
  io.renameReq.bits.rs1 := rs1
  io.renameReq.bits.rs2 := rs2
  io.renameReq.bits.rd := Mux(hasException || !canWB, 0.U, rd)
  io.renameReq.bits.isBranch := (specialInstr === SpecialInstr.BRANCH)

  // ROB 初始化控制包
  io.robInit.valid := decoderValid
  io.robInit.bits.pc := pc
  io.robInit.bits.prediction := prediction
  io.robInit.bits.exception := finalException
  io.robInit.bits.specialInstr := specialInstr

  // 分派包
  io.dispatch.valid := decoderValid && !hasException
  io.dispatch.bits.robId := io.freeRobID
  io.dispatch.bits.microOp.aluOp := aluOp
  io.dispatch.bits.microOp.op1Src := op1Src
  io.dispatch.bits.microOp.op2Src := op2Src
  io.dispatch.bits.microOp.lsuOp := lsuOp
  io.dispatch.bits.microOp.lsuWidth := lsuWidth
  io.dispatch.bits.microOp.lsuSign := lsuSign
  io.dispatch.bits.microOp.bruOp := bruOp
  io.dispatch.bits.microOp.zicsrOp := zicsrOp
  io.dispatch.bits.pc := pc
  io.dispatch.bits.imm := imm
  io.dispatch.bits.csrAddr := csrAddr
  io.dispatch.bits.privMode := privMode
  io.dispatch.bits.prediction := prediction
}
