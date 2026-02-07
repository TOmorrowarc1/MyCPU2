# ALU (算术逻辑单元) 设计文档

## 1. 概述

ALU（算术逻辑单元）处理所有整数算术和逻辑运算。作为后端执行流水线的一部分，ALU 从保留站接收指令，并通过公共数据总线（CDB）广播结果。

### 1.1 目的和作用

ALU 在 CPU 中发挥以下关键作用：

- **信息整合**：将来自 RS 的指令信息与来自 PRF 的操作数值结合。
- **整数计算**：执行所有 RV32I 基础整数算术和逻辑运算。
- **结果广播**：将计算结果与执行期间异常发送到 CDB。

ALU 是 Tomasulo 架构中的四个执行单元之一，与分支解决单元（BRU）、加载存储单元（LSU）和 Zicsr 指令单元（ZICSRU）并列。其在 CDB 仲裁中具有最低优先级（ZICSRU > LSU > BRU > ALU）。

## 2. ALU 接口

### 2.1 输入接口

ALU 从 CPU 中的多个源接收输入，使用 [`AluDrivenPacket`](../../src/main/scala/cpu/Protocol.scala:321-324) 结构体：

#### 2.1.1 来自保留站（RS）

| 信号 | 类型 | 描述 |
|------|------|------|
| `aluOp` | `ALUOp` | 指定要执行的 ALU 操作的操作码|
| `src1Sel` | `Src1Sel` | 第一个操作数源的选择器 |
| `src2Sel` | `Src2Sel` | 第二个操作数源的选择器 |
| `imm` | `DataW` | I 类型和 U 类型指令的立即数 |
| `pc` | `AddrW` | 用于 PC 相对操作的程序计数器值 |
| `robId` | `RobTag` | 用于结果跟踪的重排序缓冲区条目标识符 |
| `phyRd` | `PhyTag` | 目标物理寄存器标识符 |
| `exception` | `Exception` | 异常信息 |

#### 2.1.2 来自物理寄存器文件（PRF）

| 信号 | 类型 | 描述 |
|------|------|------|
| `rdata1` | `DataW` | 从物理寄存器文件读取的第一个操作数 |
| `rdata2` | `DataW` | 从物理寄存器文件读取的第二个操作数 |

#### 2.1.3 控制信号

| 信号 | 源 | 描述 |
|------|-----|------|
| `GlobalFlush` | ROB | 全局冲刷信号，用于丢弃所有进行中的操作 |
| `BranchFlush` | BRU | 分支预测错误冲刷信号 |

### 2.2 输出接口

ALU 将结果输出到公共数据总线（CDB），使用 [`CDBMessage`](../../src/main/scala/cpu/Protocol.scala:340-346) 结构体：

| 信号 | 类型 | 描述 |
|------|------|------|
| `robId` | `RobTag` | 重排序缓冲区条目标识符 |
| `phyRd` | `PhyTag` | 目标物理寄存器标识符 |
| `data` | `DataW` | 计算结果 |
| `hasSideEffect` | `Bits(1.W)` | 副作用标志（ALU 固定为 0） |
| `exception` | `Exception` | 异常信息（如果有） |

### 2.3 Chisel 接口定义

ALU 模块的接口定义在 [`Protocol.scala`](../../src/main/scala/cpu/Protocol.scala) 中，使用以下结构体：

```scala
// RS -> ALU 请求（在 Protocol.scala 第 305-309 行）
class AluReq extends Bundle with CPUConfig {
  val aluOp = ALUOp()
  val meta = new IssueMetaPacket
  val data = new IssueDataPacket
}

// Issue 元数据包（在 Protocol.scala 第 291-295 行）
class IssueMetaPacket extends Bundle with CPUConfig {
  val robId = RobTag
  val phyRd = PhyTag
  val exception = new Exception
}

// Issue 数据包（在 Protocol.scala 第 297-302 行）
class IssueDataPacket extends Bundle with CPUConfig {
  val src1Sel = Src1Sel()
  val src2Sel = Src2Sel()
  val imm = DataW
  val pc = AddrW
}

// PRF -> ALU 数据（在 Protocol.scala 第 285-288 行）
class PrfReadData extends Bundle with CPUConfig {
  val rdata1 = DataW
  val rdata2 = DataW
}

// ALU 驱动数据包（在 Protocol.scala 第 321-324 行）
class AluDrivenPacket extends Bundle with CPUConfig {
  val aluReq = new AluReq
  val prfData = new PrfReadData
}

// CDB 消息（在 Protocol.scala 第 340-346 行）
class CDBMessage extends Bundle with CPUConfig {
  val robId = RobTag
  val phyRd = PhyTag
  val data = DataW
  val hasSideEffect = Bits(1.W)
  val exception = new Exception
}
```

## 3. 支持的操作

### 3.1 操作类型

ALU 支持 `ALUOp` 枚举中定义的以下操作：

| 操作 | 描述 | RISC-V 指令 |
|------|------|-------------|
| `ADD` | 加法 | ADD, ADDI, LUI, AUIPC |
| `SUB` | 减法 | SUB |
| `AND` | 按位与 | AND, ANDI |
| `OR` | 按位或 | OR, ORI |
| `XOR` | 按位异或 | XOR, XORI |
| `SLL` | 逻辑左移 | SLL, SLLI |
| `SRL` | 逻辑右移 | SRL, SRLI |
| `SRA` | 算术右移 | SRA, SRAI |
| `SLT` | 小于设置（有符号） | SLT, SLTI |
| `SLTU` | 小于设置（无符号） | SLTU, SLTIU |
| `NOP` | 无操作 | 非 ALU 指令的默认值 |

### 3.2 操作数选择

ALU 通过多路复用器支持多个操作数源：

#### 3.2.1 第一个操作数选择（`Src1Sel`）

| 选择器 | 源 | 描述 |
|--------|-----|------|
| `REG` | `rdata1` | 来自 PRF 寄存器 rs1 的第一个操作数 |
| `PC` | `pc` | 程序计数器值 |
| `ZERO` | 0 | 零常数 |

#### 3.2.2 第二个操作数选择（`Src2Sel`）

| 选择器 | 源 | 描述 |
|--------|-----|------|
| `REG` | `rdata2` | 来自 PRF 寄存器 rs2 的第二个操作数 |
| `IMM` | `imm` | 来自指令的立即数 |
| `FOUR` | 4 | 常数值 4（用于 PC+4 操作） |

### 3.3 指令映射

下表显示了 RISC-V 指令如何映射到 ALU 操作：

| 指令 | 格式 | ALUOp | Op1Sel | Op2Sel |
|------|------|-------|--------|--------|
| ADD | R 类型 | ADD | REG | REG |
| ADDI | I 类型 | ADD | REG | IMM |
| SUB | R 类型 | SUB | REG | REG |
| AND | R 类型 | AND | REG | REG |
| ANDI | I 类型 | AND | REG | IMM |
| OR | R 类型 | OR | REG | REG |
| ORI | I 类型 | OR | REG | IMM |
| XOR | R 类型 | XOR | REG | REG |
| XORI | I 类型 | XOR | REG | IMM |
| SLL | R 类型 | SLL | REG | REG |
| SLLI | I 类型 | SLL | REG | IMM |
| SRL | R 类型 | SRL | REG | REG |
| SRLI | I 类型 | SRL | REG | IMM |
| SRA | R 类型 | SRA | REG | REG |
| SRAI | I 类型 | SRA | REG | IMM |
| SLT | R 类型 | SLT | REG | REG |
| SLTI | I 类型 | SLT | REG | IMM |
| SLTU | R 类型 | SLTU | REG | REG |
| SLTIU | I 类型 | SLTU | REG | IMM |
| LUI | U 类型 | ADD | ZERO | IMM |
| AUIPC | U 类型 | ADD | PC | IMM |

## 4. 内部逻辑

### 4.1 操作数选择逻辑

ALU 使用多路复用器根据 `src1Sel` 和 `src2Sel` 控制信号选择操作数：

```scala
// 操作数 1 选择（来自 PrfReadData.rdata1 和 IssueDataPacket）
val op1 = MuxLookup(io.aluReq.data.src1Sel, 0.U, Seq(
  Src1Sel.REG  -> io.prfData.rdata1,
  Src1Sel.PC   -> io.aluReq.data.pc,
  Src1Sel.ZERO -> 0.U
))

// 操作数 2 选择（来自 PrfReadData.rdata2 和 IssueDataPacket）
val op2 = MuxLookup(io.aluReq.data.src2Sel, 0.U, Seq(
  Src2Sel.REG  -> io.prfData.rdata2,
  Src2Sel.IMM  -> io.aluReq.data.imm,
  Src2Sel.FOUR -> 4.U
))
```

### 4.3 操作执行

ALU 根据 `aluOp` 控制信号执行操作：

```scala
val result = MuxLookup(io.aluReq.aluOp, 0.U, Seq(
  ALUOp.ADD  -> (op1 + op2),
  ALUOp.SUB  -> (op1 - op2),
  ALUOp.AND  -> (op1 & op2),
  ALUOp.OR   -> (op1 | op2),
  ALUOp.XOR  -> (op1 ^ op2),
  ALUOp.SLL  -> (op1 << op2(4,0)),      // 移位量：低 5 位
  ALUOp.SRL  -> (op1 >> op2(4,0)),      // 逻辑右移
  ALUOp.SRA  -> (op1.asSInt >> op2(4,0)).asUInt,  // 算术右移
  ALUOp.SLT  -> (op1.asSInt < op2.asSInt),        // 有符号比较
  ALUOp.SLTU -> (op1 < op2),                      // 无符号比较
  ALUOp.NOP  -> 0.U
))
```

### 4.4 结果寄存和广播

ALU 在向 CDB 广播之前将结果存储在结果寄存器中：

1. **结果寄存**：将计算结果、robId、phyRd、hasSideEffect 和异常信息存储在内部寄存器中
2. **CDB 仲裁**：等待 CDB 可用（在仲裁中优先级最低）
3. **结果广播**：当总线准备好时，将结果包发送到 CDB

```scala
// 结果寄存器
val resultReg = Reg(UInt(32.W))
val robIdReg = Reg(UInt(5.W))
val phyRdReg = Reg(UInt(7.W))
val hasSideEffectReg = Reg(Bits(1.W))

// CDB 输出（使用 CDBMessage 结构体）
io.CDB.valid := busy
io.CDB.bits.robId := robIdReg
io.CDB.bits.phyRd := phyRdReg
io.CDB.bits.data := resultReg
io.CDB.bits.hasSideEffect := hasSideEffectReg
io.CDB.bits.exception := exceptionReg
```

## 5. 验证考虑

### 5.1 功能验证

ALU 的关键验证点：

1. **操作正确性**：验证每个 ALU 操作产生正确结果
2. **操作数选择**：验证所有 `src1Sel` 和 `src2Sel` 组合的正确操作数选择
3. **边界情况**：测试边界条件（溢出、移位量、比较）

### 5.2 集成验证

关键集成验证点：

1. **RS 接口**：验证正确的指令分派和操作数传递
2. **CDB 接口**：验证正确的结果广播和仲裁，busy 位向上游的反压。
3. **冲刷处理**：验证对全局和分支冲刷信号的正确响应
