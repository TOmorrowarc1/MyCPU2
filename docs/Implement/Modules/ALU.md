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

ALU 从 CPU 中的多个源接收输入：

#### 2.1.1 来自保留站（RS）

| 信号 | 类型 | 描述 |
|------|------|------|
| `ALUOp` | `ALUOp` | 指定要执行的 ALU 操作的操作码 |
| `Op1Sel` | `Src1Sel` | 第一个操作数源的选择器 |
| `Op2Sel` | `Src2Sel` | 第二个操作数源的选择器 |
| `Imm` | `UInt(32.W)` | I 类型和 U 类型指令的立即数 |
| `PC` | `UInt(32.W)` | 用于 PC 相对操作的程序计数器值 |
| `RobID` | `UInt(5.W)` | 用于结果跟踪的重排序缓冲区条目标识符 |

#### 2.1.2 来自物理寄存器文件（PRF）

| 信号 | 类型 | 描述 |
|------|------|------|
| `ReadData1` | `UInt(32.W)` | 从物理寄存器文件读取的第一个操作数 |
| `ReadData2` | `UInt(32.W)` | 从物理寄存器文件读取的第二个操作数 |

#### 2.1.3 控制信号

| 信号 | 源 | 描述 |
|------|-----|------|
| `GlobalFlush` | ROB | 全局冲刷信号，用于丢弃所有进行中的操作 |
| `BranchFlush` | BRU | 分支预测错误冲刷信号 |

### 2.2 输出接口

ALU 将结果输出到公共数据总线（CDB）：

| 信号 | 类型 | 描述 |
|------|------|------|
| `RobID` | `UInt(5.W)` | 重排序缓冲区条目标识符 |
| `Result` | `UInt(32.W)` | 计算结果 |
| `Exception` | `Exception` | 异常信息（如果有） |

### 2.3 Chisel 接口定义

```scala
// RS -> ALU 请求
class AluReq extends Bundle with CPUConfig {
  val aluOp = ALUOp()
  val meta = new IssueMetaPacket
  val data = new IssueDataPacket
}

// 完整 ALU 数据包（控制 + 操作数）
class AluPacket extends Bundle with CPUConfig {
  val aluReq = new AluReq
  val prfData = new PrfReadData
}

// PRF -> ALU 数据
class PrfReadData extends Bundle with CPUConfig {
  val rdata1 = DataW
  val rdata2 = DataW
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
| `REG` | `ReadData1` | 来自 PRF 寄存器 rs1 的第一个操作数 |
| `PC` | `PC` | 程序计数器值 |
| `ZERO` | 0 | 零常数 |

#### 3.2.2 第二个操作数选择（`Src2Sel`）

| 选择器 | 源 | 描述 |
|--------|-----|------|
| `REG` | `ReadData2` | 来自 PRF 寄存器 rs2 的第二个操作数 |
| `IMM` | `Imm` | 来自指令的立即数 |
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

## 4. 实现细节

### 4.1 数据流

ALU 按以下数据流运行：

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│     RS      │────▶│     ALU     │────▶│     CDB     │
│  (Dispatch) │     │  (Execute)  │     │  (Broadcast)│
└─────────────┘     └──────┬──────┘     └─────────────┘
                            │
                     ┌──────┴──────┐
                     │     PRF     │
                     │  (Operands) │
                     └─────────────┘
```

### 4.2 操作数选择逻辑

ALU 使用多路复用器根据 `Op1Sel` 和 `Op2Sel` 控制信号选择操作数：

```scala
// 操作数 1 选择
val op1 = MuxLookup(io.aluReq.data.src1Sel, 0.U, Seq(
  Src1Sel.REG  -> io.prfData.rdata1,
  Src1Sel.PC   -> io.aluReq.data.pc,
  Src1Sel.ZERO -> 0.U
))

// 操作数 2 选择
val op2 = MuxLookup(io.aluReq.data.src2Sel, 0.U, Seq(
  Src2Sel.REG  -> io.prfData.rdata2,
  Src2Sel.IMM  -> io.aluReq.data.imm,
  Src2Sel.FOUR -> 4.U
))
```

### 4.3 操作执行

ALU 根据 `ALUOp` 控制信号执行操作：

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

### 4.4 异常处理

ALU 处理在指令解码期间检测到的异常：

- **异常传播**：如果指令携带来自前端的异常（例如非法指令），ALU 不执行计算，而是直接将异常信息传播到结果包中
- **异常包**：异常信息包括有效标志、异常原因和陷阱值（tval）

```scala
// 异常处理逻辑
val finalResult = Mux(io.aluReq.meta.exception.valid, 0.U, result)
val finalException = io.aluReq.meta.exception
```

### 4.5 结果寄存和广播

ALU 在向 CDB 广播之前将结果存储在结果寄存器中：

1. **结果寄存**：将计算结果、RobID 和异常信息存储在内部寄存器中
2. **CDB 仲裁**：等待 CDB 可用（在仲裁中优先级最低）
3. **结果广播**：当总线准备好时，将结果包发送到 CDB

```scala
// 结果寄存器
val resultReg = Reg(UInt(32.W))
val robIdReg = Reg(UInt(5.W))
val exceptionReg = Reg(new Exception)

// CDB 输出
io.CDB.valid := busy
io.CDB.bits.robId := robIdReg
io.CDB.bits.data := resultReg
io.CDB.bits.exception := exceptionReg
```

## 5. 时序和延迟考虑

### 5.1 流水线阶段

ALU 作为单周期执行单元运行：

```
周期 N：   发射   - RS 将指令分派到 ALU
周期 N+1：执行   - ALU 执行计算
周期 N+2：写回   - 通过 CDB 广播结果（如果仲裁获胜）
```

### 5.2 延迟

- **计算延迟**：1 个周期（所有操作在单个周期内完成）
- **写回延迟**：可变，取决于 CDB 仲裁优先级
  - 最小值：1 个周期（如果 CDB 立即可用）
  - 最大值：如果高优先级 EU 正在使用总线，可能需要多个周期

### 5.3 吞吐量

- **发射速率**：每周期 1 条指令（受 RS 分派限制）
- **完成速率**：每周期最多 1 条指令（受 CDB 带宽限制）

### 5.4 关键路径

ALU 中的关键路径包括：

1. 操作数多路复用（Op1Sel、Op2Sel）
2. ALU 操作执行（加法器、移位器、比较器）
3. 结果寄存

最长的路径通常是算术运算的加法器/减法器。

## 6. 与 CPU 模块的集成

### 6.1 保留站（RS）

ALU 从 ALU 保留站（ALURS）接收指令：

- **分派**：当所有操作数都可用时，RS 将就绪指令分派到 ALU
- **发射**：发射阶段从 PRF 读取操作数，并将完整数据包发送到 ALU
- **依赖跟踪**：RS 跟踪数据依赖，并将 CDB 结果转发给等待指令

### 6.2 物理寄存器文件（PRF）

ALU 从 PRF 读取操作数：

- **读端口**：两个读端口提供操作数值（`rdata1`、`rdata2`）
- **读时序**：操作数在发射周期读取并传递给 ALU
- **写路径**：结果通过 CDB 写回，而不是由 ALU 直接写入

### 6.3 公共数据总线（CDB）

ALU 通过 CDB 广播结果：

- **仲裁优先级**：最低优先级（ZICSRU > LSU > BRU > ALU）
- **广播格式**：`{RobID, Result, Exception}`
- **转发**：RS 和 PRF 监听 CDB 以便将结果转发给依赖指令

### 6.4 重排序缓冲区（ROB）

ALU 通过 CDB 与 ROB 通信：

- **完成**：CDB 将完成状态广播给 ROB
- **异常处理**：ROB 在提交期间处理来自 ALU 的异常
- **退休**：ROB 按程序顺序退休 ALU 指令

### 6.5 分支解决单元（BRU）

ALU 响应分支冲刷信号：

- **分支预测错误**：BRU 发送 `BranchFlush` 信号以丢弃进行中的 ALU 操作
- **快照管理**：ALU 操作标记有分支掩码，用于选择性冲刷

## 7. 设计决策

### 7.1 单周期执行

**决策**：所有 ALU 操作在单个周期内完成。

**理由**：
- 简化流水线设计
- 提供可预测的延迟
- 对于现代 FPGA/ASIC 实现中的 32 位整数操作足够

### 7.2 操作数多路复用

**决策**：使用多路复用器进行操作数选择，而不是使用单独的输入端口。

**理由**：
- 减少端口数量和路由复杂性
- 支持灵活的操作数源（寄存器、PC、立即数、常数）
- 与 RISC-V 指令格式要求一致

### 7.3 异常传播

**决策**：ALU 传播来自前端的异常，而不进行额外的异常检测。

**理由**：
- RV32I 中的整数操作不生成算术异常（无溢出检测）
- 异常主要在指令解码期间检测（非法指令）
- 简化 ALU 逻辑并减少关键路径

### 7.4 CDB 仲裁优先级

**决策**：ALU 在 CDB 仲裁中具有最低优先级。

**理由**：
- ALU 操作通常对性能不关键
- LSU 和 ZICSRU 操作具有更高的延迟和对性能的影响
- BRU 操作对分支解决和流水线流程至关重要

## 8. 配置参数

ALU 使用来自 `CPUConfig` 的以下配置参数：

| 参数 | 值 | 描述 |
|------|-----|------|
| `XLEN` | 32 | 寄存器和数据路径的位宽 |
| `DataWidth` | 32 | ALU 操作数和结果的位宽 |
| `RobIdWidth` | 5 | ROB 条目标识符的位宽（支持 32 个条目） |
| `PhyRegIdWidth` | 7 | 物理寄存器标识符的位宽（支持 128 个寄存器） |

## 9. 验证考虑

### 9.1 功能验证

ALU 的关键验证点：

1. **操作正确性**：验证每个 ALU 操作产生正确结果
2. **操作数选择**：验证所有 `Src1Sel` 和 `Src2Sel` 组合的正确操作数选择
3. **异常处理**：验证异常被正确传播
4. **边界情况**：测试边界条件（溢出、移位量、比较）

### 9.2 时序验证

关键时序验证点：

1. **关键路径**：验证单周期操作满足时序约束
2. **建立/保持时间**：验证所有接口边界的时序
3. **时钟域**：验证与 CPU 时钟的同步操作

### 9.3 集成验证

关键集成验证点：

1. **RS 接口**：验证正确的指令分派和操作数传递
2. **CDB 接口**：验证正确的结果广播和仲裁
3. **冲刷处理**：验证对全局和分支冲刷信号的正确响应

## 10. 参考

- RISC-V 规范：第一卷：用户级 ISA，版本 20191213
- Tomasulo 算法："An Efficient Algorithm for Exploiting Multiple Arithmetic Units" (1967)
- Chisel 文档：https://www.chisel-lang.org/
- 项目顶层设计：[`Top.md`](../Top.md)
- 协议定义：[`Protocol.scala`](../../src/main/scala/cpu/Protocol.scala)
