package cpu

import chisel3._
import chisel3.util._

class ALU extends Module with CPUConfig {
  val io = IO(new Bundle {
    val in = Input(Decoupled(new AluDrivenPacket))
    val out = Output(Decoupled(new CDBMessage))
    val globalFlush = Input(Bool())
    val branchFlush = Input(Bool())
    val branchOH = Input(SnapshotMask)
  })

  // 操作数选择逻辑
  val src1Sel = io.in.bits.aluReq.data.src1Sel
  val src2Sel = io.in.bits.aluReq.data.src2Sel
  val rdata1 = io.in.bits.prfData.rdata1
  val rdata2 = io.in.bits.prfData.rdata2
  val imm = io.in.bits.aluReq.data.imm
  val pc = io.in.bits.aluReq.data.pc

  // 第一个操作数选择
  val op1 = MuxCase(
    0.U,
    Seq(
      (src1Sel === Src1Sel.REG) -> rdata1,
      (src1Sel === Src1Sel.PC) -> pc,
      (src1Sel === Src1Sel.ZERO) -> 0.U
    )
  )

  // 第二个操作数选择
  val op2 = MuxCase(
    0.U,
    Seq(
      (src2Sel === Src2Sel.REG) -> rdata2,
      (src2Sel === Src2Sel.IMM) -> imm,
      (src2Sel === Src2Sel.FOUR) -> 4.U
    )
  )

  // 操作执行逻辑
  val aluOp = io.in.bits.aluReq.aluOp
  val result = MuxCase(
    0.U,
    Seq(
      (aluOp === ALUOp.ADD) -> (op1 + op2),
      (aluOp === ALUOp.SUB) -> (op1 - op2),
      (aluOp === ALUOp.AND) -> (op1 & op2),
      (aluOp === ALUOp.OR) -> (op1 | op2),
      (aluOp === ALUOp.XOR) -> (op1 ^ op2),
      (aluOp === ALUOp.SLL) -> (op1 << op2(4, 0)),
      (aluOp === ALUOp.SRL) -> (op1 >> op2(4, 0)),
      (aluOp === ALUOp.SRA) -> (op1.asSInt >> op2(4, 0)).asUInt,
      (aluOp === ALUOp.SLT) -> (op1.asSInt < op2.asSInt),
      (aluOp === ALUOp.SLTU) -> (op1 < op2),
      (aluOp === ALUOp.NOP) -> 0.U
    )
  )

  // 结果寄存和广播逻辑
  // 流水线寄存器
  val busy = RegInit(false.B)
  val branchMaskReg = RegInit(0.U((1 << SnapshotIdWidth).W))
  val resultReg = RegInit(0.U asTypeOf (new CDBMessage))

  // 默认异常信息（无异常）
  val defaultException = Wire(new Exception)
  defaultException.valid := false.B
  defaultException.cause := 0.U(4.W)
  defaultException.tval := 0.U(32.W)

  // 冲刷信号
  val needFlush = io.globalFlush || io.branchFlush

  // 接收新指令
  io.in.ready := (!busy || io.out.fire) && !needFlush
  when(io.in.fire) {
    busy := true.B
    branchMaskReg := io.in.bits.aluReq.meta.branchMask
    resultReg.data := result
    resultReg.robId := io.in.bits.aluReq.meta.robId
    resultReg.phyRd := io.in.bits.aluReq.meta.phyRd
    resultReg.hasSideEffect := 0.U
    resultReg.exception := defaultException
  }.otherwise {
    when(io.out.fire) {
      busy := false.B
    }
  }

  // CDB 广播逻辑
  io.out.valid := busy && !needFlush

  // 冲刷处理
  when(io.globalFlush) {
    branchMaskReg := 0.U
  }.elsewhen((io.branchOH & branchMaskReg) =/= 0.U) {
    when(io.branchFlush) {
      busy := false.B
    }
    branchMaskReg := branchMaskReg & ~io.branchOH
  }
}
