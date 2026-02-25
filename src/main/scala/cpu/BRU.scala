package cpu

import chisel3._
import chisel3.util._

class BRU extends Module with CPUConfig {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new BruDrivenPacket))
    val out = Decoupled(new CDBMessage)
    val globalFlush = Input(Bool())
    val branchFlush = Output(Bool())
    val branchOH = Output(SnapshotMask)
    val branchPC = Output(UInt(32.W))
  })

  // 操作数选择逻辑
  val src1Sel = io.in.bits.bruReq.data.src1Sel
  val src2Sel = io.in.bits.bruReq.data.src2Sel
  val rdata1 = io.in.bits.prfData.rdata1
  val rdata2 = io.in.bits.prfData.rdata2
  val imm = io.in.bits.bruReq.data.imm
  val pc = io.in.bits.bruReq.data.pc
  val bruOp = io.in.bits.bruReq.bruOp
  val prediction = io.in.bits.bruReq.prediction

  // 第一个操作数选择
  val src1 = MuxCase(
    0.U,
    Seq(
      (src1Sel === Src1Sel.REG) -> rdata1,
      (src1Sel === Src1Sel.PC) -> pc,
      (src1Sel === Src1Sel.ZERO) -> 0.U
    )
  )

  // 第二个操作数选择
  val src2 = MuxCase(
    0.U,
    Seq(
      (src2Sel === Src2Sel.REG) -> rdata2,
      (src2Sel === Src2Sel.IMM) -> imm,
      (src2Sel === Src2Sel.FOUR) -> 4.U
    )
  )

  // 条件评估模块：根据分支操作类型评估分支条件
  val condition = MuxCase(
    false.B,
    Seq(
      (bruOp === BRUOp.BEQ) -> (src1 === src2),
      (bruOp === BRUOp.BNE) -> (src1 =/= src2),
      (bruOp === BRUOp.BLT) -> (src1.asSInt < src2.asSInt),
      (bruOp === BRUOp.BGE) -> (src1.asSInt >= src2.asSInt),
      (bruOp === BRUOp.BLTU) -> (src1 < src2),
      (bruOp === BRUOp.BGEU) -> (src1 >= src2),
      (bruOp === BRUOp.JAL) -> true.B,
      (bruOp === BRUOp.JALR) -> true.B,
      (bruOp === BRUOp.NOP) -> false.B
    )
  )

  // 目标计算模块：根据指令类型计算实际的跳转目标地址
  val takenTarget = MuxCase(
    0.U(32.W),
    Seq(
      (bruOp === BRUOp.JALR) -> ((src1 + imm) & ~1.U(32.W)),
      (bruOp === BRUOp.JAL) -> (pc + imm),
      // 其他分支指令使用 PC 相对寻址
      (bruOp === BRUOp.BEQ) -> (pc + imm),
      (bruOp === BRUOp.BNE) -> (pc + imm),
      (bruOp === BRUOp.BLT) -> (pc + imm),
      (bruOp === BRUOp.BGE) -> (pc + imm),
      (bruOp === BRUOp.BLTU) -> (pc + imm),
      (bruOp === BRUOp.BGEU) -> (pc + imm),
      (bruOp === BRUOp.NOP) -> (pc + 4.U)
    )
  )
  val actualTarget = Mux(condition, takenTarget, pc + 4.U)

  // 预测比较模块：将计算结果与预测进行比较
  val predictionCorrect = (condition === prediction.taken) &&
    (actualTarget === prediction.targetPC)

  // 返回地址计算模块：计算 JAL/JALR 指令的返回地址
  val returnAddr = pc + 4.U

  // 结果寄存器
  // 向 CDB 广播的结果
  val busy = RegInit(false.B)
  val resultReg = RegInit(0.U asTypeOf (new CDBMessage))
  // 全局 Branch 信号结果
  val branchFlushReg = RegInit(false.B)
  val branchPCReg = RegInit(0.U(32.W))
  val branchOHReg = RegInit(0.U(4.W))

  // 默认异常信息（无异常）
  val defaultException = Wire(new Exception)
  defaultException.valid := false.B
  defaultException.cause := 0.U(4.W)
  defaultException.tval := 0.U(32.W)

  // 冲刷信号
  // branchFlush 信号由此处产生
  val needFlush = io.globalFlush || branchFlushReg

  // 接收新指令
  io.in.ready := (!busy || io.out.fire) && !needFlush
  when(io.in.fire) {
    busy := true.B
    // 当周期计算分支结果
    branchOHReg := io.in.bits.bruReq.snapshotOH
    branchFlushReg := !predictionCorrect
    branchPCReg := Mux(predictionCorrect, 0.U, actualTarget)

    // 保存元数据
    resultReg.robId := io.in.bits.bruReq.meta.robId
    resultReg.phyRd := io.in.bits.bruReq.meta.phyRd
    // 计算并保存结果（返回地址）
    resultReg.data := returnAddr
    resultReg.hasSideEffect := false.B
    resultReg.exception := defaultException
  }.otherwise {
    // 一周期后清除决议信息
    branchFlushReg := false.B
    branchOHReg := 0.U
    branchPCReg := 0.U
    // CDB 完成广播后清除忙碌状态
    when(io.out.fire) {
      busy := false.B
    }
  }

  // 输出
  // 分支决议
  io.branchFlush := branchFlushReg
  io.branchOH := branchOHReg
  io.branchPC := branchPCReg
  // CDB 广播逻辑
  io.out.valid := busy && !needFlush
  io.out.bits := resultReg

  // 冲刷处理，全局冲刷抹去所有状态信息
  when(io.globalFlush) {
    busy := false.B
    io.branchFlush := false.B
    io.branchOH := 0.U
    io.branchPC := 0.U
  }
}
