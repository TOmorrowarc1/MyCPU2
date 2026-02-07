# BRU (分支解决单元)

## 1. 概述

分支解决单元（BRU）是后端流水线中的关键执行单元，负责：
- 执行分支条件评估
- 计算跳转目标地址
- 处理分支预测错误恢复
- 与其他流水线组件协调控制流变化

BRU 在执行阶段运行，从保留站（RS）接收分支指令，评估实际的分支结果，并与在取指阶段做出的预测进行比较。基于此比较，BRU 要么确认正确的预测，要么为预测错误触发流水线冲刷。

## 2. 接口规范

### 2.1 输入端口

#### 2.1.1 来自保留站（RS）

| 信号名称 | 类型 | 描述 |
|---------|------|------|
| `BRUOp` | Enum | 分支操作类型（BEQ、BNE、BLT、BGE、BLTU、BGEU、JAL、JALR） |
| `Imm` | UInt(32.W) | 用于偏移计算的立即数值 |
| `PC` | UInt(32.W) | 分支指令的程序计数器 |
| `RobID` | UInt(log2Ceil(ROB_SIZE).W) | 重排序缓冲区条目标识符 |
| `Prediction.Taken` | Bool | 预测的分支是否跳转 |
| `Prediction.Target` | UInt(32.W) | 预测的目标 PC |

#### 2.1.2 来自物理寄存器文件（PRF）

| 信号名称 | 类型 | 描述 |
|---------|------|------|
| `ReadData1` | UInt(32.W) | 源寄存器 1 的值（rs1） |
| `ReadData2` | UInt(32.W) | 源寄存器 2 的值（rs2） |

#### 2.1.3 控制信号

| 信号名称 | 类型 | 描述 |
|---------|------|------|
| `globalFlush` | Bool | 来自 ROB/BRU 的流水线冲刷信号 |

### 2.2 输出端口

#### 2.2.1 全局广播总线（到 Fetcher、RAT、ROB、RS）

| 信号名称 | 类型 | 描述 |
|---------|------|------|
| `branchFlush` | Bool | 指示分支预测错误需要流水线冲刷 |
| `branchPC` | UInt(32.W) | 用于重定向取指的正确目标 PC |
| `branchOH` | UInt(4.W) | 用于 RAT 恢复的分支快照标识符 |

#### 2.2.2 公共数据总线（CDB）接口

| 信号名称 | 类型 | 描述 |
|---------|------|------|
| `cdb` | CDBMessage | 主线通用信息格式 |

## 3. 内部逻辑

### 3.1 分支操作类型

BRU 支持以下分支和跳转操作：

| 操作 | 描述 | 条件计算 | 目标计算 |
|------|------|----------|----------|
| BEQ | 相等则分支 | `Src1 == Src2` | `PC + Imm` |
| BNE | 不相等则分支 | `Src1 != Src2` | `PC + Imm` |
| BLT | 小于则分支（有符号） | `Src1.asSInt < Src2.asSInt` | `PC + Imm` |
| BGE | 大于或等于则分支（有符号） | `Src1.asSInt >= Src2.asSInt` | `PC + Imm` |
| BLTU | 小于则分支（无符号） | `Src1 < Src2` | `PC + Imm` |
| BGEU | 大于或等于则分支（无符号） | `Src1 >= Src2` | `PC + Imm` |
| JAL | 跳转并链接 | 始终为真 | `PC + Imm` |
| JALR | 跳转并链接寄存器 | 始终为真 | `(Src1 + Imm) & ~1` |

### 3.2 条件评估

BRU 根据操作类型评估分支条件：

```scala
val condition = brOp match {
  case BRUOp.BEQ  => src1 === src2
  case BRUOp.BNE  => src1 =/= src2
  case BRUOp.BLT  => src1.asSInt < src2.asSInt
  case BRUOp.BGE  => src1.asSInt >= src2.asSInt
  case BRUOp.BLTU => src1 < src2
  case BRUOp.BGEU => src1 >= src2
  case BRUOp.JAL  => true.B
  case BRUOp.JALR => true.B
}
```

### 3.3 目标地址计算

根据指令类型计算实际目标地址：

```scala
val actualTarget = brOp match {
  case BRUOp.JALR => (src1 + imm) & ~1.U(32.W)  // 清除 JALR 的最低位
  case _         => pc + imm                      // 其他为 PC 相对
}
```

### 3.4 预测比较

BRU 将计算结果与预测进行比较：

```scala
val predictionCorrect = condition === prediction.Taken && actualTarget === prediction.Target
```

### 3.5 分支决议

基于预测比较，BRU 生成适当的控制信号，当周期存储至寄存器中，下一周期向全局广播：

* `branchOH` 给出对应独热码标识

* 当预测与实际结果匹配时：
- `branchFlush` 信号维持为 0

* 当预测与实际结果不匹配时：
- 拉高 `branchFlush` 信号
- 将 `branchPC` 设置为正确的目标地址

### 3.6 结果维护

维护 `busy` 与 `resultReg` 寄存器以存储向 CDB 广播的结果。

## 4. 测试考虑

测试用例应覆盖：
1. 所有分支操作类型
2. 正确预测场景
3. 错误预测场景（跳转/不跳转）
4. 错误目标场景
5. 边界情况（溢出、下溢）
