# Decoder 模块设计文档

## 概述

Decoder 模块是 Tomasulo 架构中的关键组件，负责将指令流解析为微操作（MicroOp），进行寄存器重命名请求，检测异常，并控制流水线的流动。

## 职责

根据 [`Top.md`](../../Implement/Top.md:20-33) 中的定义，Decoder 的主要职责包括：

1. **指令解析**：将指令解析到微操作
2. **寄存器重命名请求**：向 RAT 请求寄存器重命名
3. **快照请求**：为分支指令请求创建快照
4. **异常检测**：检测指令相关的异常
5. **流水线控制**：生成 stall 信号控制取指

## 输入输出

### 输入
- 来自 Icache 的 [`IDecodePacket`](../../Implement/Protocol.md:139-142)：包含 `Instruction`, `pc`, `PrivMode`, `InsEpoch`, `prediction`, `exception`
- 来自 ROB 的：`FreeRobID`, `GlobalFlush`, `CSRDone`
- 来自 BRU 的：`BranchFlush`

### 输出
- 向 RAT 发送：[`RenameReq`](../../Implement/Protocol.md:178-183) `{Data, isBranch}`
- 向 ROB 发送：[`ROBInitControlPacket`](../../Implement/Protocol.md:145-155) `{robId, exception, prediction}`
- 向 RS 发送：[`DispatchPacket`](../../Implement/Protocol.md:168-175) `{MinOps, Exceptions, robId, prediction}`
- 向 Fetcher 发送：`IFStall`

## 第一部分：指令解析结果（使能信号部分）

### 1. RV32I 基础整数指令

#### 1.1 算术与逻辑运算（寄存器-寄存器）

**Opcode**: `0110011` (R-Type)

| 指令 | BitPattern (31-0) | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **ADD** | `0000000_rs2_rs1_000_rd_0110011` | ADD | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **SUB** | `0100000_rs2_rs1_000_rd_0110011` | SUB | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **SLL** | `0000000_rs2_rs1_001_rd_0110011` | SLL | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **SLT** | `0000000_rs2_rs1_010_rd_0110011` | SLT | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **SLTU**| `0000000_rs2_rs1_011_rd_0110011` | SLTU | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **XOR** | `0000000_rs2_rs1_100_rd_0110011` | XOR | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **SRL** | `0000000_rs2_rs1_101_rd_0110011` | SRL | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **SRA** | `0100000_rs2_rs1_101_rd_0110011` | SRA | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **OR** | `0000000_rs2_rs1_110_rd_0110011` | OR | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |
| **AND** | `0000000_rs2_rs1_111_rd_0110011` | AND | REG | REG | NOP | WORD | UNSIGNED | NOP | R_TYPE | NONE |

#### 1.2 算术与逻辑运算（寄存器-立即数）

**Opcode**: `0010011` (I-Type)

| 指令 | BitPattern | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **ADDI** | `imm[11:0]_rs1_000_rd_0010011` | ADD | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **SLTI** | `imm[11:0]_rs1_010_rd_0010011` | SLT | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **SLTIU**| `imm[11:0]_rs1_011_rd_0010011` | SLTU | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **XORI** | `imm[11:0]_rs1_100_rd_0010011` | XOR | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **ORI** | `imm[11:0]_rs1_110_rd_0010011` | OR | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **ANDI** | `imm[11:0]_rs1_111_rd_0010011` | AND | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **SLLI** | `0000000_shamt_rs1_001_rd_0010011` | SLL | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **SRLI** | `0000000_shamt_rs1_101_rd_0010011` | SRL | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |
| **SRAI** | `0100000_shamt_rs1_101_rd_0010011` | SRA | REG | IMM | NOP | WORD | UNSIGNED | NOP | I_TYPE | NONE |

#### 1.3 大立即数与 PC 相关指令

| 指令 | BitPattern | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **LUI** | `imm[31:12]_rd_0110111` | ADD | ZERO | IMM | NOP | WORD | UNSIGNED | NOP | U_TYPE | NONE |
| **AUIPC**| `imm[31:12]_rd_0010111` | ADD | PC | IMM | NOP | WORD | UNSIGNED | NOP | U_TYPE | NONE |

#### 1.4 控制流指令（分支与跳转）

**Opcode**: `1100011` (B-Type), `1101111` (J-Type), `1100111` (I-Type)

| 指令 | BitPattern | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **JAL** | `imm[20\|10:1\|11\|19:12]_rd_1101111` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | JAL | J_TYPE | BRANCH |
| **JALR** | `imm[11:0]_rs1_000_rd_1100111` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | JALR | I_TYPE | BRANCH |
| **BEQ** | `imm..._rs2_rs1_000_..._1100011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | BEQ | B_TYPE | BRANCH |
| **BNE** | `imm..._rs2_rs1_001_..._1100011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | BNE | B_TYPE | BRANCH |
| **BLT** | `imm..._rs2_rs1_100_..._1100011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | BLT | B_TYPE | BRANCH |
| **BGE** | `imm..._rs2_rs1_101_..._1100011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | BGE | B_TYPE | BRANCH |
| **BLTU** | `imm..._rs2_rs1_110_..._1100011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | BLTU | B_TYPE | BRANCH |
| **BGEU** | `imm..._rs2_rs1_111_..._1100011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | BGEU | B_TYPE | BRANCH |

#### 1.5 访存指令（Load/Store）

**Opcode**: `0000011` (Load), `0100011` (Store)

| 指令 | BitPattern | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **LB** | `imm_rs1_000_rd_0000011` | ADD | REG | IMM | LOAD | BYTE | SIGNED | NOP | I_TYPE | NONE |
| **LH** | `imm_rs1_001_rd_0000011` | ADD | REG | IMM | LOAD | HALF | SIGNED | NOP | I_TYPE | NONE |
| **LW** | `imm_rs1_010_rd_0000011` | ADD | REG | IMM | LOAD | WORD | SIGNED | NOP | I_TYPE | NONE |
| **LBU** | `imm_rs1_100_rd_0000011` | ADD | REG | IMM | LOAD | BYTE | UNSIGNED | NOP | I_TYPE | NONE |
| **LHU** | `imm_rs1_101_rd_0000011` | ADD | REG | IMM | LOAD | HALF | UNSIGNED | NOP | I_TYPE | NONE |
| **SB** | `imm[11:5]_rs2_rs1_000_imm[4:0]_0100011` | ADD | REG | IMM | STORE | BYTE | UNSIGNED | NOP | S_TYPE | STORE |
| **SH** | `imm[11:5]_rs2_rs1_001_imm[4:0]_0100011` | ADD | REG | IMM | STORE | HALF | UNSIGNED | NOP | S_TYPE | STORE |
| **SW** | `imm[11:5]_rs2_rs1_010_imm[4:0]_0100011` | ADD | REG | IMM | STORE | WORD | UNSIGNED | NOP | S_TYPE | STORE |
| **FENCE** | `0000_pred_succ_00000_000_00000_0001111` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | FENCE |

### 2. Zifencei 扩展指令

| 指令 | BitPattern | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **FENCE.I** | `000000000000_00000_001_00000_0001111` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | FENCEI |

### 3. Zicsr 扩展指令（CSR 访问）

**Opcode**: `1110011` (System)

| 指令 | BitPattern | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **CSRRW** | `csr_rs1_001_rd_1110011` | NOP | REG | FOUR | NOP | WORD | UNSIGNED | NOP | I_TYPE | CSR |
| **CSRRS** | `csr_rs1_010_rd_1110011` | NOP | REG | FOUR | NOP | WORD | UNSIGNED | NOP | I_TYPE | CSR |
| **CSRRC** | `csr_rs1_011_rd_1110011` | NOP | REG | FOUR | NOP | WORD | UNSIGNED | NOP | I_TYPE | CSR |
| **CSRRWI**| `csr_uimm_101_rd_1110011` | NOP | IMM | FOUR | NOP | WORD | UNSIGNED | NOP | I_TYPE | CSR |
| **CSRRSI**| `csr_uimm_110_rd_1110011` | NOP | IMM | FOUR | NOP | WORD | UNSIGNED | NOP | I_TYPE | CSR |
| **CSRRCI**| `csr_uimm_111_rd_1110011` | NOP | IMM | FOUR | NOP | WORD | UNSIGNED | NOP | I_TYPE | CSR |

### 4. 特权指令（Privileged Instructions）

**Opcode**: `1110011` (System)

| 指令 | BitPattern | aluOp | op1Src | op2Src | lsuOp | lsuWidth | lsuSign | bruOp | immType | specialInstr |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **ECALL** | `000000000000_00000_000_00000_1110011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | ECALL |
| **EBREAK**| `000000000001_00000_000_00000_1110011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | EBREAK |
| **MRET** | `001100000010_00000_000_00000_1110011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | MRET |
| **SRET** | `000100000010_00000_000_00000_1110011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | SRET |
| **WFI** | `000100000101_00000_000_00000_1110011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | WFI |
| **SFENCE.VMA** | `0001001_rs2_rs1_000_00000_1110011` | NOP | ZERO | FOUR | NOP | WORD | UNSIGNED | NOP | Z_TYPE | SFENCE |

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
    val csrDone = Input(Bool())
    
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
}
```

### 2. 内部信号定义

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
  
  // 指令类型标志
  val specialInstr = Wire(SpecialInstr())
  val isBranch = Wire(Bool())
  val isStore = Wire(Bool())
  val isCsr = Wire(Bool())
  val isMret = Wire(Bool())
  val isSret = Wire(Bool())
  val isSFENCE = Wire(Bool())
  val isFENCEI = Wire(Bool())
  val isEcall = Wire(Bool())
  val isEbreak = Wire(Bool())
  val isWfi = Wire(Bool())
  val isPrivileged = Wire(Bool())
  val isLoad = Wire(Bool())
  
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
```

### 3. 指令解码逻辑

#### 3.1 使用 MuxLookup 进行指令分类

```scala
  // ALU 操作解码
  aluOp := MuxLookup(opcode, ALU_NOP, Seq(
    "b0110011".U -> MuxLookup(funct3, ALU_NOP, Seq(
      "b000".U -> Mux(funct7(5), ALU_SUB, ALU_ADD),
      "b001".U -> ALU_SLL,
      "b010".U -> ALU_SLT,
      "b011".U -> ALU_SLTU,
      "b100".U -> ALU_XOR,
      "b101".U -> Mux(funct7(5), ALU_SRA, ALU_SRL),
      "b110".U -> ALU_OR,
      "b111".U -> ALU_AND
    )),
    "b0010011".U -> MuxLookup(funct3, ALU_NOP, Seq(
      "b000".U -> ALU_ADD,
      "b010".U -> ALU_SLT,
      "b011".U -> ALU_SLTU,
      "b100".U -> ALU_XOR,
      "b110".U -> ALU_OR,
      "b111".U -> ALU_AND,
      "b001".U -> ALU_SLL,
      "b101".U -> Mux(funct7(5), ALU_SRA, ALU_SRL)
    )),
    "b0110111".U -> ALU_ADD,  // LUI
    "b0010111".U -> ALU_ADD   // AUIPC
  ))
  
  // 操作数源选择
  op1Src := MuxLookup(opcode, Src1Sel.ZERO, Seq(
    "b0110111".U -> Src1Sel.ZERO,  // LUI
    "b0010111".U -> Src1Sel.PC,    // AUIPC
    "b1100011".U -> Src1Sel.REG,   // Branch
    "b1101111".U -> Src1Sel.PC,    // JAL
    "b1100111".U -> Src1Sel.REG,   // JALR
    "b0000011".U -> Src1Sel.REG,   // Load
    "b0100011".U -> Src1Sel.REG    // Store
  ))
  
  op2Src := MuxLookup(opcode, Src2Sel.FOUR, Seq(
    "b0010011".U -> Src2Sel.IMM,   // I-type ALU
    "b0110111".U -> Src2Sel.IMM,   // LUI
    "b0010111".U -> Src2Sel.IMM,   // AUIPC
    "b0000011".U -> Src2Sel.IMM,   // Load
    "b0100011".U -> Src2Sel.IMM,   // Store
    "b1101111".U -> Src2Sel.FOUR   // JAL (PC+4)
  ))
  
  // LSU 操作解码
  lsuOp := MuxLookup(opcode, LSU_NOP, Seq(
    "b0000011".U -> LSU_LOAD,
    "b0100011".U -> LSU_STORE
  ))
  
  lsuWidth := MuxLookup(opcode, LSU.WORD, Seq(
    "b0000011".U -> MuxLookup(funct3, LSU.WORD, Seq(
      "b000".U -> LSU.BYTE,
      "b001".U -> LSU.HALF,
      "b010".U -> LSU.WORD,
      "b100".U -> LSU.BYTE,
      "b101".U -> LSU.HALF
    )),
    "b0100011".U -> MuxLookup(funct3, LSU.WORD, Seq(
      "b000".U -> LSU.BYTE,
      "b001".U -> LSU.HALF,
      "b010".U -> LSU.WORD
    ))
  ))
  
  lsuSign := MuxLookup(opcode, LSUsign.UNSIGNED, Seq(
    "b0000011".U -> MuxLookup(funct3, LSUsign.SIGNED, Seq(
      "b100".U -> LSUsign.UNSIGNED,
      "b101".U -> LSUsign.UNSIGNED
    ))
  ))
  
  // BRU 操作解码
  bruOp := MuxLookup(opcode, BRU_NOP, Seq(
    "b1100011".U -> MuxLookup(funct3, BRU_NOP, Seq(
      "b000".U -> BRU_BEQ,
      "b001".U -> BRU_BNE,
      "b100".U -> BRU_BLT,
      "b101".U -> BRU_BGE,
      "b110".U -> BRU_BLTU,
      "b111".U -> BRU_BGEU
    )),
    "b1101111".U -> BRU_JAL,
    "b1100111".U -> BRU_JALR
  ))
  
  // 立即数类型解码
  immType := MuxLookup(opcode, ImmType.R_TYPE, Seq(
    "b0010011".U -> ImmType.I_TYPE,
    "b0000011".U -> ImmType.I_TYPE,
    "b0100011".U -> ImmType.S_TYPE,
    "b1100011".U -> ImmType.B_TYPE,
    "b1101111".U -> ImmType.J_TYPE,
    "b0110111".U -> ImmType.U_TYPE,
    "b0010111".U -> ImmType.U_TYPE,
    "b1100111".U -> ImmType.I_TYPE,
    "b1110011".U -> ImmType.Z_TYPE
  ))
```

#### 3.2 指令类型标志解码

```scala
  // 特殊指令解码
  specialInstr := MuxLookup(opcode, SpecialInstr.NONE, Seq(
    "b1100011".U -> SpecialInstr.BRANCH,  // Branch
    "b1101111".U -> SpecialInstr.BRANCH,  // JAL
    "b1100111".U -> SpecialInstr.BRANCH,  // JALR
    "b0100011".U -> SpecialInstr.STORE,   // Store
    "b0001111".U -> MuxLookup(funct3, SpecialInstr.NONE, Seq(
      "b000".U -> SpecialInstr.FENCE,
      "b001".U -> SpecialInstr.FENCEI
    )),
    "b1110011".U -> MuxLookup(funct12, SpecialInstr.NONE, Seq(
      "b000000000000".U -> SpecialInstr.ECALL,
      "b000000000001".U -> SpecialInstr.EBREAK,
      "b001100000010".U -> SpecialInstr.MRET,
      "b000100000010".U -> SpecialInstr.SRET,
      "b000100000101".U -> SpecialInstr.WFI,
      "b0001001".U(12.W) -> SpecialInstr.SFENCE
    ))
  ))
  
  // CSR 指令检测（用于特权判断）
  isCsr := (opcode === "b1110011".U) && (funct3(2) === true.B) &&
          (funct12 =/= "b001100000010".U) && (funct12 =/= "b000100000010".U) &&
          (funct12 =/= "b000100000101".U) && (funct12 =/= "b000000000000".U) &&
          (funct12 =/= "b000000000001".U) && (funct12 =/= "b0001001".U(12.W))
  
  // 从 specialInstr 推导布尔标志
  isBranch := (specialInstr === SpecialInstr.BRANCH)
  isStore := (specialInstr === SpecialInstr.STORE)
  isMret := (specialInstr === SpecialInstr.MRET)
  isSret := (specialInstr === SpecialInstr.SRET)
  isSFENCE := (specialInstr === SpecialInstr.SFENCE)
  isFENCEI := (specialInstr === SpecialInstr.FENCEI)
  isEcall := (specialInstr === SpecialInstr.ECALL)
  isEbreak := (specialInstr === SpecialInstr.EBREAK)
  isWfi := (specialInstr === SpecialInstr.WFI)
  
  // Load 指令
  isLoad := (opcode === "b0000011".U)
  
  // 特权指令判断
  isPrivileged := isCsr || isMret || isSret || isSFENCE || isEcall || isEbreak || isWfi
```

### 4. 立即数生成逻辑

```scala
  // I-Type: imm[11:0]
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  
  // S-Type: imm[11:5] | imm[4:0]
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  
  // B-Type: imm[12|10:5] | imm[4:1|11]
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  
  // U-Type: imm[31:12]
  val immU = Cat(inst(31, 12), 0.U(12.W))
  
  // J-Type: imm[20|10:1|11|19:12]
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  
  // Z-Type: 0
  val immZ = 0.U(32.W)
  
  // 立即数选择
  imm := MuxLookup(immType, immZ, Seq(
    ImmType.I_TYPE -> immI,
    ImmType.S_TYPE -> immS,
    ImmType.B_TYPE -> immB,
    ImmType.U_TYPE -> immU,
    ImmType.J_TYPE -> immJ,
    ImmType.R_TYPE -> immZ,
    ImmType.Z_TYPE -> immZ
  ))
```

### 5. 寄存器重命名请求逻辑

```scala
  // 构建重命名请求
  io.renameReq.valid := io.in.valid && !hasException && !needStall && !globalFlush
  io.renameReq.bits.rs1 := rs1
  io.renameReq.bits.rs2 := rs2
  io.renameReq.bits.rd := rd
  io.renameReq.bits.isBranch := isBranch
```

### 6. 异常检测逻辑

```scala
  // 默认无异常
  decodeException.valid := false.B
  decodeException.cause := 0.U(4.W)
  decodeException.tval := 0.U(32.W)
  
  // 检测非法指令
  val isLegalInst = MuxLookup(opcode, false.B, Seq(
    "b0110011".U -> true.B,  // R-type
    "b0010011".U -> true.B,  // I-type ALU
    "b0000011".U -> true.B,  // Load
    "b0100011".U -> true.B,  // Store
    "b1100011".U -> true.B,  // Branch
    "b1101111".U -> true.B,  // JAL
    "b1100111".U -> true.B,  // JALR
    "b0110111".U -> true.B,  // LUI
    "b0010111".U -> true.B,  // AUIPC
    "b0001111".U -> true.B,  // FENCE
    "b1110011".U -> true.B   // System
  ))
  
  // 非法指令异常
  when (!isLegalInst) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
    decodeException.tval := pc
  }
  
  // ECALL 异常
  when (isEcall) {
    decodeException.valid := true.B
    decodeException.cause := MuxLookup(privMode, ExceptionCause.ECALL_FROM_M_MODE, Seq(
      PrivMode.U -> ExceptionCause.ECALL_FROM_U_MODE,
      PrivMode.S -> ExceptionCause.ECALL_FROM_S_MODE,
      PrivMode.M -> ExceptionCause.ECALL_FROM_M_MODE
    ))
    decodeException.tval := pc
  }
  
  // EBREAK 异常
  when (isEbreak) {
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

### 7. 流水线控制逻辑（Stall 信号生成）

```scala
  // 检测下游模块是否已满
  robFull := !io.robInit.ready
  rsFull := !io.dispatch.ready
  ratFull := !io.renameReq.ready
  
  // CSR 和特权指令需要串行化执行
  val needSerialize = isPrivileged || isFENCEI
  
  // Stall 条件
  needStall := robFull || rsFull || ratFull || (needSerialize && !csrDone)
  
  // 向 Fetcher 发送 Stall 信号
  io.ifStall := needStall
  
  // 向 Icache 发送 ready 信号
  io.in.ready := !needStall && !globalFlush
```

### 8. 输出数据打包

```scala
  // ROB 初始化控制包
  io.robInit.valid := io.in.valid && !hasException && !needStall && !globalFlush
  io.robInit.bits.pc := pc
  io.robInit.bits.prediction := prediction
  io.robInit.bits.exception := finalException
  io.robInit.bits.isStore := (specialInstr === SpecialInstr.STORE)
  io.robInit.bits.isCsr := isCsr
  io.robInit.bits.isMret := (specialInstr === SpecialInstr.MRET)
  io.robInit.bits.isSret := (specialInstr === SpecialInstr.SRET)
  io.robInit.bits.isSFENCE := (specialInstr === SpecialInstr.SFENCE)
  io.robInit.bits.isFENCEI := (specialInstr === SpecialInstr.FENCEI)

  // 分派包
  io.dispatch.valid := io.in.valid && !hasException && !needStall && !globalFlush
  io.dispatch.bits.robId := io.freeRobID
  io.dispatch.bits.microOp.aluOp := aluOp
  io.dispatch.bits.microOp.op1Src := op1Src
  io.dispatch.bits.microOp.op2Src := op2Src
  io.dispatch.bits.microOp.lsuOp := lsuOp
  io.dispatch.bits.microOp.lsuWidth := lsuWidth
  io.dispatch.bits.microOp.lsuSign := lsuSign
  io.dispatch.bits.microOp.bruOp := bruOp
  io.dispatch.bits.pc := pc
  io.dispatch.bits.imm := imm
  io.dispatch.bits.prediction := prediction
  io.dispatch.bits.exception := finalException
```

### 9. 完整的 Chisel 代码示例

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
    val csrDone = Input(Bool())
    
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
  
  // ========== ALU 操作解码 ==========
  aluOp := MuxLookup(opcode, ALU_NOP, Seq(
    "b0110011".U -> MuxLookup(funct3, ALU_NOP, Seq(
      "b000".U -> Mux(funct7(5), ALU_SUB, ALU_ADD),
      "b001".U -> ALU_SLL,
      "b010".U -> ALU_SLT,
      "b011".U -> ALU_SLTU,
      "b100".U -> ALU_XOR,
      "b101".U -> Mux(funct7(5), ALU_SRA, ALU_SRL),
      "b110".U -> ALU_OR,
      "b111".U -> ALU_AND
    )),
    "b0010011".U -> MuxLookup(funct3, ALU_NOP, Seq(
      "b000".U -> ALU_ADD,
      "b010".U -> ALU_SLT,
      "b011".U -> ALU_SLTU,
      "b100".U -> ALU_XOR,
      "b110".U -> ALU_OR,
      "b111".U -> ALU_AND,
      "b001".U -> ALU_SLL,
      "b101".U -> Mux(funct7(5), ALU_SRA, ALU_SRL)
    )),
    "b0110111".U -> ALU_ADD,  // LUI
    "b0010111".U -> ALU_ADD   // AUIPC
  ))
  
  // ========== 操作数源选择 ==========
  op1Src := MuxLookup(opcode, Src1Sel.ZERO, Seq(
    "b0110111".U -> Src1Sel.ZERO,  // LUI
    "b0010111".U -> Src1Sel.PC,    // AUIPC
    "b1100011".U -> Src1Sel.REG,   // Branch
    "b1101111".U -> Src1Sel.PC,    // JAL
    "b1100111".U -> Src1Sel.REG,   // JALR
    "b0000011".U -> Src1Sel.REG,   // Load
    "b0100011".U -> Src1Sel.REG    // Store
  ))
  
  op2Src := MuxLookup(opcode, Src2Sel.FOUR, Seq(
    "b0010011".U -> Src2Sel.IMM,   // I-type ALU
    "b0110111".U -> Src2Sel.IMM,   // LUI
    "b0010111".U -> Src2Sel.IMM,   // AUIPC
    "b0000011".U -> Src2Sel.IMM,   // Load
    "b0100011".U -> Src2Sel.IMM,   // Store
    "b1101111".U -> Src2Sel.FOUR   // JAL (PC+4)
  ))
  
  // ========== LSU 操作解码 ==========
  lsuOp := MuxLookup(opcode, LSU_NOP, Seq(
    "b0000011".U -> LSU_LOAD,
    "b0100011".U -> LSU_STORE
  ))
  
  lsuWidth := MuxLookup(opcode, LSU.WORD, Seq(
    "b0000011".U -> MuxLookup(funct3, LSU.WORD, Seq(
      "b000".U -> LSU.BYTE,
      "b001".U -> LSU.HALF,
      "b010".U -> LSU.WORD,
      "b100".U -> LSU.BYTE,
      "b101".U -> LSU.HALF
    )),
    "b0100011".U -> MuxLookup(funct3, LSU.WORD, Seq(
      "b000".U -> LSU.BYTE,
      "b001".U -> LSU.HALF,
      "b010".U -> LSU.WORD
    ))
  ))
  
  lsuSign := MuxLookup(opcode, LSUsign.UNSIGNED, Seq(
    "b0000011".U -> MuxLookup(funct3, LSUsign.SIGNED, Seq(
      "b100".U -> LSUsign.UNSIGNED,
      "b101".U -> LSUsign.UNSIGNED
    ))
  ))
  
  // ========== BRU 操作解码 ==========
  bruOp := MuxLookup(opcode, BRU_NOP, Seq(
    "b1100011".U -> MuxLookup(funct3, BRU_NOP, Seq(
      "b000".U -> BRU_BEQ,
      "b001".U -> BRU_BNE,
      "b100".U -> BRU_BLT,
      "b101".U -> BRU_BGE,
      "b110".U -> BRU_BLTU,
      "b111".U -> BRU_BGEU
    )),
    "b1101111".U -> BRU_JAL,
    "b1100111".U -> BRU_JALR
  ))
  
  // ========== 立即数类型解码 ==========
  immType := MuxLookup(opcode, ImmType.R_TYPE, Seq(
    "b0010011".U -> ImmType.I_TYPE,
    "b0000011".U -> ImmType.I_TYPE,
    "b0100011".U -> ImmType.S_TYPE,
    "b1100011".U -> ImmType.B_TYPE,
    "b1101111".U -> ImmType.J_TYPE,
    "b0110111".U -> ImmType.U_TYPE,
    "b0010111".U -> ImmType.U_TYPE,
    "b1100111".U -> ImmType.I_TYPE,
    "b1110011".U -> ImmType.Z_TYPE
  ))
  
  // ========== 特殊指令解码 ==========
  val specialInstr = MuxLookup(opcode, SpecialInstr.NONE, Seq(
    "b1100011".U -> SpecialInstr.BRANCH,  // Branch
    "b1101111".U -> SpecialInstr.BRANCH,  // JAL
    "b1100111".U -> SpecialInstr.BRANCH,  // JALR
    "b0100011".U -> SpecialInstr.STORE,   // Store
    "b0001111".U -> MuxLookup(funct3, SpecialInstr.NONE, Seq(
      "b000".U -> SpecialInstr.FENCE,
      "b001".U -> SpecialInstr.FENCEI
    )),
    "b1110011".U -> MuxLookup(funct12, SpecialInstr.NONE, Seq(
      "b000000000000".U -> SpecialInstr.ECALL,
      "b000000000001".U -> SpecialInstr.EBREAK,
      "b001100000010".U -> SpecialInstr.MRET,
      "b000100000010".U -> SpecialInstr.SRET,
      "b000100000101".U -> SpecialInstr.WFI,
      "b0001001".U(12.W) -> SpecialInstr.SFENCE
    ))
  ))
  
  // CSR 指令检测（用于特权判断）
  val isCsr = (opcode === "b1110011".U) && (funct3(2) === true.B) &&
              (funct12 =/= "b001100000010".U) && (funct12 =/= "b000100000010".U) &&
              (funct12 =/= "b000100000101".U) && (funct12 =/= "b000000000000".U) &&
              (funct12 =/= "b000000000001".U) && (funct12 =/= "b0001001".U(12.W))
  
  // 从 specialInstr 推导布尔标志
  val isBranch = (specialInstr === SpecialInstr.BRANCH)
  val isStore = (specialInstr === SpecialInstr.STORE)
  val isMret = (specialInstr === SpecialInstr.MRET)
  val isSret = (specialInstr === SpecialInstr.SRET)
  val isSFENCE = (specialInstr === SpecialInstr.SFENCE)
  val isFENCEI = (specialInstr === SpecialInstr.FENCEI)
  val isEcall = (specialInstr === SpecialInstr.ECALL)
  val isEbreak = (specialInstr === SpecialInstr.EBREAK)
  val isWfi = (specialInstr === SpecialInstr.WFI)
  
  // Load 指令
  val isLoad = (opcode === "b0000011".U)
  
  // 特权指令判断
  val isPrivileged = isCsr || isMret || isSret || isSFENCE || isEcall || isEbreak || isWfi
  
  // ========== 立即数生成 ==========
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  val immZ = 0.U(32.W)
  
  val imm = MuxLookup(immType, immZ, Seq(
    ImmType.I_TYPE -> immI,
    ImmType.S_TYPE -> immS,
    ImmType.B_TYPE -> immB,
    ImmType.U_TYPE -> immU,
    ImmType.J_TYPE -> immJ,
    ImmType.R_TYPE -> immZ,
    ImmType.Z_TYPE -> immZ
  ))
  
  // ========== 异常检测 ==========
  val decodeException = Wire(new Exception)
  decodeException.valid := false.B
  decodeException.cause := 0.U(4.W)
  decodeException.tval := 0.U(32.W)
  
  val isLegalInst = MuxLookup(opcode, false.B, Seq(
    "b0110011".U -> true.B,
    "b0010011".U -> true.B,
    "b0000011".U -> true.B,
    "b0100011".U -> true.B,
    "b1100011".U -> true.B,
    "b1101111".U -> true.B,
    "b1100111".U -> true.B,
    "b0110111".U -> true.B,
    "b0010111".U -> true.B,
    "b0001111".U -> true.B,
    "b1110011".U -> true.B
  ))
  
  when (!isLegalInst) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.ILLEGAL_INSTRUCTION
    decodeException.tval := pc
  }
  
  when (isEcall) {
    decodeException.valid := true.B
    decodeException.cause := MuxLookup(privMode, ExceptionCause.ECALL_FROM_M_MODE, Seq(
      PrivMode.U -> ExceptionCause.ECALL_FROM_U_MODE,
      PrivMode.S -> ExceptionCause.ECALL_FROM_S_MODE,
      PrivMode.M -> ExceptionCause.ECALL_FROM_M_MODE
    ))
    decodeException.tval := pc
  }
  
  when (isEbreak) {
    decodeException.valid := true.B
    decodeException.cause := ExceptionCause.BREAKPOINT
    decodeException.tval := pc
  }
  
  val hasException = io.in.valid && (inputException.valid || decodeException.valid)
  
  val finalException = Wire(new Exception)
  when (inputException.valid) {
    finalException := inputException
  } .otherwise {
    finalException := decodeException
  }
  
  // ========== 流水线控制 ==========
  val robFull = !io.robInit.ready
  val rsFull = !io.dispatch.ready
  val ratFull = !io.renameReq.ready
  val needSerialize = isPrivileged || isFENCEI
  val needStall = robFull || rsFull || ratFull || (needSerialize && !csrDone)
  
  io.ifStall := needStall
  io.in.ready := !needStall && !io.globalFlush
  
  // ========== 输出数据打包 ==========
  io.renameReq.valid := io.in.valid && !hasException && !needStall && !io.globalFlush
  io.renameReq.bits.rs1 := rs1
  io.renameReq.bits.rs2 := rs2
  io.renameReq.bits.rd := rd
  io.renameReq.bits.isBranch := (specialInstr === SpecialInstr.BRANCH)

  io.robInit.valid := io.in.valid && !hasException && !needStall && !io.globalFlush
  io.robInit.bits.pc := pc
  io.robInit.bits.prediction := prediction
  io.robInit.bits.exception := finalException
  io.robInit.bits.isStore := (specialInstr === SpecialInstr.STORE)
  io.robInit.bits.isCsr := isCsr
  io.robInit.bits.isMret := (specialInstr === SpecialInstr.MRET)
  io.robInit.bits.isSret := (specialInstr === SpecialInstr.SRET)
  io.robInit.bits.isSFENCE := (specialInstr === SpecialInstr.SFENCE)
  io.robInit.bits.isFENCEI := (specialInstr === SpecialInstr.FENCEI)

  io.dispatch.valid := io.in.valid && !hasException && !needStall && !io.globalFlush
  io.dispatch.bits.robId := io.freeRobID
  io.dispatch.bits.microOp.aluOp := aluOp
  io.dispatch.bits.microOp.op1Src := op1Src
  io.dispatch.bits.microOp.op2Src := op2Src
  io.dispatch.bits.microOp.lsuOp := lsuOp
  io.dispatch.bits.microOp.lsuWidth := lsuWidth
  io.dispatch.bits.microOp.lsuSign := lsuSign
  io.dispatch.bits.microOp.bruOp := bruOp
  io.dispatch.bits.pc := pc
  io.dispatch.bits.imm := imm
  io.dispatch.bits.prediction := prediction
  io.dispatch.bits.exception := finalException
}
```

---

## 设计要点总结

### 1. 指令译码策略
- 使用 [`MuxLookup`](../../Mechanism/Chisel.md:357-372) 进行高效的指令分类
- 根据 opcode 和 funct 字段逐步细化译码
- 为每条指令生成完整的控制信号

### 2. 流水线控制
- 通过检测下游模块的 ready 信号实现反压
- CSR 和特权指令需要串行化执行，等待 CSR 完成
- Global Flush 和 Branch Flush 会立即暂停译码

### 3. 异常处理
- 检测非法指令、ECALL、EBREAK 等异常
- 将输入异常和译码异常合并，优先处理输入异常
- 异常信息透传到 ROB 和 RS

### 4. 寄存器重命名
- 为所有指令生成重命名请求
- 分支指令需要创建快照（IsBranch = true）
- x0 寄存器不需要重命名

### 5. 立即数生成
- 支持所有 RISC-V 立即数格式（I, S, B, U, J, Z）
- 使用符号扩展确保立即数的正确性

### 6. Tomasulo 架构集成
- Decoder 作为前端和后端的桥梁
- 将指令分解为微操作，分发给不同的执行单元
- 维护指令的元数据（PC、预测、异常等）用于乱序执行和恢复

---

## 参考文档

- [`Top.md`](../../Implement/Top.md) - Decoder 职责定义
- [`Protocol.md`](../../Implement/Protocol.md) - 接口定义
- [`Instruction.md`](../../Mechanism/Instruction.md) - 指令集定义
- [`Chisel.md`](../../Mechanism/Chisel.md) - Chisel 语法和设计模式
