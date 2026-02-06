package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class DecoderTest extends AnyFlatSpec with ChiselScalatestTester {

  // 辅助函数：设置默认输入信号
  def setDefaultInputs(dut: Decoder): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.freeRobID.poke(0.U)
    dut.io.globalFlush.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.csrPending.poke(false.B)
    dut.io.renameReq.ready.poke(true.B)
    dut.io.robInit.ready.poke(true.B)
    dut.io.dispatch.ready.poke(true.B)
  }

  // 辅助函数：设置指令元数据
  def setInstMetadata(
      dut: Decoder,
      pc: Long,
      privMode: PrivMode.Type = PrivMode.M
  ): Unit = {
    dut.io.in.bits.instMetadata.pc.poke(pc.U)
    dut.io.in.bits.instMetadata.instEpoch.poke(0.U)
    dut.io.in.bits.instMetadata.prediction.taken.poke(false.B)
    dut.io.in.bits.instMetadata.prediction.targetPC.poke((pc + 4).U)
    dut.io.in.bits.instMetadata.exception.valid.poke(false.B)
    dut.io.in.bits.instMetadata.exception.cause.poke(0.U)
    dut.io.in.bits.instMetadata.exception.tval.poke(0.U)
    dut.io.in.bits.instMetadata.privMode.poke(privMode)
  }

  // ============================================================================
  // 1. RV32I 基础整数指令测试（R-Type）
  // ============================================================================

  "Decoder" should "正确解析 ADD 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // ADD x1, x2, x3: 0x003100b3 (0000000_00011_00010_000_00001_0110011)
      dut.io.in.bits.inst.poke(0x003100b3L.U)

      dut.io.renameReq.valid.expect(true.B)
      dut.io.renameReq.bits.rs1.expect(2.U)
      dut.io.renameReq.bits.rs2.expect(3.U)
      dut.io.renameReq.bits.rd.expect(1.U)
      dut.io.renameReq.bits.isBranch.expect(false.B)

      dut.io.robInit.valid.expect(true.B)
      dut.io.robInit.bits.pc.expect(0x80000000L.U)
      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.NONE)
      dut.io.robInit.bits.exception.valid.expect(false.B)

      dut.io.dispatch.valid.expect(true.B)
      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.ADD)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.REG)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.REG)
      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.NOP)
      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.NOP)
      dut.io.dispatch.bits.microOp.zicsrOp.expect(ZicsrOp.NOP)

      dut.io.ifStall.expect(false.B)
    }
  }

  it should "正确解析 SUB 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SUB x1, x2, x3: 0x403100b3 (0100000_00011_00010_000_00001_0110011)
      dut.io.in.bits.inst.poke(0x403100b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SUB)
      dut.io.renameReq.bits.rs1.expect(2.U)
      dut.io.renameReq.bits.rs2.expect(3.U)
      dut.io.renameReq.bits.rd.expect(1.U)
    }
  }

  it should "正确解析 SLL 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SLL x1, x2, x3: 0x003110b3 (0000000_00011_00010_001_00001_0110011)
      dut.io.in.bits.inst.poke(0x003110b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SLL)
    }
  }

  it should "正确解析 SLT 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SLT x1, x2, x3: 0x003120b3 (0000000_00011_00010_010_00001_0110011)
      dut.io.in.bits.inst.poke(0x003120b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SLT)
    }
  }

  it should "正确解析 SLTU 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SLTU x1, x2, x3: 0x003130b3 (0000000_00011_00010_011_00001_0110011)
      dut.io.in.bits.inst.poke(0x003130b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SLTU)
    }
  }

  it should "正确解析 XOR 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // XOR x1, x2, x3: 0x003140b3 (0000000_00011_00010_100_00001_0110011)
      dut.io.in.bits.inst.poke(0x003140b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.XOR)
    }
  }

  it should "正确解析 SRL 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SRL x1, x2, x3: 0x003150b3 (0000000_00011_00010_101_00001_0110011)
      dut.io.in.bits.inst.poke(0x003150b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SRL)
    }
  }

  it should "正确解析 SRA 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SRA x1, x2, x3: 0x403150b3 (0100000_00011_00010_101_00001_0110011)
      dut.io.in.bits.inst.poke(0x403150b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SRA)
    }
  }

  it should "正确解析 OR 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // OR x1, x2, x3: 0x003160b3 (0000000_00011_00010_110_00001_0110011)
      dut.io.in.bits.inst.poke(0x003160b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.OR)
    }
  }

  it should "正确解析 AND 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // AND x1, x2, x3: 0x003170b3 (0000000_00011_00010_111_00001_0110011)
      dut.io.in.bits.inst.poke(0x003170b3L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.AND)
    }
  }

  // ============================================================================
  // 2. RV32I 基础整数指令测试（I-Type）
  // ============================================================================

  it should "正确解析 ADDI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // ADDI x1, x2, 0x123: 0x12310093 (imm[11:0]=0x123, rs1=2, rd=1)
      dut.io.in.bits.inst.poke(0x12310093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.ADD)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.REG)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.IMM)
      dut.io.dispatch.bits.imm.expect(0x123L.U)
      dut.io.renameReq.bits.rs1.expect(2.U)
      dut.io.renameReq.bits.rd.expect(1.U)
    }
  }

  it should "正确解析 SLTI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SLTI x1, x2, 0x123: 0x12312093 (imm[11:0]=0x123, rs1=2, rd=1)
      dut.io.in.bits.inst.poke(0x12312093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SLT)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.IMM)
    }
  }

  it should "正确解析 SLTIU 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SLTIU x1, x2, 0x123: 0x12313093
      dut.io.in.bits.inst.poke(0x12313093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SLTU)
    }
  }

  it should "正确解析 XORI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // XORI x1, x2, 0x123: 0x12314093
      dut.io.in.bits.inst.poke(0x12314093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.XOR)
    }
  }

  it should "正确解析 ORI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // ORI x1, x2, 0x123: 0x12316093
      dut.io.in.bits.inst.poke(0x12316093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.OR)
    }
  }

  it should "正确解析 ANDI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // ANDI x1, x2, 0x123: 0x12317093
      dut.io.in.bits.inst.poke(0x12317093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.AND)
    }
  }

  it should "正确解析 SLLI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SLLI x1, x2, 5: 0x00511093 (shamt=5, rs1=2, rd=1)
      dut.io.in.bits.inst.poke(0x00511093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SLL)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.IMM)
      dut.io.dispatch.bits.imm.expect(5.U)
    }
  }

  it should "正确解析 SRLI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SRLI x1, x2, 5: 0x00515093
      dut.io.in.bits.inst.poke(0x00515093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SRL)
    }
  }

  it should "正确解析 SRAI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SRAI x1, x2, 5: 0x40515093
      dut.io.in.bits.inst.poke(0x40515093L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.SRA)
    }
  }

  // ============================================================================
  // 3. RV32I 大立即数与 PC 相关指令测试（U-Type）
  // ============================================================================

  it should "正确解析 LUI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // LUI x1, 0x12345: 0x123450b7 (imm[31:12]=0x12345, rd=1)
      dut.io.in.bits.inst.poke(0x123450b7L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.ADD)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.ZERO)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.IMM)
      dut.io.dispatch.bits.imm.expect(0x12345000L.U)
      dut.io.renameReq.bits.rd.expect(1.U)
    }
  }

  it should "正确解析 AUIPC 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // AUIPC x1, 0x12345: 0x12345097
      dut.io.in.bits.inst.poke(0x12345097L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.ADD)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.PC)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.IMM)
      dut.io.dispatch.bits.imm.expect(0x12345000L.U)
    }
  }

  // ============================================================================
  // 4. RV32I 控制流指令测试（B-Type/J-Type）
  // ============================================================================

  it should "正确解析 BEQ 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // BEQ x1, x2, 0x10: 0x00208463 (imm=0x10, rs2=2, rs1=1)
      dut.io.in.bits.inst.poke(0x00208463L.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.BEQ)
      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.NOP)
      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.NOP)
      dut.io.dispatch.bits.imm.expect(0x8L.U)

      dut.io.renameReq.valid.expect(true.B)
      dut.io.renameReq.bits.isBranch.expect(true.B)

      dut.io.robInit.valid.expect(true.B)
      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.BRANCH)
    }
  }

  it should "正确解析 BNE 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // BNE x1, x2, 0x10: 0x00209463
      dut.io.in.bits.inst.poke(0x00209463L.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.BNE)
    }
  }

  it should "正确解析 BLT 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // BLT x1, x2, 0x10: 0x0020c463
      dut.io.in.bits.inst.poke(0x0020c463L.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.BLT)
    }
  }

  it should "正确解析 BGE 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // BGE x1, x2, 0x10: 0x0020d463
      dut.io.in.bits.inst.poke(0x0020d463L.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.BGE)
    }
  }

  it should "正确解析 BLTU 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // BLTU x1, x2, 0x10: 0x0020e463
      dut.io.in.bits.inst.poke(0x0020e463L.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.BLTU)
    }
  }

  it should "正确解析 BGEU 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // BGEU x1, x2, 0x10: 0x0020f463
      dut.io.in.bits.inst.poke(0x0020f463L.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.BGEU)
    }
  }

  it should "正确解析 JAL 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // JAL x1, 0x100: 0x100000ef (imm=0x100, rd=1)
      dut.io.in.bits.inst.poke(0x100000efL.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.JAL)
      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.NOP)
      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.NOP)
      dut.io.dispatch.bits.imm.expect(0x100L.U)

      dut.io.renameReq.valid.expect(true.B)
      dut.io.renameReq.bits.isBranch.expect(true.B)

      dut.io.robInit.valid.expect(true.B)
      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.BRANCH)
    }
  }

  it should "正确解析 JALR 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // JALR x1, x2, 0x10: 0x004100E7 (imm=0x4, rs1=2, rd=1)
      dut.io.in.bits.inst.poke(0x004100e7L.U)

      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.JALR)
      dut.io.dispatch.bits.imm.expect(0x4L.U)
      dut.io.renameReq.bits.rs1.expect(2.U)
    }
  }

  // ============================================================================
  // 5. RV32I 访存指令测试
  // ============================================================================

  it should "正确解析 LB 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // LB x1, 0x10(x2): 0x00410083 (imm=0x4, rs1=2, rd=1)
      dut.io.in.bits.inst.poke(0x00410083L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.NOP)
      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.LOAD)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.BYTE)
      dut.io.dispatch.bits.microOp.lsuSign.expect(LSUsign.SIGNED)
      dut.io.dispatch.bits.imm.expect(0x4L.U)
      dut.io.renameReq.bits.rs1.expect(2.U)
      dut.io.renameReq.bits.rd.expect(1.U)
    }
  }

  it should "正确解析 LH 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // LH x1, 0x10(x2): 0x01011083
      dut.io.in.bits.inst.poke(0x01011083L.U)

      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.LOAD)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.HALF)
      dut.io.dispatch.bits.microOp.lsuSign.expect(LSUsign.SIGNED)
    }
  }

  it should "正确解析 LW 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // LW x1, 0x10(x2): 0x00412083
      dut.io.in.bits.inst.poke(0x00412083L.U)

      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.LOAD)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.WORD)
      dut.io.dispatch.bits.microOp.lsuSign.expect(LSUsign.SIGNED)
    }
  }

  it should "正确解析 LBU 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // LBU x1, 0x10(x2): 0x00414083
      dut.io.in.bits.inst.poke(0x00414083L.U)

      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.LOAD)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.BYTE)
      dut.io.dispatch.bits.microOp.lsuSign.expect(LSUsign.UNSIGNED)
    }
  }

  it should "正确解析 LHU 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // LHU x1, 0x10(x2): 0x00415083
      dut.io.in.bits.inst.poke(0x00415083L.U)

      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.LOAD)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.HALF)
      dut.io.dispatch.bits.microOp.lsuSign.expect(LSUsign.UNSIGNED)
    }
  }

  it should "正确解析 SB 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SB x3, 0x8(x1): 0x00308423 (imm=0x8, rs2=3, rs1=1)
      dut.io.in.bits.inst.poke(0x00308423L.U)

      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.NOP)
      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.STORE)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.BYTE)
      dut.io.dispatch.bits.microOp.lsuSign.expect(LSUsign.UNSIGNED)
      dut.io.dispatch.bits.microOp.bruOp.expect(BRUOp.NOP)
      dut.io.dispatch.bits.imm.expect(0x8L.U)
      dut.io.renameReq.bits.rs1.expect(1.U)
      dut.io.renameReq.bits.rs2.expect(3.U)
      dut.io.renameReq.bits.isBranch.expect(false.B)
      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.STORE)
    }
  }

  it should "正确解析 SH 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SH x3, 0x8(x1): 0x00309423
      dut.io.in.bits.inst.poke(0x00309423L.U)

      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.STORE)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.HALF)
    }
  }

  it should "正确解析 SW 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SW x3, 0x8(x1): 0x0030a423
      dut.io.in.bits.inst.poke(0x0030a423L.U)

      dut.io.dispatch.bits.microOp.lsuOp.expect(LSUOp.STORE)
      dut.io.dispatch.bits.microOp.lsuWidth.expect(LSUWidth.WORD)
    }
  }

  it should "正确解析 FENCE 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // FENCE: 0x0ff0000f
      dut.io.in.bits.inst.poke(0x0ff0000fL.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.FENCE)
      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.NOP)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.ZERO)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.FOUR)
    }
  }

  // ============================================================================
  // 6. Zifencei 扩展指令测试
  // ============================================================================

  it should "正确解析 FENCE.I 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // FENCE.I: 0x0000100f
      dut.io.in.bits.inst.poke(0x0000100fL.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.FENCEI)
      dut.io.dispatch.bits.microOp.aluOp.expect(ALUOp.NOP)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.ZERO)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.FOUR)
    }
  }

  // ============================================================================
  // 7. Zicsr 扩展指令测试
  // ============================================================================

  it should "正确解析 CSRRW 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRW x1, mstatus, x2: 0x300110F3 (csr=0x300, rs1=2, rd=1)
      dut.io.in.bits.inst.poke(0x300110f3L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.CSR)
      dut.io.dispatch.bits.microOp.zicsrOp.expect(ZicsrOp.RW)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.REG)
      dut.io.dispatch.bits.microOp.op2Src.expect(Src2Sel.FOUR)
      dut.io.renameReq.bits.rs1.expect(2.U)
      dut.io.renameReq.bits.rd.expect(1.U)
    }
  }

  it should "正确解析 CSRRS 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRS x1, mstatus, x2: 0x300120F3
      dut.io.in.bits.inst.poke(0x300120f3L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.CSR)
      dut.io.dispatch.bits.microOp.zicsrOp.expect(ZicsrOp.RS)
    }
  }

  it should "正确解析 CSRRC 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRC x1, mstatus, x2: 0x300130F3
      dut.io.in.bits.inst.poke(0x300130f3L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.CSR)
      dut.io.dispatch.bits.microOp.zicsrOp.expect(ZicsrOp.RC)
    }
  }

  it should "正确解析 CSRRWI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRWI x1, mstatus, 2: 0x300150F3 (csr=0x300, uimm=5, rd=1)
      dut.io.in.bits.inst.poke(0x300150f3L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.CSR)
      dut.io.dispatch.bits.microOp.zicsrOp.expect(ZicsrOp.RW)
      dut.io.dispatch.bits.microOp.op1Src.expect(Src1Sel.ZERO)
      dut.io.dispatch.bits.imm.expect(2.U)
    }
  }

  it should "正确解析 CSRRSI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRSI x1, mstatus, 2: 0x300160F3
      dut.io.in.bits.inst.poke(0x300160f3L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.CSR)
      dut.io.dispatch.bits.microOp.zicsrOp.expect(ZicsrOp.RS)
    }
  }

  it should "正确解析 CSRRCI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRCI x1, mstatus, 2: 0x300170F3
      dut.io.in.bits.inst.poke(0x300170f3L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.CSR)
      dut.io.dispatch.bits.microOp.zicsrOp.expect(ZicsrOp.RC)
    }
  }

  // ============================================================================
  // 8. 特权指令测试
  // ============================================================================

  it should "正确解析 ECALL 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // ECALL: 0x00000073
      dut.io.in.bits.inst.poke(0x00000073L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.ECALL)
      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ECALL_FROM_M_MODE)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)
      dut.io.dispatch.valid.expect(false.B)

      dut.clock.step()

      setInstMetadata(dut, 0x80000004L, PrivMode.U)
      dut.io.in.bits.inst.poke(0x00000073L.U)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ECALL_FROM_U_MODE)
    }
  }

  it should "正确解析 EBREAK 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // EBREAK: 0x00100073
      dut.io.in.bits.inst.poke(0x00100073L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.EBREAK)
      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause.expect(ExceptionCause.BREAKPOINT)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "正确解析 MRET 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // MRET: 0x30200073
      dut.io.in.bits.inst.poke(0x30200073L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.MRET)
    }
  }

  it should "正确解析 SRET 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SRET: 0x10200073
      dut.io.in.bits.inst.poke(0x10200073L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.SRET)
    }
  }

  it should "正确解析 WFI 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // WFI: 0x10500073
      dut.io.in.bits.inst.poke(0x10500073L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.WFI)
    }
  }

  it should "正确解析 SFENCE.VMA 指令" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SFENCE.VMA x1, x2: 0x12000073
      dut.io.in.bits.inst.poke(0x12000073L.U)

      dut.io.robInit.bits.specialInstr.expect(SpecialInstr.SFENCE)
    }
  }

  // ============================================================================
  // 9. 立即数处理测试
  // ============================================================================

  it should "立即数符号扩展正确（I-Type 负数）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // ADDI x1, x2, -1: 0xfff10093 (imm[11:0]=0xfff, 符号扩展为 0xffffffff)
      dut.io.in.bits.inst.poke(0xfff10093L.U)

      dut.io.dispatch.bits.imm.expect(0xffffffffL.U)
    }
  }

  it should "立即数符号扩展正确（B-Type 负数）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // BEQ x1, x2, -0x18: 0xfe2084e3 (imm=-0x18, 符号扩展为 0xfffffff0)
      dut.io.in.bits.inst.poke(0xfe2084e3L.U)

      dut.io.dispatch.bits.imm.expect(0xffffffe8L.U)
    }
  }

  it should "立即数符号扩展正确（J-Type 负数）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // JAL x1, -0x100: 0xf01ff0ef (imm=-0x100, 符号扩展为 0xffffff00)
      dut.io.in.bits.inst.poke(0xf01ff0efL.U)

      dut.io.dispatch.bits.imm.expect(0xffffff00L.U)
    }
  }

  it should "立即数符号扩展正确（S-Type 负数）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // SW x3, -0x10(x1): 0xfe308823 (imm=-0x10, 符号扩展为 0xfffffff0)
      dut.io.in.bits.inst.poke(0xfe308823L.U)

      dut.io.dispatch.bits.imm.expect(0xfffffff0L.U)
    }
  }

  it should "U-Type 立即数正确左移 12 位" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // LUI x1, 0x12345: 0x123450b7 (imm[31:12]=0x12345, 左移12位为 0x12345000)
      dut.io.in.bits.inst.poke(0x123450b7L.U)

      dut.io.dispatch.bits.imm.expect(0x12345000L.U)
    }
  }

  it should "Z-Type 立即数正确零扩展" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRWI x1, mstatus, 0x1f: 0x300fd0f3 (uimm=0x1f, 零扩展为 0x0000001f)
      dut.io.in.bits.inst.poke(0x300fd0f3L.U)

      dut.io.dispatch.bits.imm.expect(0x1f.U)
    }
  }

  // ============================================================================
  // 10. 异常检测测试
  // ============================================================================

  it should "检测非法指令异常" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 非法指令：0xffffffff
      dut.io.in.bits.inst.poke(0xffffffffL.U)

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "检测 CSR 越权访问异常（U 模式访问 M-only CSR）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.U)

      // CSRRW x1, mstatus (0x300), x2: 在 U 模式下访问 M-only CSR
      dut.io.in.bits.inst.poke(0x30029173L.U)

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)
    }
  }

  it should "检测 CSR 越权访问异常（S 模式访问 M-only CSR）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.S)

      // CSRRW x1, mstatus (0x300), x2: 在 S 模式下访问 M-only CSR
      dut.io.in.bits.inst.poke(0x30029173L.U)

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
    }
  }

  it should "允许 M 模式访问 M-only CSR" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // CSRRW x1, mstatus (0x300), x2: 在 M 模式下访问 M-only CSR（合法）
      dut.io.in.bits.inst.poke(0x30029173L.U)

      dut.io.robInit.bits.exception.valid.expect(false.B)
      dut.io.dispatch.valid.expect(true.B)
    }
  }

  it should "检测 ECALL 异常（U 模式）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.U)

      // ECALL: 0x00000073
      dut.io.in.bits.inst.poke(0x00000073L.U)

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ECALL_FROM_U_MODE)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "检测 ECALL 异常（S 模式）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.S)

      // ECALL: 0x00000073
      dut.io.in.bits.inst.poke(0x00000073L.U)

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ECALL_FROM_S_MODE)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "检测 ECALL 异常（M 模式）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // ECALL: 0x00000073
      dut.io.in.bits.inst.poke(0x00000073L.U)

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ECALL_FROM_M_MODE)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "检测 EBREAK 异常" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // EBREAK: 0x00100073
      dut.io.in.bits.inst.poke(0x00100073L.U)

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause.expect(ExceptionCause.BREAKPOINT)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "输入异常优先级高于译码异常" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置输入异常
      dut.io.in.bits.instMetadata.exception.valid.poke(true.B)
      dut.io.in.bits.instMetadata.exception.cause
        .poke(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.in.bits.instMetadata.exception.tval.poke(0x80000000L.U)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U) // ADD x1, x2, x3

      // 应该使用输入异常
      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)
    }
  }

  it should "检测 MRET 越权访问异常（U/S 模式尝试执行 MRET）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)

      // MRET 机器码: 0x30200073
      val mretInst = 0x30200073L.U

      // Case 1: U 模式执行 MRET -> 异常
      setInstMetadata(dut, 0x80000000L, PrivMode.U)
      dut.io.in.bits.inst.poke(mretInst)
      dut.clock.step()

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000000L.U)

      // Case 2: S 模式执行 MRET -> 异常 (MRET 仅限 M 模式)
      setInstMetadata(dut, 0x80000004L, PrivMode.S)
      dut.io.in.bits.inst.poke(mretInst)
      dut.clock.step()

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000004L.U)
    }
  }

  it should "检测 SRET 越权访问异常（U 模式尝试执行 SRET）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)

      // SRET 机器码: 0x10200073
      val sretInst = 0x10200073L.U

      // Case: U 模式执行 SRET -> 异常
      setInstMetadata(dut, 0x80000010L, PrivMode.U)
      dut.io.in.bits.inst.poke(sretInst)
      dut.clock.step()

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000010L.U)
    }
  }

  it should "检测 SFENCE.VMA 越权访问异常（U 模式尝试执行）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)

      // SFENCE.VMA x0, x0 机器码: 0x12000073
      val sfenceInst = 0x12000073L.U

      // Case: U 模式执行 SFENCE.VMA -> 异常
      setInstMetadata(dut, 0x80000020L, PrivMode.U)
      dut.io.in.bits.inst.poke(sfenceInst)
      dut.clock.step()

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000020L.U)
    }
  }

  it should "检测 WFI 越权访问异常（U 模式尝试执行）" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)

      // WFI 机器码: 0x10500073
      val wfiInst = 0x10500073L.U

      // Case: U 模式执行 WFI -> 异常
      // 注：在某些配置下(mstatus.TW=0) U模式可能允许执行WFI，
      // 但标准实现通常将其视为非法或需要 S/M 权限。此处假设通用非法场景。
      setInstMetadata(dut, 0x80000030L, PrivMode.U)
      dut.io.in.bits.inst.poke(wfiInst)
      dut.clock.step()

      dut.io.robInit.bits.exception.valid.expect(true.B)
      dut.io.robInit.bits.exception.cause
        .expect(ExceptionCause.ILLEGAL_INSTRUCTION)
      dut.io.robInit.bits.exception.tval.expect(0x80000030L.U)
    }
  }

  // ============================================================================
  // 11. 流水线控制信号测试
  // ============================================================================

  it should "ROB 满时发出 stall 信号" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 ROB 满
      dut.io.robInit.ready.poke(false.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U) // ADD x1, x2, x3

      dut.io.ifStall.expect(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.renameReq.valid.expect(false.B)
      dut.io.robInit.valid.expect(false.B)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "RS 满时发出 stall 信号" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 RS 满
      dut.io.dispatch.ready.poke(false.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U)

      dut.io.ifStall.expect(true.B)
      dut.io.in.ready.expect(false.B)
    }
  }

  it should "RAT 满时发出 stall 信号" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 RAT 满
      dut.io.renameReq.ready.poke(false.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U)

      dut.io.ifStall.expect(true.B)
      dut.io.in.ready.expect(false.B)
    }
  }

  it should "csrPending 为高时发出 stall 信号" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 csrPending 为高
      dut.io.csrPending.poke(true.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U) // ADD x1, x2, x3

      dut.io.ifStall.expect(true.B)
      dut.io.in.ready.expect(false.B)
    }
  }

  it should "flush 拉高时不发出 stall 信号" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 globalFlush 为高
      dut.io.globalFlush.poke(true.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U)

      // flush 时不应该发出 stall 信号
      dut.io.ifStall.expect(false.B)
      // 但是流水线的阻塞依旧成立
      dut.io.in.ready.expect(false.B)
      dut.io.renameReq.valid.expect(false.B)
      dut.io.robInit.valid.expect(false.B)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "branchFlush 拉高时不发出 stall 信号" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 branchFlush 为高
      dut.io.branchFlush.poke(true.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U)

      // flush 时不应该发出 stall 信号
      dut.io.ifStall.expect(false.B)
      dut.io.in.ready.expect(false.B)
    }
  }

  it should "同时有 stall 和 flush 时 flush 优先" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 ROB 满和 globalFlush
      dut.io.robInit.ready.poke(false.B)
      dut.io.globalFlush.poke(true.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U)

      // flush 优先，不应该发出 stall 信号
      dut.io.ifStall.expect(false.B)
      dut.io.in.ready.expect(false.B)
    }
  }

  it should "同时有 csrPending 和 flush 时 flush 优先" in {
    test(new Decoder) { dut =>
      setDefaultInputs(dut)
      setInstMetadata(dut, 0x80000000L, PrivMode.M)

      // 设置 csrPending 和 branchFlush
      dut.io.csrPending.poke(true.B)
      dut.io.branchFlush.poke(true.B)

      // 合法指令
      dut.io.in.bits.inst.poke(0x003100b3L.U)

      // flush 优先，不应该发出 stall 信号
      dut.io.ifStall.expect(false.B)
      dut.io.in.ready.expect(false.B)
    }
  }
}
