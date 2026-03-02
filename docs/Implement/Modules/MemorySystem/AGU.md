# AGU (地址生成单元) 设计文档

## 1. 概述

AGU（Address Generation Unit，地址生成单元）是 LSU（Load Store Unit）的子模块，负责为所有内存访问指令计算物理地址并进行访问权限检查。作为内存系统的前端，AGU 在指令执行前完成地址计算和权限验证，确保后续的 Cache 访问符合 RISC-V 规范。

### 1.1 目的和作用

AGU 在 LSU 中发挥以下关键作用：

- **地址计算**：将来自 PRF 的基址寄存器值与指令中的立即数偏移量相加，生成物理地址。
- **对齐检查**：根据访存位宽（Byte/Half/Word）验证物理地址是否正确对齐，防止未对齐访问。
- **权限检查**：与 PMPChecker 配合，根据当前特权级和 PMP 配置验证访问权限。
- **异常生成**：当检测到对齐错误或权限违规时，生成相应的异常信息。

AGU 是 LSU 的关键子模块，与 LSQ（Load/Store Queue）和 PMPChecker 紧密协作，确保内存访问的正确性和安全性。AGU 的所有操作均在单周期内完成，结果直接返回给 LSU 用于后续的 Cache 访问。

## 2. 模块接口

### 2.1 输入接口

AGU 从 LSU 接收地址生成请求，使用 [`AGUReq`](../../../src/main/scala/cpu/Protocol.scala:467-474) 结构体：

#### 2.1.1 来自 LSU 的请求

| 信号 | 类型 | 描述 |
|------|------|------|
| `baseAddr` | `DataW` | 基地址寄存器值（来自 rs1） |
| `offset` | `DataW` | 偏移量（来自指令立即数） |
| `memWidth` | `LSUWidth()` | 访存位宽（BYTE/HALF/WORD） |
| `memOp` | `LSUOp()` | 访存类型（LOAD/STORE） |
| `privMode` | `PrivMode()` | 当前特权级（U/S/M） |
| `ctx` | `MemContext` | 内存上下文（包含 epoch、branchMask、robId） |

### 2.2 输出接口

AGU 将计算结果返回给 LSU，使用 [`AGUResp`](../../../src/main/scala/cpu/Protocol.scala:477-481) 结构体：

| 信号 | 类型 | 描述 |
|------|------|------|
| `pa` | `UInt(32.W)` | 计算出的物理地址 |
| `exception` | `Exception` | 异常包（对齐异常或访问异常） |
| `ctx` | `MemContext` | 原样带回的内存上下文 |

#### 2.2.1 到 PMPChecker 的检查请求

AGU 内部会生成 PMP 检查请求，使用 [`PMPCheckReq`](../../../src/main/scala/cpu/Protocol.scala:483-487) 结构体：

| 信号 | 类型 | 描述 |
|------|------|------|
| `addr` | `UInt(32.W)` | 待检查的物理地址 |
| `memOp` | `LSUOp()` | 访存类型（LOAD/STORE） |
| `privMode` | `PrivMode()` | 当前特权级（U/S/M） |

### 2.3 Chisel 接口定义

AGU 模块的接口定义在 [`Protocol.scala`](../../../src/main/scala/cpu/Protocol.scala) 中，使用以下结构体：

```scala
// LSU -> AGU 请求（在 Protocol.scala 第 467-474 行）
class AGUReq extends Bundle with CPUConfig {
  val baseAddr = DataW      // 基地址
  val offset = DataW        // 偏移量（立即数）
  val memWidth = LSUWidth() // 访存位宽
  val memOp = LSUOp()       // 访存类型（Load/Store）
  val privMode = PrivMode() // 特权级
  val ctx = new MemContext  // 内存上下文
}

// AGU -> LSU 响应（在 Protocol.scala 第 477-481 行）
class AGUResp extends Bundle with CPUConfig {
  val pa = UInt(32.W)       // 计算出的物理地址
  val exception = new Exception // 异常包（对齐异常或访问异常）
  val ctx = new MemContext  // 原样带回的内存上下文
}

// 内存上下文（在 Protocol.scala 第 420-424 行）
class MemContext extends Bundle with CPUConfig {
  val epoch = EpochW        // 纪元标记（用于逻辑撤销）
  val branchMask = SnapshotMask // 分支掩码（用于分支冲刷）
  val robId = RobTag        // ROB 条目 ID
}

// PMP 检查请求（在 Protocol.scala 第 483-487 行）
class PMPCheckReq extends Bundle with CPUConfig {
  val addr = UInt(32.W)     // 物理地址
  val memOp = LSUOp()       // 访存类型（Load/Store）
  val privMode = PrivMode() // 特权级
}

// 访存位宽枚举（在 Protocol.scala 第 61-63 行）
object LSUWidth extends ChiselEnum {
  val BYTE, HALF, WORD = Value
}

// 访存操作枚举（在 Protocol.scala 第 57-59 行）
object LSUOp extends ChiselEnum {
  val LOAD, STORE, NOP = Value
}

// 特权级枚举（在 Protocol.scala 第 80-84 行）
object PrivMode extends ChiselEnum {
  val U = Value(0.U)
  val S = Value(1.U)
  val M = Value(3.U)
}

// 异常结构体（在 Protocol.scala 第 134-138 行）
class Exception extends Bundle {
  val valid = Bool()
  val cause = UInt(32.W)
  val tval = UInt(32.W)
}
```

## 3. 支持的功能

### 3.1 物理地址计算

AGU 支持基于基址和偏移量的物理地址计算：

| 操作 | 描述 | 公式 |
|------|------|------|
| `PA 计算` | 基址 + 偏移 | `pa = baseAddr + offset` |

### 3.2 地址对齐检查

AGU 根据访存位宽进行地址对齐验证：

| 访存位宽 | 对齐要求 | 未对齐异常 |
|---------|---------|-----------|
| `BYTE` | 无需对齐 | 无 |
| `HALF` | 最低 1 位必须为 0 | `LOAD/STORE_ADDRESS_MISALIGNED` |
| `WORD` | 最低 2 位必须为 0 | `LOAD/STORE_ADDRESS_MISALIGNED` |

### 3.3 访问权限检查

AGU 与 PMPChecker 配合进行访问权限检查：

| 检查项 | 描述 | 异常类型 |
|--------|------|---------|
| PMP 权限 | 根据 PMP 配置验证 R/W/X 权限 | `LOAD/STORE_ACCESS_FAULT` |
| 特权级 | 验证当前特权级是否允许访问 | `LOAD/STORE_ACCESS_FAULT` |

### 3.4 异常生成

AGU 支持以下异常类型：

| 异常 | 原因编码 | 触发条件 |
|------|---------|---------|
| `LOAD_ADDRESS_MISALIGNED` | 4 | Load 指令地址未对齐 |
| `STORE_ADDRESS_MISALIGNED` | 6 | Store 指令地址未对齐 |
| `LOAD_ACCESS_FAULT` | 5 | Load 指令 PMP 权限违规 |
| `STORE_ACCESS_FAULT` | 7 | Store 指令 PMP 权限违规 |

## 4. 内部逻辑

### 4.1 地址计算逻辑

AGU 使用简单的加法器计算物理地址：

```scala
// 物理地址计算
val pa = io.aguReq.baseAddr + io.aguReq.offset
```

### 4.2 对齐检查逻辑

AGU 根据访存位宽进行对齐检查：

```scala
// 对齐检查
val misaligned = MuxLookup(io.aguReq.memWidth, false.B, Seq(
  LSUWidth.BYTE -> false.B,                    // Byte 无需对齐
  LSUWidth.HALF -> pa(0),                      // Half: 最低 1 位必须为 0
  LSUWidth.WORD -> (pa(1) | pa(0))             // Word: 最低 2 位必须为 0
))

// 生成对齐异常
val alignException = Wire(new Exception)
alignException.valid := misaligned
alignException.cause := Mux(io.aguReq.memOp === LSUOp.LOAD,
  ExceptionCause.LOAD_ADDRESS_MISALIGNED,
  ExceptionCause.STORE_ADDRESS_MISALIGNED)
alignException.tval := pa
```

### 4.3 权限检查流程

AGU 通过 PMPChecker 进行权限检查：

```scala
// 生成 PMP 检查请求
val pmpCheckReq = Wire(new PMPCheckReq)
pmpCheckReq.addr := pa
pmpCheckReq.memOp := io.aguReq.memOp
pmpCheckReq.privMode := io.aguReq.privMode

// 发送 PMP 检查请求（假设 PMPChecker 在 LSU 内部）
// io.pmpCheckReq <> pmpCheckReq

// 接收 PMP 检查结果
// val pmpCheckResp = io.pmpCheckResp

// 生成访问异常
val accessException = Wire(new Exception)
accessException.valid := pmpCheckResp.exception.valid
accessException.cause := Mux(io.aguReq.memOp === LSUOp.LOAD,
  ExceptionCause.LOAD_ACCESS_FAULT,
  ExceptionCause.STORE_ACCESS_FAULT)
accessException.tval := pa
```

### 4.4 异常处理

AGU 使用优先级逻辑处理多个可能的异常：

```scala
// 异常优先级：对齐异常 > 访问异常
val finalException = Wire(new Exception)
finalException.valid := alignException.valid || accessException.valid
finalException.cause := Mux(alignException.valid,
  alignException.cause,
  accessException.cause)
finalException.tval := Mux(alignException.valid,
  alignException.tval,
  accessException.tval)

// 输出响应
io.aguResp.pa := pa
io.aguResp.exception := finalException
io.aguResp.ctx := io.aguReq.ctx
```

## 5. 验证考虑

### 5.1 功能验证

AGU 的关键验证点：

1. **地址计算正确性**：验证基址和偏移量的加法计算正确
2. **对齐检查**：验证所有访存位宽的对齐检查逻辑
3. **权限检查**：验证与 PMPChecker 配合的权限检查流程
4. **异常生成**：验证各种异常情况的正确生成

### 5.2 边界条件验证

关键边界条件：

1. **地址溢出**：测试基址和偏移量相加导致的地址溢出
2. **边界对齐**：测试地址边界处的对齐检查（如 0xFFFF, 0x10000）
3. **特权级切换**：测试不同特权级下的权限检查
4. **PMP 配置**：测试各种 PMP 配置下的访问权限

### 5.3 集成验证

关键集成验证点：

1. **LSU 接口**：验证与 LSU 的请求/响应接口正确性
2. **PMPChecker 接口**：验证与 PMPChecker 的检查请求/响应接口
3. **上下文传递**：验证内存上下文（epoch、branchMask、robId）的正确传递
4. **异常传递**：验证异常信息正确传递到 LSU 并最终到 ROB
