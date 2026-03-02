# PMPChecker (物理内存保护检查器) 设计文档

## 1. 概述

PMPChecker（物理内存保护检查器）负责执行 RISC-V 特权架构规范定义的物理内存保护（Physical Memory Protection, PMP）权限检查。作为 LSU 内部的组合逻辑模块，PMPChecker 对每个内存访问请求进行权限验证，确保只有符合 PMP 规则的访问才能通过。

### 1.1 目的和作用

PMPChecker 在 CPU 中发挥以下关键作用：

- **内存保护**：通过 PMP 配置寄存器实现对物理内存区域的访问控制，防止未授权的读、写或执行操作。
- **特权级隔离**：根据当前特权级（U/S/M）和 PMP 配置，对不同特权级的访问请求应用不同的权限规则。
- **安全执行**：为系统提供硬件级别的内存保护机制，支持操作系统和虚拟机管理器的安全隔离需求。
- **异常生成**：当访问请求违反 PMP 规则时，生成相应的访问异常（Access Fault）。

PMPChecker 是 AGU（地址生成单元）的配合模块，在地址生成完成后立即进行权限检查，所有检查逻辑在单周期内完成，不引入额外的流水线延迟。

## 2. 模块接口

### 2.1 输入接口

PMPChecker 从 LSU 接收检查请求，使用 [`PMPCheckReq`](../../src/main/scala/cpu/Protocol.scala:483-487) 结构体：

#### 2.1.1 来自 AGU

| 信号 | 类型 | 描述 |
|------|------|------|
| `addr` | `UInt(32.W)` | 待检查的物理地址 |
| `memOp` | `LSUOp()` | 访存类型（LOAD/STORE） |
| `privMode` | `PrivMode()` | 当前特权级（U/S/M） |

#### 2.1.2 来自 CSRsUnit

| 信号 | 类型 | 描述 |
|------|------|------|
| `pmpcfg` | `Vec(16, UInt(8.W))` | PMP 配置寄存器数组（pmpcfg0-pmpcfg15） |
| `pmpaddr` | `Vec(16, UInt(32.W))` | PMP 地址寄存器数组（pmpaddr0-pmpaddr15） |

### 2.2 输出接口

PMPChecker 将检查结果返回给 AGU：

| 信号 | 类型 | 描述 |
|------|------|------|
| `exception` | `Exception` | 异常包（访问异常或无异常） |
| `pmpMatch` | `Bool()` | PMP 匹配标志（用于调试） |

### 2.3 Chisel 接口定义

PMPChecker 模块的接口定义在 [`Protocol.scala`](../../src/main/scala/cpu/Protocol.scala) 中，使用以下结构体：

```scala
// PMP 检查请求（在 Protocol.scala 第 483-487 行）
class PMPCheckReq extends Bundle with CPUConfig {
  val addr = UInt(32.W) // 物理地址
  val memOp = LSUOp()   // 访存类型 (Load/Store)
  val privMode = PrivMode() // 特权级
}

// 特权级枚举（在 Protocol.scala 第 80-84 行）
object PrivMode extends ChiselEnum {
  val U = Value(0.U) // User Mode
  val S = Value(1.U) // Supervisor Mode
  val M = Value(3.U) // Machine Mode
}

// 访存操作枚举（在 Protocol.scala 第 57-59 行）
object LSUOp extends ChiselEnum {
  val LOAD, STORE, NOP = Value
}

// 异常包定义（在 Protocol.scala 第 134-138 行）
class Exception extends Bundle {
  val valid = Bool()
  val cause = UInt(32.W)
  val tval = UInt(32.W)
}
```

## 3. 支持的功能

### 3.1 物理内存保护（PMP）权限检查

PMPChecker 支持 RISC-V 特权架构规范定义的 PMP 功能：

| 功能 | 描述 |
|------|------|
| **地址范围匹配** | 根据地址寄存器配置匹配物理地址所属的 PMP 区域 |
| **权限验证** | 检查访问请求的读/写权限是否与 PMP 配置一致 |
| **特权级检查** | 根据当前特权级和 PMP 配置的 L 位决定是否强制执行权限检查 |
| **优先级仲裁** | 当多个 PMP 条目匹配时，使用小编号优先的策略 |

### 3.2 读/写/执行权限验证

PMPChecker 支持以下权限验证：

| 权限类型 | 描述 | 对应异常 |
|---------|------|---------|
| **读权限（R）** | 检查 LOAD 操作是否允许读取目标区域 | `LOAD_ACCESS_FAULT` |
| **写权限（W）** | 检查 STORE 操作是否允许写入目标区域 | `STORE_ACCESS_FAULT` |
| **执行权限（X）** | 检查指令取指是否允许执行目标区域（由 I-Cache 使用） | `INSTRUCTION_ACCESS_FAULT` |

### 3.3 地址范围匹配

PMPChecker 支持两种地址匹配模式：

| 模式 | 描述 | 地址范围计算 |
|------|------|-------------|
| **NAPOT (Naturally Aligned Power-of-Two)** | 自然对齐的 2 的幂次方区域 | 基于地址寄存器的低位零个数 |
| **TOR (Top-of-Range)** | 范围模式，使用两个相邻地址寄存器定义区域 | `[pmpaddr[i-1], pmpaddr[i])` |

### 3.4 优先级匹配

PMPChecker 使用自大编号向小编号的匹配策略：

| 策略 | 描述 |
|------|------|
| **匹配顺序** | 从编号最大的 PMP 条目（如 pmp15）向编号最小的条目（如 pmp0）遍历 |
| **优先级** | 编号最小的匹配条目具有最高优先级 |
| **默认策略** | 如果没有条目匹配且 `privMode < M`，默认拒绝访问 |

## 4. 内部逻辑

### 4.1 PMP 配置寄存器解析

PMPChecker 首先解析 PMP 配置寄存器，提取每个条目的关键信息：

```scala
// PMP 配置寄存器格式（每个字节对应一个 PMP 条目）
// bit[7] = L (锁定位)
// bit[6:3] = A (地址匹配模式)
// bit[2] = X (执行权限)
// bit[1] = W (写权限)
// bit[0] = R (读权限)

val pmpR = Wire(Vec(16, Bool()))
val pmpW = Wire(Vec(16, Bool()))
val pmpX = Wire(Vec(16, Bool()))
val pmpL = Wire(Vec(16, Bool()))
val pmpA = Wire(Vec(16, UInt(4.W)))

for (i <- 0 until 16) {
  pmpR(i) := pmpcfg(i)(0)
  pmpW(i) := pmpcfg(i)(1)
  pmpX(i) := pmpcfg(i)(2)
  pmpL(i) := pmpcfg(i)(7)
  pmpA(i) := pmpcfg(i)(6, 3)
}
```

### 4.2 地址匹配逻辑

PMPChecker 对每个 PMP 条目执行地址匹配：

```scala
// 地址匹配标志
val pmpMatch = Wire(Vec(16, Bool()))

for (i <- 0 until 16) {
  when (pmpA(i) === 0.U) { // OFF 模式
    pmpMatch(i) := false.B
  } .elsewhen (pmpA(i) === 1.U) { // TOR 模式
    when (i === 0.U) {
      pmpMatch(i) := (addr < pmpaddr(i))
    } .otherwise {
      pmpMatch(i) := (addr >= pmpaddr(i-1) && addr < pmpaddr(i))
    }
  } .elsewhen (pmpA(i) === 2.U) { // NA4 模式
    pmpMatch(i) := (addr(31, 2) === pmpaddr(i)(31, 2))
  } .elsewhen (pmpA(i) === 3.U) { // NAPOT 模式
    // 根据 pmpaddr 中的低位零个数计算掩码
    val napotMask = Wire(UInt(32.W))
    // ... NAPOT 掩码计算逻辑
    pmpMatch(i) := ((addr & napotMask) === (pmpaddr(i) & napotMask))
  } .otherwise {
    pmpMatch(i) := false.B
  }
}
```

### 4.3 权限检查逻辑

PMPChecker 根据匹配结果和权限配置执行权限检查：

```scala
// 权限需求
val needR = (io.req.memOp === LSUOp.LOAD)
val needW = (io.req.memOp === LSUOp.STORE)

// 默认拒绝（当 privMode < M 且没有匹配条目）
val defaultDeny = (io.req.privMode =/= PrivMode.M)

// 检查每个匹配的条目
val accessGranted = Wire(Bool())
val pmpException = Wire(Bool())

pmpException := defaultDeny

// 自大编号向小编号遍历
for (i <- 15 to 0 by -1) {
  when (pmpMatch(i)) {
    // 如果 L=1 或 privMode < M，强制执行权限检查
    val enforceCheck = pmpL(i) || (io.req.privMode =/= PrivMode.M)

    when (enforceCheck) {
      val rOk = !needR || pmpR(i)
      val wOk = !needW || pmpW(i)
      pmpException := !(rOk && wOk)
    } .otherwise {
      // M-mode 且 L=0，允许访问
      pmpException := false.B
    }
  }
}
```

### 4.4 优先级仲裁

PMPChecker 使用自大编号向小编号的匹配策略，确保小编号条目具有最高优先级：

```scala
// 找到匹配的条目中编号最小的
val matchedIdx = Wire(UInt(4.W))
val hasMatch = pmpMatch.asUInt.orR

// 优先级仲裁：小编号优先
matchedIdx := PriorityEncoder(pmpMatch.reverse).asUInt // 反转后编码，实现小编号优先

// 使用匹配的条目进行权限检查
val finalException = Wire(new Exception)
finalException.valid := pmpException
finalException.cause := Mux(needW,
  ExceptionCause.STORE_ACCESS_FAULT,
  ExceptionCause.LOAD_ACCESS_FAULT
)
finalException.tval := io.req.addr
```

## 5. 验证考虑

### 5.1 功能验证

PMPChecker 的关键验证点：

1. **权限检查正确性**：验证每个 PMP 配置下的读/写权限检查是否正确
2. **地址匹配正确性**：验证 TOR 和 NAPOT 模式的地址范围匹配是否正确
3. **特权级处理**：验证不同特权级（U/S/M）下的权限检查行为
4. **L 位处理**：验证锁定位（L=1）对 M-mode 访问的影响
5. **默认策略**：验证没有匹配条目时的默认拒绝策略

### 5.2 边界条件验证

关键边界条件：

1. **地址边界**：验证 PMP 区域边界处的访问行为
2. **全零配置**：验证所有 PMP 条目未配置时的行为
3. **重叠区域**：验证多个 PMP 条目重叠时的优先级仲裁
4. **M-mode 特权**：验证 M-mode 在 L=0 时的绕过行为
5. **极端地址**：验证 0x00000000 和 0xFFFFFFFF 等极端地址的访问

### 5.3 集成验证

关键集成验证点：

1. **AGU 接口**：验证与 AGU 的请求/响应接口握手
2. **CSRsUnit 接口**：验证 PMP 配置寄存器的正确传递和更新
3. **异常生成**：验证异常包的正确生成和传递
4. **时序要求**：验证单周期完成检查的时序约束
5. **性能影响**：验证组合逻辑路径不会成为关键路径
