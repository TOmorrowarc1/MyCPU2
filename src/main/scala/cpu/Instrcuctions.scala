package cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._ // 必须引入实验特性以支持模块外字面量

/**
 * DecodeEntry - 硬件控制信号束
 * 定义了指令在流水线中所需的所有静态控制信息
 */
class DecodeEntry extends Bundle {
  val aluOp        = ALUOp()
  val op1Src       = Src1Sel()
  val op2Src       = Src2Sel()
  val lsuOp        = LSUOp()
  val lsuWidth     = LSUWidth()
  val lsuSign      = LSUsign()
  val bruOp        = BRUOp()
  val immType      = ImmType()
  val specialInstr = SpecialInstr()
  val zicsrOp      = ZicsrOp()
  val canWB        = Bool()
  val isLegal      = Bool()
}

object Instructions {

  /**
   * 工厂函数 D: 显式构造每一个字段
   * 不使用默认参数，确保每一行指令定义的完整性和清晰性
   */
  private def D(
    alu: ALUOp.Type, 
    op1: Src1Sel.Type, 
    op2: Src2Sel.Type, 
    lsu: LSUOp.Type, 
    lsuW: LSUWidth.Type, 
    lsuS: LSUsign.Type, 
    bru: BRUOp.Type, 
    imm: ImmType.Type, 
    spec: SpecialInstr.Type, 
    csr: ZicsrOp.Type, 
    wb: Boolean,
    legal: Boolean
  ): DecodeEntry = {
    (new DecodeEntry).Lit(
      _.aluOp        -> alu,
      _.op1Src       -> op1,
      _.op2Src       -> op2,
      _.lsuOp        -> lsu,
      _.lsuWidth     -> lsuW,
      _.lsuSign      -> lsuS,
      _.bruOp        -> bru,
      _.immType      -> imm,
      _.specialInstr -> spec,
      _.zicsrOp      -> csr,
      _.canWB        -> wb.B,
      _.isLegal      -> legal.B
    )
  }

  // 默认项：当没有任何指令匹配时，产生非法指令异常
  val defaultEntry = D(ALUOp.NOP, Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP, LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP, ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP, false, false)

  /**
   * 指令查找全表
   * 采用列对齐格式，极大方便人工查错
   */
  val table: Seq[(BitPat, DecodeEntry)] = Seq(
    // 格式参考:
    // BitPat                                          ALU操作    Src1来源      Src2来源      LSU操作      LSU位宽        LSU符号         BRU操作     立即数格式      特殊指令类型         CSR操作     WB     合法性
    // =============================================================================================================================================================================================================
    
    // RV32I R-Type (opcode: 0110011)
    BitPat("b0000000_?????_?????_000_?????_0110011") -> D(ALUOp.ADD,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // ADD
    BitPat("b0100000_?????_?????_000_?????_0110011") -> D(ALUOp.SUB,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SUB
    BitPat("b0000000_?????_?????_001_?????_0110011") -> D(ALUOp.SLL,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SLL
    BitPat("b0000000_?????_?????_010_?????_0110011") -> D(ALUOp.SLT,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SLT
    BitPat("b0000000_?????_?????_011_?????_0110011") -> D(ALUOp.SLTU, Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SLTU
    BitPat("b0000000_?????_?????_100_?????_0110011") -> D(ALUOp.XOR,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // XOR
    BitPat("b0000000_?????_?????_101_?????_0110011") -> D(ALUOp.SRL,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SRL
    BitPat("b0100000_?????_?????_101_?????_0110011") -> D(ALUOp.SRA,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SRA
    BitPat("b0000000_?????_?????_110_?????_0110011") -> D(ALUOp.OR,   Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // OR
    BitPat("b0000000_?????_?????_111_?????_0110011") -> D(ALUOp.AND,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // AND
    
    // RV32I I-Type (ALU) (opcode: 0010011)
    BitPat("b????????????_?????_000_?????_0010011") -> D(ALUOp.ADD,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // ADDI
    BitPat("b????????????_?????_010_?????_0010011") -> D(ALUOp.SLT,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SLTI
    BitPat("b????????????_?????_011_?????_0010011") -> D(ALUOp.SLTU, Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SLTIU
    BitPat("b????????????_?????_100_?????_0010011") -> D(ALUOp.XOR,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // XORI
    BitPat("b????????????_?????_110_?????_0010011") -> D(ALUOp.OR,   Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // ORI
    BitPat("b????????????_?????_111_?????_0010011") -> D(ALUOp.AND,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // ANDI
    BitPat("b0000000_?????_?????_001_?????_0010011") -> D(ALUOp.SLL,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SLLI
    BitPat("b0000000_?????_?????_101_?????_0010011") -> D(ALUOp.SRL,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SRLI
    BitPat("b0100000_?????_?????_101_?????_0010011") -> D(ALUOp.SRA,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // SRAI
    
    // RV32I U-Type (opcode: 0110111, 0010111)
    BitPat("b????????????_?????_0110111")           -> D(ALUOp.ADD,  Src1Sel.ZERO, Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.U_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // LUI
    BitPat("b????????????_?????_0010111")           -> D(ALUOp.ADD,  Src1Sel.PC,   Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.U_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // AUIPC
    
    // RV32I Load (opcode: 0000011)
    BitPat("b????????????_?????_000_?????_0000011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.LOAD,  LSUWidth.BYTE, LSUsign.SIGNED,   BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // LB
    BitPat("b????????????_?????_001_?????_0000011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.LOAD,  LSUWidth.HALF, LSUsign.SIGNED,   BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // LH
    BitPat("b????????????_?????_010_?????_0000011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.LOAD,  LSUWidth.WORD, LSUsign.SIGNED,   BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // LW
    BitPat("b????????????_?????_100_?????_0000011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.LOAD,  LSUWidth.BYTE, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // LBU
    BitPat("b????????????_?????_101_?????_0000011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.LOAD,  LSUWidth.HALF, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE,   ZicsrOp.NOP, true,  true), // LHU
    
    // RV32I Store (opcode: 0100011)
    BitPat("b???????_?????_?????_000_?????_0100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.STORE, LSUWidth.BYTE, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.S_TYPE, SpecialInstr.STORE,  ZicsrOp.NOP, false, true), // SB
    BitPat("b???????_?????_?????_001_?????_0100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.STORE, LSUWidth.HALF, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.S_TYPE, SpecialInstr.STORE,  ZicsrOp.NOP, false, true), // SH
    BitPat("b???????_?????_?????_010_?????_0100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.STORE, LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.S_TYPE, SpecialInstr.STORE,  ZicsrOp.NOP, false, true), // SW
    
    // RV32I Branch (opcode: 1100011)
    BitPat("b???????_?????_?????_000_?????_1100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BEQ,  ImmType.B_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, false, true), // BEQ
    BitPat("b???????_?????_?????_001_?????_1100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BNE,  ImmType.B_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, false, true), // BNE
    BitPat("b???????_?????_?????_100_?????_1100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BLT,  ImmType.B_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, false, true), // BLT
    BitPat("b???????_?????_?????_101_?????_1100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BGE,  ImmType.B_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, false, true), // BGE
    BitPat("b???????_?????_?????_110_?????_1100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BLTU, ImmType.B_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, false, true), // BLTU
    BitPat("b???????_?????_?????_111_?????_1100011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.REG,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BGEU, ImmType.B_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, false, true), // BGEU
    
    // RV32I Jump (opcode: 1101111, 1100111)
    BitPat("b????????????????????_?????_1101111")   -> D(ALUOp.NOP,  Src1Sel.PC,   Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.JAL,  ImmType.J_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, true,  true), // JAL
    BitPat("b????????????_?????_000_?????_1100111") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.IMM,  LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.JALR, ImmType.I_TYPE, SpecialInstr.BRANCH, ZicsrOp.NOP, true,  true), // JALR
    
    // RV32I FENCE (opcode: 0001111)
    BitPat("b0000????????_00000_000_00000_0001111") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.FENCE,  ZicsrOp.NOP, false, true), // FENCE
    
    // Zifencei Extension (opcode: 0001111)
    BitPat("b000000000000_00000_001_00000_0001111") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.FENCEI, ZicsrOp.NOP, false, true), // FENCE.I
    
    // Zicsr Extension (opcode: 1110011)
    BitPat("b????????????_?????_001_?????_1110011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.CSR,    ZicsrOp.RW,  true,  true), // CSRRW
    BitPat("b????????????_?????_010_?????_1110011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.CSR,    ZicsrOp.RS,  true,  true), // CSRRS
    BitPat("b????????????_?????_011_?????_1110011") -> D(ALUOp.NOP,  Src1Sel.REG,  Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.CSR,    ZicsrOp.RC,  true,  true), // CSRRC
    BitPat("b????????????_?????_101_?????_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.CSR,    ZicsrOp.RW,  true,  true), // CSRRWI
    BitPat("b????????????_?????_110_?????_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.CSR,    ZicsrOp.RS,  true,  true), // CSRRSI
    BitPat("b????????????_?????_111_?????_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.CSR,    ZicsrOp.RC,  true,  true), // CSRRCI
    
    // Privileged Instructions (opcode: 1110011)
    BitPat("b000000000000_00000_000_00000_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.ECALL,  ZicsrOp.NOP, false, true), // ECALL
    BitPat("b000000000001_00000_000_00000_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.EBREAK, ZicsrOp.NOP, false, true), // EBREAK
    BitPat("b001100000010_00000_000_00000_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.MRET,   ZicsrOp.NOP, false, true), // MRET
    BitPat("b000100000010_00000_000_00000_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.SRET,   ZicsrOp.NOP, false, true), // SRET
    BitPat("b000100000101_00000_000_00000_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.WFI,    ZicsrOp.NOP, false, true), // WFI
    BitPat("b0001001?????_?????_000_00000_1110011") -> D(ALUOp.NOP,  Src1Sel.ZERO, Src2Sel.FOUR, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.SFENCE, ZicsrOp.NOP, false, true)  // SFENCE.VMA
  )
}