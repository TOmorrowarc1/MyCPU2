# Decoder 模块设计文档

## 概述

Decoder 模块是 Tomasulo 架构中的关键组件，负责将指令流解析为微操作（MicroOp），进行寄存器重命名请求，检测异常，并控制流水线的流动。对应两个文件：`Instructions.scala` （放置指令查找表）与 `Decoder.scala` （实现 Decoder 模块逻辑）。

## 职责

根据 [`Top.md`](../../Implement/Top.md:20-33) 中的定义，Decoder 的主要职责包括：

1. **指令解析**：将指令解析为微操作（MicroOp），进行寄存器重命名请求，检测异常，并控制流水线的流动。
2. **寄存器重命名请求**：向 RAT 请求寄存器重命名与快照。
3. **异常检测**：检测并透传指令相关的异常。
4. **流水线控制**：在 CSR 指令解析的当周期生成 stall 信号，在 CSR 在途过程中透传 ROB 传入 stall 信号控制取指。

## 输入输出

### 输入
- 来自 Icache 的 [`IDecodePacket`](../../Implement/Protocol.md:148-151)：包含 `Instruction`, `pc`, `PrivMode`, `InsEpoch`, `prediction`, `exception`
- 来自 ROB 的：`FreeRobID`, `GlobalFlush`, `CSRPending`
- 来自 BRU 的：`BranchFlush`

### 输出
- 向 RAT 发送：[`RenameReq`](../../Implement/Protocol.md:184-189) `{rs1, rs2, rd, isBranch}`
- 向 ROB 发送：[`ROBInitControl`](../../Implement/Protocol.md:154-159) `{pc, prediction, exception, specialInstr}`
- 向 RS 发送：[`DispatchPacket`](../../Implement/Protocol.md:173-181) `{robId, microOp, pc, imm, privMode, prediction, exception}`
- 向 Fetcher 发送：`IFStall`

## 第一部分：指令解析结果（使能信号部分）

### 1. RV32I 基础整数指令

#### 1.1 算术与逻辑运算（寄存器-寄存器）

**Opcode**: `0110011` (R-Type)

| 指令     | BitPattern (31-0)                | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :------- | :------------------------------- | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **ADD**  | `0000000_rs2_rs1_000_rd_0110011` | ADD   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **SUB**  | `0100000_rs2_rs1_000_rd_0110011` | SUB   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **SLL**  | `0000000_rs2_rs1_001_rd_0110011` | SLL   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **SLT**  | `0000000_rs2_rs1_010_rd_0110011` | SLT   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **SLTU** | `0000000_rs2_rs1_011_rd_0110011` | SLTU  | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **XOR**  | `0000000_rs2_rs1_100_rd_0110011` | XOR   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **SRL**  | `0000000_rs2_rs1_101_rd_0110011` | SRL   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **SRA**  | `0100000_rs2_rs1_101_rd_0110011` | SRA   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **OR**   | `0000000_rs2_rs1_110_rd_0110011` | OR    | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |
| **AND**  | `0000000_rs2_rs1_111_rd_0110011` | AND   | REG    | REG    | NOP   | WORD     | UNSIGNED | NOP   | R_TYPE  | NONE         | NOP     |

#### 1.2 算术与逻辑运算（寄存器-立即数）

**Opcode**: `0010011` (I-Type)

| 指令      | BitPattern                         | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :-------- | :--------------------------------- | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **ADDI**  | `imm[11:0]_rs1_000_rd_0010011`     | ADD   | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **SLTI**  | `imm[11:0]_rs1_010_rd_0010011`     | SLT   | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **SLTIU** | `imm[11:0]_rs1_011_rd_0010011`     | SLTU  | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **XORI**  | `imm[11:0]_rs1_100_rd_0010011`     | XOR   | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **ORI**   | `imm[11:0]_rs1_110_rd_0010011`     | OR    | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **ANDI**  | `imm[11:0]_rs1_111_rd_0010011`     | AND   | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **SLLI**  | `0000000_shamt_rs1_001_rd_0010011` | SLL   | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **SRLI**  | `0000000_shamt_rs1_101_rd_0010011` | SRL   | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **SRAI**  | `0100000_shamt_rs1_101_rd_0010011` | SRA   | REG    | IMM    | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |

#### 1.3 大立即数与 PC 相关指令

| 指令      | BitPattern              | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :-------- | :---------------------- | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **LUI**   | `imm[31:12]_rd_0110111` | ADD   | ZERO   | IMM    | NOP   | WORD     | UNSIGNED | NOP   | U_TYPE  | NONE         | NOP     |
| **AUIPC** | `imm[31:12]_rd_0010111` | ADD   | PC     | IMM    | NOP   | WORD     | UNSIGNED | NOP   | U_TYPE  | NONE         | NOP     |

#### 1.4 控制流指令（分支与跳转）

**Opcode**: `1100011` (B-Type), `1101111` (J-Type), `1100111` (I-Type)

| 指令     | BitPattern                            | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :------- | :------------------------------------ | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **JAL**  | `imm[20\|10:1\|11\|19:12]_rd_1101111` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | JAL   | J_TYPE  | BRANCH       | NOP     |
| **JALR** | `imm[11:0]_rs1_000_rd_1100111`        | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | JALR  | I_TYPE  | BRANCH       | NOP     |
| **BEQ**  | `imm..._rs2_rs1_000_..._1100011`      | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | BEQ   | B_TYPE  | BRANCH       | NOP     |
| **BNE**  | `imm..._rs2_rs1_001_..._1100011`      | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | BNE   | B_TYPE  | BRANCH       | NOP     |
| **BLT**  | `imm..._rs2_rs1_100_..._1100011`      | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | BLT   | B_TYPE  | BRANCH       | NOP     |
| **BGE**  | `imm..._rs2_rs1_101_..._1100011`      | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | BGE   | B_TYPE  | BRANCH       | NOP     |
| **BLTU** | `imm..._rs2_rs1_110_..._1100011`      | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | BLTU  | B_TYPE  | BRANCH       | NOP     |
| **BGEU** | `imm..._rs2_rs1_111_..._1100011`      | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | BGEU  | B_TYPE  | BRANCH       | NOP     |

#### 1.5 访存指令（Load/Store）

**Opcode**: `0000011` (Load), `0100011` (Store)

| 指令      | BitPattern                               | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :-------- | :--------------------------------------- | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **LB**    | `imm_rs1_000_rd_0000011`                 | ADD   | REG    | IMM    | LOAD  | BYTE     | SIGNED   | NOP   | I_TYPE  | NONE         | NOP     |
| **LH**    | `imm_rs1_001_rd_0000011`                 | ADD   | REG    | IMM    | LOAD  | HALF     | SIGNED   | NOP   | I_TYPE  | NONE         | NOP     |
| **LW**    | `imm_rs1_010_rd_0000011`                 | ADD   | REG    | IMM    | LOAD  | WORD     | SIGNED   | NOP   | I_TYPE  | NONE         | NOP     |
| **LBU**   | `imm_rs1_100_rd_0000011`                 | ADD   | REG    | IMM    | LOAD  | BYTE     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **LHU**   | `imm_rs1_101_rd_0000011`                 | ADD   | REG    | IMM    | LOAD  | HALF     | UNSIGNED | NOP   | I_TYPE  | NONE         | NOP     |
| **SB**    | `imm[11:5]_rs2_rs1_000_imm[4:0]_0100011` | ADD   | REG    | IMM    | STORE | BYTE     | UNSIGNED | NOP   | S_TYPE  | STORE        | NOP     |
| **SH**    | `imm[11:5]_rs2_rs1_001_imm[4:0]_0100011` | ADD   | REG    | IMM    | STORE | HALF     | UNSIGNED | NOP   | S_TYPE  | STORE        | NOP     |
| **SW**    | `imm[11:5]_rs2_rs1_010_imm[4:0]_0100011` | ADD   | REG    | IMM    | STORE | WORD     | UNSIGNED | NOP   | S_TYPE  | STORE        | NOP     |
| **FENCE** | `0000_pred_succ_00000_000_00000_0001111` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | FENCE        | NOP     |

### 2. Zifencei 扩展指令

| 指令        | BitPattern                             | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :---------- | :------------------------------------- | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **FENCE.I** | `000000000000_00000_001_00000_0001111` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | FENCEI       | NOP     |

### 3. Zicsr 扩展指令（CSR 访问）

**Opcode**: `1110011` (System)

| 指令       | BitPattern                | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :--------- | :------------------------ | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **CSRRW**  | `csr_rs1_001_rd_1110011`  | NOP   | REG    | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | CSR          | RW      |
| **CSRRS**  | `csr_rs1_010_rd_1110011`  | NOP   | REG    | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | CSR          | RS      |
| **CSRRC**  | `csr_rs1_011_rd_1110011`  | NOP   | REG    | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | I_TYPE  | CSR          | RC      |
| **CSRRWI** | `csr_uimm_101_rd_1110011` | NOP   | IMM    | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | CSR          | RW      |
| **CSRRSI** | `csr_uimm_110_rd_1110011` | NOP   | IMM    | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | CSR          | RS      |
| **CSRRCI** | `csr_uimm_111_rd_1110011` | NOP   | IMM    | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | CSR          | RC      |

**注意**：
- CSRRW/CSRRS/CSRRC 使用 I-Type 立即数（符号扩展）
- CSRRWI/CSRRSI/CSRRCI 使用 Z-Type 立即数（零扩展，从 rs1 字段提取 5 位立即数 uimm[4:0]）

### 4. 特权指令（Privileged Instructions）

**Opcode**: `1110011` (System)

| 指令           | BitPattern                             | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign  | bruOp | immType | specialInstr | zicsrOp |
| :------------- | :------------------------------------- | :---- | :----- | :----- | :---- | :------- | :------- | :---- | :------ | :----------- | :------ |
| **ECALL**      | `000000000000_00000_000_00000_1110011` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | ECALL        | NOP     |
| **EBREAK**     | `000000000001_00000_000_00000_1110011` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | EBREAK       | NOP     |
| **MRET**       | `001100000010_00000_000_00000_1110011` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | MRET         | NOP     |
| **SRET**       | `000100000010_00000_000_00000_1110011` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | SRET         | NOP     |
| **WFI**        | `000100000101_00000_000_00000_1110011` | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | WFI          | NOP     |
| **SFENCE.VMA** | `0001001_rs2_rs1_000_00000_1110011`    | NOP   | ZERO   | FOUR   | NOP   | WORD     | UNSIGNED | NOP   | Z_TYPE  | SFENCE       | NOP     |

---

## 第二部分：Decoder 模块设计

### 1. 模块接口定义（IO）

```scala
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
    val robInit = Decoupled(new ROBInitControl)

    // 向 RS 发送分派信息
    val dispatch = Decoupled(new DispatchPacket)

    // 向 Fetcher 发送 Stall 信号
    val ifStall = Output(Bool())
  })
}
```

### 2. 内部逻辑

#### 2.1 信号定义

```scala
  // 指令字段提取
  val inst = io.in.bits.inst
  val opcode = inst(6, 0)
  val rd = inst(11, 7)
  val funct3 = inst(14, 12)
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val funct7 = inst(31, 25)
  val funct12 = inst(31, 20)

  // 元数据透传
  val pc = io.in.bits.instMetadata.pc
  val insEpoch = io.in.bits.instMetadata.insEpoch
  val prediction = io.in.bits.instMetadata.prediction
  val inputException = io.in.bits.instMetadata.exception
  val privMode = io.in.bits.instMetadata.privMode

  // 译码控制信号
  val aluOp = Wire(ALUOp())
  val op1Src = Wire(Src1Sel())
  val op2Src = Wire(Src2Sel())
  val lsuOp = Wire(LSUOp())
  val lsuWidth = Wire(LSUWidth())
  val lsuSign = Wire(LSUsign())
  val bruOp = Wire(BRUOp())
  val immType = Wire(ImmType())
  val zicsrOp = Wire(ZicsrOp())

  // 指令类型标志
  val specialInstr = Wire(SpecialInstr())
  val isCsr = Wire(Bool())
  val isEcall = Wire(Bool())
  val isEbreak = Wire(Bool())

  // 立即数
  val imm = Wire(UInt(32.W))

  // 异常信号
  val decodeException = Wire(new Exception)
  val hasException = Wire(Bool())

  // Stall 信号
  val robFull = Wire(Bool())
  val rsFull = Wire(Bool())
  val ratFull = Wire(Bool())
  val needStall = Wire(Bool())

  val needFlush = Wire(Bool())
  val decoderValid = Wire(Bool())

```

#### 2.2 指令解码

##### 2.2.1 使用 LookupTable 进行指令分类

这部分逻辑放置在 `Instructions.scala` 文件中，定义了一个指令解码查找表 `decodeTable`，用于将指令的 bitpattern 映射到对应的解码信息。主要分为线束定义，辅助函数与解码表定义三部分：

```scala
package cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._ // 必须引入，用于在 Module 外创建硬件字面量

// 1. DecodeEntry 定义：指令生成的“样板”
class DecodeEntry extends Bundle with CPUConfig {
  val aluOp        = ALUOp()          // ALU 操作码
  val op1Src       = Src1Sel()        // 操作数 1 来源 (Reg/PC/Zero)
  val op2Src       = Src2Sel()        // 操作数 2 来源 (Reg/Imm/Four)
  val lsuOp        = LSUOp()          // 访存类型 (Load/Store/None)
  val lsuWidth     = LSUWidth()       // 访存位宽 (B/H/W)
  val lsuSign      = LSUsign()        // 访存符号扩展
  val bruOp        = BRUOp()          // 分支跳转类型
  val immType      = ImmType()        // 立即数解码格式
  val specialInstr = SpecialInstr()   // 特殊指令标记 (CSR/System/Fence)
  val zicsrOp      = ZicsrOp()        // CSR 操作类型
  val isLegal      = Bool()           // 指令合法性
}

// 2. 指令集数据库对象
object Instructions extends CPUConfig {

  // 辅助函数: 快速生成一个 DecodeEntry 字面量 (L2 -> L3 Literal)
    
  def D(alu: ALUOp.Type, op1: Src1Sel.Type, op2: Src2Sel.Type, 
        lsu: LSUOp.Type, lsuW: LSUWidth.Type, lsuS: LSUsign.Type,
        bru: BRUOp.Type, imm: ImmType.Type, spec: SpecialInstr.Type, 
        csr: ZicsrOp.Type): DecodeEntry = {
    
    // 使用 .Lit 构造硬件常数，这不属于任何 Module 作用域
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
      _.isLegal      -> true.B // 表项中的指令均为合法
    )
  }


  // 默认对象: 当 MuxLookup 没匹配到时的输出，标记为非法指令，用于触发异常。
  def default = (new DecodeEntry).Lit(
    _.aluOp        -> ALUOp.NOP,
    _.op1Src       -> Src1Sel.ZERO,
    _.op2Src       -> Src2Sel.FOUR,
    _.lsuOp        -> LSUOp.NOP,
    _.lsuWidth     -> LSUWidth.WORD,
    _.lsuSign      -> LSUsign.UNSIGNED,
    _.bruOp        -> BRUOp.NOP,
    _.immType      -> ImmType.R_TYPE,
    _.specialInstr -> SpecialInstr.NONE,
    _.zicsrOp      -> ZicsrOp.NOP,
    _.isLegal      -> false.B
  )

  // 3. 查找表 (Lookup Table)
  // 分门别类定义，最后通过 ++ 合并，方便维护。
  
  // BitPat -> ALUOp Src1 Src2 LSUOp LSUWidth LSUSign BRUOp ImmType SpecInstr ZicsrOp

  // RV32I 基础整数运算 - R-Type (opcode: 0110011)
  private val rv32i_rtype = Seq(
    BitPat("b0000000_?????_?????_000_?????_0110011") -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // ADD
    BitPat("b0100000_?????_?????_000_?????_0110011") -> D(ALUOp.SUB,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SUB
    BitPat("b0000000_?????_?????_001_?????_0110011") -> D(ALUOp.SLL,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SLL
    BitPat("b0000000_?????_?????_010_?????_0110011") -> D(ALUOp.SLT,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SLT
    BitPat("b0000000_?????_?????_011_?????_0110011") -> D(ALUOp.SLTU, Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SLTU
    BitPat("b0000000_?????_?????_100_?????_0110011") -> D(ALUOp.XOR,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // XOR
    BitPat("b0000000_?????_?????_101_?????_0110011") -> D(ALUOp.SRL,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SRL
    BitPat("b0100000_?????_?????_101_?????_0110011") -> D(ALUOp.SRA,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SRA
    BitPat("b0000000_?????_?????_110_?????_0110011") -> D(ALUOp.OR,   Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // OR
    BitPat("b0000000_?????_?????_111_?????_0110011") -> D(ALUOp.AND,  Src1Sel.REG, Src2Sel.REG, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.R_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // AND
  )

  // RV32I 基础整数运算 - I-Type (opcode: 0010011)
  private val rv32i_itype = Seq(
    BitPat("b?????????????_000_?????_0010011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // ADDI
    BitPat("b?????????????_010_?????_0010011")        -> D(ALUOp.SLT,  Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SLTI
    BitPat("b?????????????_011_?????_0010011")        -> D(ALUOp.SLTU, Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SLTIU
    BitPat("b?????????????_100_?????_0010011")        -> D(ALUOp.XOR,  Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // XORI
    BitPat("b?????????????_110_?????_0010011")        -> D(ALUOp.OR,   Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // ORI
    BitPat("b?????????????_111_?????_0010011")        -> D(ALUOp.AND,  Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // ANDI
    BitPat("b0000000_?????_001_?????_0010011")        -> D(ALUOp.SLL,  Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SLLI
    BitPat("b0000000_?????_101_?????_0010011")        -> D(ALUOp.SRL,  Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SRLI
    BitPat("b0100000_?????_101_?????_0010011")        -> D(ALUOp.SRA,  Src1Sel.REG, Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // SRAI
  )

  // RV32I 基础整数运算 - U-Type (opcode: 0110111, 0010111)
  private val rv32i_utype = Seq(
    BitPat("b?????????????_?????_0110111")            -> D(ALUOp.ADD,  Src1Sel.ZERO,Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.U_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // LUI
    BitPat("b?????????????_?????_0010111")            -> D(ALUOp.ADD,  Src1Sel.PC,  Src2Sel.IMM, LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.U_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // AUIPC
  )

  // RV32I 访存指令 - Load (opcode: 0000011)
  private val rv32i_load = Seq(
    BitPat("b?????????????_000_?????_0000011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.LOAD,  LSUWidth.BYTE, LSUsign.SIGNED,   BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // LB
    BitPat("b?????????????_001_?????_0000011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.LOAD,  LSUWidth.HALF, LSUsign.SIGNED,   BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // LH
    BitPat("b?????????????_010_?????_0000011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.LOAD,  LSUWidth.WORD, LSUsign.SIGNED,   BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // LW
    BitPat("b?????????????_100_?????_0000011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.LOAD,  LSUWidth.BYTE, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // LBU
    BitPat("b?????????????_101_?????_0000011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.LOAD,  LSUWidth.HALF, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.NONE, ZicsrOp.NOP), // LHU
  )

  // RV32I 访存指令 - Store (opcode: 0100011)
  private val rv32i_store = Seq(
    BitPat("b?????????????_000_?????_0100011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.STORE, LSUWidth.BYTE, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.S_TYPE, SpecialInstr.STORE,ZicsrOp.NOP), // SB
    BitPat("b?????????????_001_?????_0100011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.STORE, LSUWidth.HALF, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.S_TYPE, SpecialInstr.STORE,ZicsrOp.NOP), // SH
    BitPat("b?????????????_010_?????_0100011")        -> D(ALUOp.ADD,  Src1Sel.REG, Src2Sel.IMM, LSUOp.STORE, LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.S_TYPE, SpecialInstr.STORE,ZicsrOp.NOP), // SW
  )

  // RV32I 分支与跳转 - B-Type (opcode: 1100011)
  private val rv32i_btype = Seq(
    BitPat("b?????????????_000_?????_1100011")        -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BEQ,  ImmType.B_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // BEQ
    BitPat("b?????????????_001_?????_1100011")        -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BNE,  ImmType.B_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // BNE
    BitPat("b?????????????_100_?????_1100011")        -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BLT,  ImmType.B_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // BLT
    BitPat("b?????????????_101_?????_1100011")        -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BGE,  ImmType.B_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // BGE
    BitPat("b?????????????_110_?????_1100011")        -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BLTU, ImmType.B_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // BLTU
    BitPat("b?????????????_111_?????_1100011")        -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.BGEU, ImmType.B_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // BGEU
  )

  // RV32I 分支与跳转 - J-Type (opcode: 1101111)
  private val rv32i_jtype = Seq(
    BitPat("b?????????????_?????_1101111")            -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.JAL, ImmType.J_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // JAL
  )

  // RV32I 分支与跳转 - JALR (opcode: 1100111)
  private val rv32i_jalr = Seq(
    BitPat("b?????????????_000_?????_1100111")        -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.JALR, ImmType.I_TYPE, SpecialInstr.BRANCH,ZicsrOp.NOP), // JALR
  )

  // RV32I FENCE 指令 (opcode: 0001111)
  private val rv32i_fence = Seq(
    BitPat("b0000????????_00000_000_00000_0001111")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.FENCE, ZicsrOp.NOP), // FENCE
  )

  // Zifencei 扩展指令 (opcode: 0001111)
  private val rv32i_zifencei = Seq(
    BitPat("b000000000000_00000_001_00000_0001111")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.FENCEI,ZicsrOp.NOP), // FENCE.I
  )

  // Zicsr 扩展指令 (opcode: 1110011)
  private val rv32i_zicsr = Seq(
    BitPat("b?????????????_001_?????_1110011")        -> D(ALUOp.NOP,  Src1Sel.REG, Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.CSR,   ZicsrOp.RW),  // CSRRW
    BitPat("b?????????????_010_?????_1110011")        -> D(ALUOp.NOP,  Src1Sel.REG, Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.CSR,   ZicsrOp.RS),  // CSRRS
    BitPat("b?????????????_011_?????_1110011")        -> D(ALUOp.NOP,  Src1Sel.REG, Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.I_TYPE, SpecialInstr.CSR,   ZicsrOp.RC),  // CSRRC
    BitPat("b?????????????_101_?????_1110011")        -> D(ALUOp.NOP,  Src1Sel.IMM, Src2Sel.FOUR,LSUOp.NOP, LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP, ImmType.Z_TYPE, SpecialInstr.CSR,   ZicsrOp.RW),  // CSRRWI
    BitPat("b?????????????_110_?????_1110011")        -> D(ALUOp.NOP,  Src1Sel.IMM, Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.CSR,   ZicsrOp.RS),  // CSRRSI
    BitPat("b?????????????_111_?????_1110011")        -> D(ALUOp.NOP,  Src1Sel.IMM, Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.CSR,   ZicsrOp.RC),  // CSRRCI
  )

  // 特权指令 (opcode: 1110011)
  private val rv32i_priv = Seq(
    BitPat("b000000000000_00000_000_00000_1110011")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.ECALL, ZicsrOp.NOP), // ECALL
    BitPat("b000000000001_00000_000_00000_1110011")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.EBREAK,ZicsrOp.NOP), // EBREAK
    BitPat("b001100000010_00000_000_00000_1110011")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.MRET,  ZicsrOp.NOP), // MRET
    BitPat("b000100000010_00000_000_00000_1110011")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.SRET,  ZicsrOp.NOP), // SRET
    BitPat("b000100000101_00000_000_00000_1110011")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.WFI,   ZicsrOp.NOP), // WFI
    BitPat("b0001001?????_?????_000_00000_1110011")   -> D(ALUOp.NOP,  Src1Sel.ZERO,Src2Sel.FOUR,LSUOp.NOP,   LSUWidth.WORD, LSUsign.UNSIGNED, BRUOp.NOP,  ImmType.Z_TYPE, SpecialInstr.SFENCE,ZicsrOp.NOP), // SFENCE.VMA
  )

  // 全量表合并
  val table = rv32i_rtype ++ rv32i_itype ++ rv32i_utype ++
              rv32i_load ++ rv32i_store ++ rv32i_fence ++
              rv32i_btype ++ rv32i_jtype ++ rv32i_jalr ++
              rv32i_zifencei ++ rv32i_zicsr ++ rv32i_priv
}
```

##### 2.2.2 利用查找表完成解码

在 `Decoder.scala` 模块内，使用 `MuxLookup` 根据指令的 bitpattern 和 `Instructions.scala` 中定义查找表进行指令解码，并将结果分配到各个控制信号：

```scala
// 将 table 转换为 MuxCase 接受的格式
  val decodeEntry = MuxCase(Instructions.defaultEntry, Instructions.table.map { 
  case (pat, entry) => (io.inst === pat) -> entry 
  })

  // 将查找到的解码信息分配到各个控制信号
  aluOp        := decodeEntry.aluOp
  op1Src       := decodeEntry.op1Src
  op2Src       := decodeEntry.op2Src
  lsuOp        := decodeEntry.lsuOp
  lsuWidth     := decodeEntry.lsuWidth
  lsuSign      := decodeEntry.lsuSign
  bruOp        := decodeEntry.bruOp
  immType      := decodeEntry.immType
  specialInstr := decodeEntry.specialInstr
  zicsrOp      := decodeEntry.zicsrOp

  // 特殊指令标志
  isCsr    := specialInstr === SpecialInstr.CSR
  isEcall  := specialInstr === SpecialInstr.ECALL
  isEbreak := specialInstr === SpecialInstr.EBREAK
```

##### 2.2.2 立即数生成逻辑

```scala
  // I-Type: imm[11:0] 符号扩展
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))

  // S-Type: imm[11:5] | imm[4:0] 符号扩展
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))

  // B-Type: imm[12|10:5] | imm[4:1|11] 符号扩展
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))

  // U-Type: imm[31:12] 左移12位，低位补0
  val immU = Cat(inst(31, 12), 0.U(12.W))

  // J-Type: imm[20|10:1|11|19:12] 符号扩展
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  // Z-Type: 零扩展立即数（用于 Zicsr 指令的 uimm[4:0]）
  // 从指令的 rs1 字段提取 5 位立即数，零扩展至 32 位
  val immZ = Cat(0.U(27.W), rs1)

  // 立即数选择
  imm := MuxLookup(immType, 0.U(32.W), Seq(
    ImmType.I_TYPE -> immI,
    ImmType.S_TYPE -> immS,
    ImmType.B_TYPE -> immB,
    ImmType.U_TYPE -> immU,
    ImmType.J_TYPE -> immJ,
    ImmType.Z_TYPE -> immZ
  ))
```

#### 2.3 异常检测

```scala
  // 默认无异常
  decodeException.valid := false.B
  decodeException.cause := 0.U(4.W)
  decodeException.tval := 0.U(32.W)

  // 非法指令异常（基于完整 32 位 bitpattern）
  when (!decodeEntry.isLegal) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
    decodeException.tval := pc
  }

  // CSR 权限检测
  // CSR 地址空间的 [9:8] 位指示访问该 CSR 所需的最低特权级（00=U, 01=S, 11=M）
  // 若尝试越权访问 CSR，将触发 Illegal Instruction Exception
  val csrAddr = funct12
  val csrRequiredPriv = csrAddr(9, 8) // 提取 CSR 所需特权级
  val isCsrAccessLegal = MuxLookup(csrRequiredPriv, false.B, Seq(
    "b00".U -> (privMode === PrivMode.U || privMode === PrivMode.S || privMode === PrivMode.M),
    "b01".U -> (privMode === PrivMode.S || privMode === PrivMode.M),
    "b11".U -> (privMode === PrivMode.M)
  ))

  // CSR 指令越权访问异常
  when (isCsr && !isCsrAccessLegal) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
    decodeException.tval := pc
  }
  // ECALL 异常
  .elsewhen (isEcall) {
    decodeException.valid := true.B
    decodeException.cause := MuxLookup(privMode, ExceptionCause.ECALL_FROM_M_MODE, Seq(
      PrivMode.U -> ExceptionCause.ECALL_FROM_U_MODE,
      PrivMode.S -> ExceptionCause.ECALL_FROM_S_MODE,
      PrivMode.M -> ExceptionCause.ECALL_FROM_M_MODE
    ))
    decodeException.tval := pc
  }
  // EBREAK 异常
  .elsewhen (isEbreak) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.BREAKPOINT
    decodeException.tval := pc
  }

  // 合并输入异常和译码异常
  hasException := io.in.valid && (inputException.valid || decodeException.valid)

  val finalException = Wire(new Exception)
  when (inputException.valid) {
    finalException := inputException
  } .otherwise {
    finalException := decodeException
  }
```

#### 2.4 流水线控制

```scala
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
  needStall := robFull || rsFull || ratFull || csrDecoding || csrPending
  // Flush 条件
  needFlush := io.branchFlush || io.globalFlush
  // 向 Fetcher 发送 Stall 信号
  io.ifStall := needStall && !needFlush
  // 向 Icache 发送 ready 信号
  io.in.ready := !(needStall || needFlush)
  // 生成该阶段 valid 信号
  decoderValid := io.in.valid && !needStall && !needFlush
```

#### 2.5 输出数据打包

```scala
  // 构建重命名请求
  io.renameReq.valid := decoderValid
  io.renameReq.bits.rs1 := rs1
  io.renameReq.bits.rs2 := rs2
  io.renameReq.bits.rd := rd
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
  io.dispatch.bits.privMode := privMode
  io.dispatch.bits.prediction := prediction
  io.dispatch.bits.exception := finalException
```

## 第三部分：完整的 Chisel 代码示例

```scala
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
    val robInit = Decoupled(new ROBInitControl)
    
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
  
  // ========== 元数据透传 ==========
  val pc = io.in.bits.instMetadata.pc
  val insEpoch = io.in.bits.instMetadata.insEpoch
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

  // 指令类型标志
  val specialInstr = Wire(SpecialInstr())
  val isCsr = Wire(Bool())
  val isEcall = Wire(Bool())
  val isEbreak = Wire(Bool())

  // 立即数
  val imm = Wire(UInt(32.W))

  // 异常信号
  val decodeException = Wire(new Exception)
  val hasException = Wire(Bool())

  // Stall 信号
  val robFull = Wire(Bool())
  val rsFull = Wire(Bool())
  val ratFull = Wire(Bool())
  val needStall = Wire(Bool())

  val needFlush = Wire(Bool())
  val decoderValid = Wire(Bool())

  // ========== 指令解码查找表 ==========
  // 定义指令解码表项
  case class DecodeEntry(
    aluOp: ALUOp.Type,
    op1Src: Src1Sel.Type,
    op2Src: Src2Sel.Type,
    lsuOp: LSUOp.Type,
    lsuWidth: LSUWidth.Type,
    lsuSign: LSUsign.Type,
    bruOp: BRUOp.Type,
    immType: ImmType.Type,
    specialInstr: SpecialInstr.Type,
    zicsrOp: ZicsrOp.Type,
    isLegal: Bool  // 指令是否合法（基于完整 32 位 bitpattern）
  )

  // 定义默认解码表项（非法指令）
  val defaultEntry = DecodeEntry(
    aluOp = ALUOp.NOP,
    op1Src = Src1Sel.ZERO,
    op2Src = Src2Sel.FOUR,
    lsuOp = LSUOp.NOP,
    lsuWidth = LSUWidth.WORD,
    lsuSign = LSUsign.UNSIGNED,
    bruOp = BRUOp.NOP,
    immType = ImmType.R_TYPE,
    specialInstr = SpecialInstr.NONE,
    zicsrOp = ZicsrOp.NOP,
    isLegal = false.B  // 默认为非法指令
  )

  // 使用查找表进行指令解码
  val decodeEntry = MuxLookup(inst, Instructions.defaultEntry, Instructions.table)

  // 从解码表项中提取各个信号
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

  isCsr := (specialInstr === SpecialInstr.CSR)
  isEcall := (specialInstr === SpecialInstr.ECALL)
  isEbreak := (specialInstr === SpecialInstr.EBREAK)
  
  // ========== 立即数生成 ==========
  // I-Type: imm[11:0] 符号扩展
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  // S-Type: imm[11:5] | imm[4:0] 符号扩展
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  // B-Type: imm[12|10:5] | imm[4:1|11] 符号扩展
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  // U-Type: imm[31:12] 左移12位，低位补0
  val immU = Cat(inst(31, 12), 0.U(12.W))
  // J-Type: imm[20|10:1|11|19:12] 符号扩展
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  // Z-Type: 零扩展立即数（用于 Zicsr 指令的 uimm[4:0]）
  val immZ = Cat(0.U(27.W), rs1)

  val imm = MuxLookup(immType, 0.U(32.W), Seq(
    ImmType.I_TYPE -> immI,
    ImmType.S_TYPE -> immS,
    ImmType.B_TYPE -> immB,
    ImmType.U_TYPE -> immU,
    ImmType.J_TYPE -> immJ,
    ImmType.R_TYPE -> 0.U(32.W),
    ImmType.Z_TYPE -> immZ
  ))
  
  // ========== 异常检测 ==========
  val decodeException = Wire(new Exception)
  decodeException.valid := false.B
  decodeException.cause := 0.U(4.W)
  decodeException.tval := 0.U(32.W)

  // 非法指令异常（基于完整 32 位 bitpattern）
  when (!decodeEntry.isLegal) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
    decodeException.tval := pc
  }
  .elsewhen (isCsr) {
    // CSR 权限检测
    // CSR 地址空间的 [9:8] 位指示访问该 CSR 所需的最低特权级（00=U, 01=S, 11=M）
    // 若尝试越权访问 CSR，将触发 Illegal Instruction Exception
    val csrAddr = funct12
    val csrRequiredPriv = csrAddr(9, 8) // 提取 CSR 所需特权级
    val isCsrAccessLegal = MuxLookup(csrRequiredPriv, false.B, Seq(
      "b00".U -> (privMode === PrivMode.U || privMode === PrivMode.S || privMode === PrivMode.M),
      "b01".U -> (privMode === PrivMode.S || privMode === PrivMode.M),
      "b11".U -> (privMode === PrivMode.M)
    ))

    // CSR 指令越权访问异常
    when (!isCsrAccessLegal) {
      decodeException.valid := true.B
      decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
      decodeException.tval := pc
    }
  }
  .elsewhen (isEcall) {
    decodeException.valid := true.B
    decodeException.cause := MuxLookup(privMode, ExceptionCause.ECALL_FROM_M_MODE, Seq(
      PrivMode.U -> ExceptionCause.ECALL_FROM_U_MODE,
      PrivMode.S -> ExceptionCause.ECALL_FROM_S_MODE,
      PrivMode.M -> ExceptionCause.ECALL_FROM_M_MODE
    ))
    decodeException.tval := pc
  }
  .elsewhen (isEbreak) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.BREAKPOINT
    decodeException.tval := pc
  }

  // 合并输入异常和译码异常
  hasException := io.in.valid && (inputException.valid || decodeException.valid)

  val finalException = Wire(new Exception)
  when (inputException.valid) {
    finalException := inputException
  } .otherwise {
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
  needStall := robFull || rsFull || ratFull || csrDecoding || csrPending
  // Flush 条件
  needFlush := io.branchFlush || io.globalFlush
  // 向 Fetcher 发送 Stall 信号
  io.ifStall := needStall && !needFlush
  // 向 Icache 发送 ready 信号
  io.in.ready := !(needStall || needFlush)
  // 生成该阶段 valid 信号
  decoderValid := io.in.valid && !needStall && !needFlush

  // ========== 输出数据打包 ==========
  // 构建重命名请求
  io.renameReq.valid := decoderValid
  io.renameReq.bits.rs1 := rs1
  io.renameReq.bits.rs2 := rs2
  io.renameReq.bits.rd := rd
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
  io.dispatch.bits.privMode := privMode
  io.dispatch.bits.prediction := prediction
  io.dispatch.bits.exception := finalException
}
```

## 参考文档

- [`Top.md`](../../Implement/Top.md) - Decoder 职责定义
- [`Protocol.md`](../../Implement/Protocol.md) - 接口定义
- [`Instruction.md`](../../Mechanism/Instruction.md) - 指令集定义
- [`Chisel.md`](../../Mechanism/Chisel.md) - Chisel 语法和设计模式