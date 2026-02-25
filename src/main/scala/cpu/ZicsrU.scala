package cpu

import chisel3._
import chisel3.util._

class ZicsrU extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 RS 的输入
    val zicsrReq = Flipped(Decoupled(new ZicsrDispatch))

    // 向 PRF 的读请求
    val prfReq = Decoupled(new PrfReadPacket)
    // 来自 PRF 的回复数据
    val prfData = Flipped(Decoupled(new PrfReadData))

    // 来自 CSRsUnit 的接口
    val csrReadReq = Decoupled(new CsrReadReq)
    val csrReadResp = Input(new CsrReadResp)
    val csrWriteReq = Decoupled(new CsrWriteReq)
    val csrWriteResp = Input(new CsrWriteResp)

    // 来自 ROB 的输入
    val commitReady = Input(Bool())

    // 输出到 CDB
    val cdb = Decoupled(new CDBMessage)
    val cdbIn = Flipped(Decoupled(new Bundle {
      val phyRd = PhyTag
    }))

    // 分支冲刷信号
    val branchFlush = Input(Bool())
    val branchOH = Input(SnapshotMask)
  })

  // 状态枚举
  object ZicsrState extends ChiselEnum {
    val IDLE = Value // 空闲状态
    val WAIT_READ = Value // 等待读取结果
    val WAIT_CDB1 = Value // 等待第一次广播（读结果）
    val WAIT_HEAD = Value // 等待 ROB 头部信号
    val WAIT_CDB2 = Value // 等待第二次广播（写结果）
  }

  // 当前状态
  val state = RegInit(ZicsrState.IDLE)

  // 指令寄存器
  val instructionReg = Reg(new ZicsrDispatch)
  val csrRdataReg = Reg(UInt(32.W)) // CSR 读取值寄存器
  val csrWdataReg = Reg(UInt(32.W)) // CSR 写入值寄存器
  val exceptionReg = Reg(new Exception) // 异常信息寄存器

  // 默认异常信息（无异常）
  val defaultException = Wire(new Exception)
  defaultException.valid := false.B
  defaultException.cause := 0.U(4.W)
  defaultException.tval := 0.U(32.W)

  // 定义使能信号
  val busy = WireDefault(false.B) // 模块忙碌标志
  val canRead = WireDefault(false.B) // 读取 Reg 与 CSR 并计算新值
  val canWrite = WireDefault(false.B) // 写回阶段，执行 CSR 写入
  val boardcast = WireDefault(false.B) // 广播阶段，向 CDB 广播结果
  val needFlush = WireDefault(false.B) // 可能被冲刷

  // 计算使能
  needFlush := io.branchFlush
  io.zicsrReq.ready := !needFlush && state === ZicsrState.IDLE
  canRead := !needFlush && state === ZicsrState.WAIT_READ && instructionReg.data.src1Ready
  canWrite := !needFlush && state === ZicsrState.WAIT_HEAD && io.commitReady
  boardcast := !needFlush && (state === ZicsrState.WAIT_CDB1 || state === ZicsrState.WAIT_CDB2)

  // 状态转移
  when(io.zicsrReq.fire) {
    state := ZicsrState.WAIT_READ
    instructionReg := io.zicsrReq.bits
    exceptionReg := defaultException
  }.elsewhen(canRead) {
    state := ZicsrState.WAIT_CDB1
  }.elsewhen(io.cdb.fire && state === ZicsrState.WAIT_CDB1) {
    state := ZicsrState.WAIT_HEAD
  }.elsewhen(canWrite) {
    state := ZicsrState.WAIT_CDB2
  }.elsewhen(io.cdb.fire && state === ZicsrState.WAIT_CDB2) {
    state := ZicsrState.IDLE
  }

  // 操作数就绪检测
  io.cdbIn.ready := state === ZicsrState.WAIT_READ
  when(io.cdbIn.fire && io.cdbIn.bits.phyRd === instructionReg.data.src1Tag) {
    instructionReg.data.src1Ready := true.B
  }

  // 判定目标寄存器是否为 x0（用于 RW 操作的副作用规避）
  val rdIsX0 = instructionReg.phyRd === 0.U

  // 判定源操作数是否为 x0（用于 RS/RC 操作的副作用规避）
  val src1IsX0 =
    (instructionReg.data.src1Sel === Src1Sel.ZERO && instructionReg.data.imm === 0.U) ||
      (instructionReg.data.src1Sel === Src1Sel.REG && instructionReg.data.src1Tag === 0.U)

  // 集体使能
  // 根据 x0 判定控制 CSR 读请求（RW 操作：rd == x0 时不读）
  io.csrReadReq.valid := canRead && !rdIsX0
  io.prfReq.valid := canRead
  io.prfData.ready := canRead

  // 发送 CSR 读请求
  io.csrReadReq.bits.csrAddr := instructionReg.csrAddr
  io.csrReadReq.bits.privMode := instructionReg.privMode

  // 从 PRF 读取 RS1 数据
  io.prfReq.bits.raddr1 := instructionReg.data.src1Tag
  io.prfReq.bits.raddr2 := 0.U // CSR 指令不需要第二个操作数

  val oldValue = Mux(canRead, io.csrReadResp.data, 0.U)
  // 根据信息计算 CSR 之外的操作数
  val operand1 = MuxCase(
    0.U,
    Seq(
      (instructionReg.data.src1Sel === Src1Sel.REG) -> io.prfData.bits.rdata1,
      (instructionReg.data.src1Sel === Src1Sel.ZERO) -> instructionReg.data.imm
    )
  )

  // 新值计算
  val newValue = MuxCase(
    oldValue,
    Seq(
      (instructionReg.zicsrOp === ZicsrOp.RW) -> operand1, // CSRRW/CSRRWI: 新值 = RS1/Imm
      (instructionReg.zicsrOp === ZicsrOp.RS) -> (oldValue | operand1), // CSRRS/CSRRSI: 新值 = 旧值 | RS1/Imm
      (instructionReg.zicsrOp === ZicsrOp.RC) -> (oldValue & ~operand1), // CSRRC/CSRRCI: 新值 = 旧值 & ~RS1/Imm
      (instructionReg.zicsrOp === ZicsrOp.NOP) -> oldValue // 无操作
    )
  )

  when(canRead) {
    // 根据 x0 判定控制读取返回值
    val csrReadResp = Mux(rdIsX0, 0.U.asTypeOf(new CsrReadResp), io.csrReadResp)
    csrRdataReg := csrReadResp.data
    exceptionReg := csrReadResp.exception
    val exceptionValid = csrReadResp.exception.valid
    csrWdataReg := Mux(exceptionValid, 0.U, newValue)
    instructionReg.csrAddr := Mux(exceptionValid, 0.U, instructionReg.csrAddr)
  }

  // CSR 写入请求
  // 根据 x0 判定控制 CSR 写请求（RS/RC 操作：rs1 == x0 或立即数为 0 时不写）
  io.csrWriteReq.valid := canWrite && !src1IsX0
  io.csrWriteReq.bits.csrAddr := instructionReg.csrAddr
  io.csrWriteReq.bits.privMode := instructionReg.privMode
  io.csrWriteReq.bits.data := csrWdataReg

  when(canWrite) {
    // 根据 x0 判定控制写响应
    val csrWriteResp =
      Mux(src1IsX0, 0.U.asTypeOf(new CsrWriteResp), io.csrWriteResp)
    exceptionReg := Mux(
      exceptionReg.valid,
      exceptionReg,
      csrWriteResp.exception
    )
  }

  // CDB 输出（使用 CDBMessage 结构体）
  io.cdb.valid := boardcast
  io.cdb.bits.robId := instructionReg.robId
  io.cdb.bits.phyRd := Mux(
    state === ZicsrState.WAIT_CDB2,
    instructionReg.phyRd,
    0.U
  )
  io.cdb.bits.data := Mux(state === ZicsrState.WAIT_CDB2, csrRdataReg, 0.U)
  io.cdb.bits.hasSideEffect := false.B
  io.cdb.bits.exception := exceptionReg

  // 冲刷处理
  when(
    (state =/= ZicsrState.IDLE) && (io.branchOH & instructionReg.branchMask) =/= 0.U
  ) {
    when(needFlush) {
      state := ZicsrState.IDLE
      instructionReg := 0.U.asTypeOf(instructionReg)
      csrRdataReg := 0.U
      csrWdataReg := 0.U
      exceptionReg := defaultException
    }.otherwise {
      // 移除对应的分支依赖
      instructionReg.branchMask := instructionReg.branchMask & ~io.branchOH
    }
  }
}
