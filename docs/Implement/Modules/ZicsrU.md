# ZicsrU (CSR 指令单元) 设计文档

## 1. 概述

ZicsrU（Zicsr Instructions Unit，CSR 指令单元）负责执行 RISC-V Zicsr 扩展定义的控制和 CSR 读写指令。

ZicsrU 在 CPU 中发挥以下关键作用：

- **CSR 读取**：根据 CSR 地址从 CSRsUnit 读取当前值
- **新值计算**：根据 CSROp（RW/RS/RC）和操作数（RS1/Imm）计算新值
- **序列化执行**：CSR 指令在 ROB 头部序列化执行，确保 CSR 操作的原子性
- **结果广播**：将旧值（读出的 CSR 值）广播到 CDB，用于写回目标寄存器

ZicsrU 是 Tomasulo 架构中的四个执行单元之一，与算术逻辑单元（ALU）、分支解决单元（BRU）和加载存储单元（LSU）并列。其在 CDB 仲裁中具有最高优先级（ZICSRU > LSU > BRU > ALU），以确保 CSR 操作的及时性和原子性。

## 2. ZicsrU 接口

### 2.1 输入接口

ZicsrU 从多个源接收输入。

#### 2.1.1 来自 Dispatcher

ZicsrU 从 RS.scala 中 Dispatcher 模块接收 CSR 指令的分发信息，使用 [`ZicsrDispatch`](../../../src/main/scala/cpu/Protocol.scala) 结构体：

| 信号             | 类型       | 描述                             |
| ---------------- | ---------- | -------------------------------- |
| `zicsrOp`        | `ZicsrOp`  | CSR 操作类型（RW/RS/RC）         |
| `csrAddr`        | `CsrAddrW` | CSR 地址                         |
| `robId`          | `RobTag`   | 重排序缓冲区条目标识符           |
| `phyRd`          | `PhyTag`   | 目标物理寄存器标识符             |
| `data.src1Sel`   | `Src1Sel`  | 第一个操作数源的选择器           |
| `data.src1Tag`   | `PhyTag`   | 第一个操作数依赖的物理寄存器标签 |
| `data.src1Ready` | `Bool`     | 第一个操作数是否就绪             |
| `data.imm`       | `DataW`    | 立即数（用于 I 类型 CSR 指令）   |

> Zicsr 指令解析出 data 中第二个操作数为 x0 或 Imm，因此没有指出相关信号。

#### 2.1.2 来自总线 CDB

ZicsrU 通过总线 CDB 接收操作数就绪信号：

| 信号  | 类型     | 描述                               |
| ----- | -------- | ---------------------------------- |
| `cdb` | `RhyTag` | 广播的操作数对应的物理寄存器标识符 |

#### 2.1.2 来自物理寄存器文件（PRF）

| 信号        | 类型    | 描述                                      |
| ----------- | ------- | ----------------------------------------- |
| `ReadData1` | `DataW` | 从物理寄存器文件读取的第一个操作数（RS1） |

#### 2.1.3 来自 CSRsUnit

| 信号          | 类型        | 描述                          |
| ------------- | ----------- | ----------------------------- |
| `CSRReadData` | `DataW`     | 从 CSRsUnit 读取的 CSR 当前值 |
| `exception`   | `Exception` | CSR 读取/写入异常信息         |

#### 2.1.4 来自重排序缓冲区（ROB）

| 信号          | 类型   | 描述                                              |
| ------------- | ------ | ------------------------------------------------- |
| `commitReady` | `Bool` | 提交信号，表示该 CSR 指令已在队头，可以进行写操作 |

### 2.2 输出接口

#### 2.2.1 广播结果

ZicsrU 将结果输出到公共数据总线（CDB），使用 [`CDBMessage`](../../../src/main/scala/cpu/Protocol.scala) 结构体：

| 信号            | 类型        | 描述                          |
| --------------- | ----------- | ----------------------------- |
| `robId`         | `RobTag`    | 重排序缓冲区条目标识符        |
| `phyRd`         | `PhyTag`    | 目标物理寄存器标识符          |
| `data`          | `DataW`     | 计算结果（CSR 旧值）          |
| `hasSideEffect` | `Bits(1.W)` | 副作用标志（ZicsrU 固定为 0） |
| `exception`     | `Exception` | 异常信息（如果有）            |

#### 2.2.2 访问 CSRsUnit

ZicsrU 通过以下四个接口与 CSRsUnit 进行交互，实现 CSR 的读取和写入操作。

##### 读请求接口

ZicsrU 向 CSRsUnit 发送读请求，使用 `CsrReadReq` 结构体：

| 信号       | 类型         | 描述                |
| ---------- | ------------ | ------------------- |
| `csrAddr`  | `CsrAddrW`   | CSR 地址（12 位）   |
| `privMode` | `PrivMode()` | 特权级模式（U/S/M） |

##### 读响应接口

CSRsUnit 向 ZicsrU 发出请求同周期返回读响应，使用 `CsrReadResp` 结构体：

| 信号        | 类型        | 描述                                    |
| ----------- | ----------- | --------------------------------------- |
| `data`      | `DataW`     | 读取的 CSR 数据（32 位）                |
| `exception` | `Exception` | 读取异常信息（包含 valid、cause、tval） |

##### 写请求接口

ZicsrU 向 CSRsUnit 发送写请求，使用 `CsrWriteReq` 结构体：

| 信号       | 类型         | 描述                  |
| ---------- | ------------ | --------------------- |
| `csrAddr`  | `CsrAddrW`   | CSR 地址（12 位）     |
| `privMode` | `PrivMode()` | 特权级模式（U/S/M）   |
| `data`     | `DataW`      | 要写入的数据（32 位） |

##### 写响应接口

CSRsUnit 向 ZicsrU 当周期返回写响应，使用 `CsrWriteResp` 结构体：

| 信号        | 类型        | 描述                                    |
| ----------- | ----------- | --------------------------------------- |
| `exception` | `Exception` | 写入异常信息（包含 valid、cause、tval） |

> 异步设计太过 overkill，因此进行一周期读写约定即可。

### 2.3 ZicsrU 接口设计

```scala
class ZicsrU extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 RS 的输入
    val zicsrReq = Flipped(Decoupled(new ZicsrDispatch))

    // 向 PRF 的读请求
    val prfReq = Decoupled(new PrfReadReq)
    // 来自 PRF 的回复数据
    val prfData = Flipped(Decoupled(new PrfReadData))

    // 来自 CSRsUnit 的接口
    val csrReadReq = Decoupled(new CsrReadReq)
    val csrReadResp = Input(new CsrReadResp)
    val csrWriteReq = Decoupled((new CsrWriteReq))
    val csrWriteResp = Input(new CsrWriteResp)

    // 来自 ROB 的输入
    val commitReady = Input(Bool())

    // 输出到 CDB
    val CDB = Decoupled(new CDBMessage)

    // 分支冲刷信号
    val branchFlush = Input(Bool())
    val branchOH = Input(SnapshotMask)
  })
}
```

## 3. 支持的操作

### 3.1 操作类型

ZicsrU 支持 `ZicsrOp` 枚举中定义的以下操作：

| 操作  | 描述         | RISC-V 指令         | 新值计算                         |
| ----- | ------------ | ------------------- | -------------------------------- |
| `RW`  | 读写 CSR     | CSRRW, CSRRWI       | `NewValue = RS1/Imm`             |
| `RS`  | 读并设置 CSR | CSRRS, CSRRSI       | `NewValue = OldValue \| RS1/Imm` |
| `RC`  | 读并清除 CSR | CSRRC, CSRRCI       | `NewValue = OldValue & ~RS1/Imm` |
| `NOP` | 无操作       | 非 CSR 指令的默认值 | 无                               |

#### 3.1.1 副作用规避

为了避免 CSR 读写操作带来的不必要的副作用，硬件会对目标寄存器 `rd` 和源寄存器 `rs1` 是否为 `x0`（零寄存器）进行特殊判定，规则如下：

1. **对于 `csrrw`/`csrrwi`（RW 操作）**：
   - 如果 `rd == x0`，硬件**不执行读操作**。
   - 不会从 CSRsUnit 读取 CSR 的当前值。
   - 写入 CSR 操作仍然执行。

2. **对于 `csrrs`/`csrrsi`（RS 操作）和 `csrrc`/`csrrci`（RC 操作）**：
   - 如果 `rs1 == x0`（寄存器版本）或立即数为 0（立即数版本），硬件**不执行写操作**。
   - 不会向 CSRsUnit 发送写请求。
   - 读取操作仍然执行。

### 3.2 指令映射

下表显示了 RISC-V CSR 指令如何映射到 ZicsrU 操作：

| 指令   | 格式   | ZicsrOp | RS1 源            |
| ------ | ------ | ------- | ----------------- |
| CSRRW  | I 类型 | RW      | 寄存器 rs1        |
| CSRRWI | I 类型 | RW      | 零寄存器 + 立即数 |
| CSRRS  | I 类型 | RS      | 寄存器 rs1        |
| CSRRSI | I 类型 | RS      | 零寄存器 + 立即数 |
| CSRRC  | I 类型 | RC      | 寄存器 rs1        |
| CSRRCI | I 类型 | RC      | 零寄存器 + 立即数 |

## 4. 内部逻辑

### 4.1 维护状态

每一条 CSR 指令在 ZicsrU 内部以如下方式原子化执行：先进入等待状态直至所有操作数就绪，之后访问 CSRsUnit 获得结果，计算出结果并进行第一次提交，等待指令到达 ROB 头部返回信号，此时执行 CSR 写入，最后再次广播结果使 ROB 能够 Commit 这条指令。如果等待中途出现 branchFlush 信号可能被冲刷。

> CSRs 的读取因为有幂等性可以投机执行，但是写入必须等到 ROB 头部才能完成。

因此有如下状态需要维护：
1. **状态记录**：记录当前指令的状态（IDLE 或等待操作数或等待 ROB 头部信号）
2. **指令寄存**：储存指令信息—— CSR 地址、操作数、ROB ID、目标物理寄存器，ZicsrOp 、计算出的新值与新的异常等。

```scala
// 状态枚举
object ZicsrState extends ChiselEnum {
  val IDLE = Value           // 空闲状态
  val WAIT_OPERANDS = Value  // 等待操作数就绪
  val WAIT_ROB_HEAD = Value  // 等待 ROB 头部信号
}

// 当前状态
val state = RegInit(ZicsrState.IDLE)
// 结果寄存器忙标志
val resultBusy = RegInit(false.B)

// 指令寄存器
val instructionReg = Reg(new ZicsrDispatch)
val csrRdataReg = Reg(UInt(32.W))  // CSR 读取值寄存器
val csrWdataReg = Reg(UInt(32.W))  // CSR 写入值寄存器
val exceptionReg = Reg(new Exception) // 异常信息寄存器

// 定义使能信号
val busy = WireDefault(false.B) // 模块忙碌标志
val calculate = WireDefault(false.B) // 读取 Reg 与 CSR并计算新值
val writeBack = WireDefault(false.B) // 写回阶段，所有权移交至 ROB
val needFlush = WireDefault(false.B) // 需要冲刷

// 忙标志
busy := state =/= ZicsrState.IDLE
needFlush := io.branchFlush
// 计算其他使能
io.zicsrReq.ready := !busy && !needFlush
calculate := !needFlush && state === ZicsrState.WAIT_OPERANDS && instructionReg.data.src1Ready
writeBack := !needFlush && state === ZicsrState.WAIT_ROB_HEAD && io.commitReady

// 状态转移
when(io.zicsrReq.fire()) {
  state := ZicsrState.WAIT_OPERANDS
  instructionReg := io.zicsrReq.bits
}.elsewhen(calculate) {
  state := ZicsrState.WAIT_ROB_HEAD
}.elsewhen(writeBack) {
  state := ZicsrState.IDLE
}
```
  
### 4.2 操作数读取与计算

当指令操作数就绪时，ZicsrU 根据 CSR 地址从 CSRsUnit 读取当前值，根据 rs 地址从 PRF 中读取数据：

```scala
val src1Ready = io.cdb.valid && io.cdb.bits.cdbTag === instructionReg.data.src1Tag && state === ZicsrState.WAIT_OPERANDS

when(src1Ready) {
  instructionReg.data.src1Ready := true.B
}

// 集体使能
val rdIsX0 = instructionReg.phyRd === 0.U // 判定目标寄存器是否为 x0
io.csrReadReq.valid := calculate && !rdIsX0 // 根据 x0 判定控制 CSR 读请求
io.prfReq.valid := calculate
io.prfData.ready := calculate

// 发送 CSR 读请求
io.csrReadReq.bits.csrAddr := instructionReg.csrAddr
io.csrReadReq.bits.privMode := instructionReg.privMode

// 从 PRF 读取 RS1 数据
io.prfReq.bits.raddr1 := instructionReg.data.src1Tag
io.prfReq.bits.raddr2 := instructionReg.data.src2Tag

val oldValue = Mux(calculate, io.csrReadResp.data, 0.U)
// 根据信息计算 CSR 之外的操作数
val oprand1 = MuxCase(0.U, Seq(
  instructionReg.data.src1Sel === Src1Sel.REG -> io.prfData.bits.rdata1,
  instructionReg.data.src1Sel === Src1Sel.ZERO -> instructionReg.data.imm
))


```

进而 ZicsrU 根据 `zicsrOp` 控制信号计算新值：

```scala
// 新值计算
val newValue = MuxLookup(io.zicsrReq.zicsrOp, oldValue, Seq(
  ZicsrOp.RW -> oprand1,                      // CSRRW/CSRRWI: 新值 = RS1/Imm
  ZicsrOp.RS -> (oldValue | oprand1),         // CSRRS/CSRRSI: 新值 = 旧值 | RS1/Imm
  ZicsrOp.RC -> (oldValue & ~oprand1),        // CSRRC/CSRRCI: 新值 = 旧值 & ~RS1/Imm
))

when(calculate) {
  // 根据 x0 判定控制读取返回值
  val csrReadResp = Mux(rdIsX0, 0.asTypeOf(csrReadResp), io.csrReadResp) 
  csrRdataReg := csrReadResp.data
  exceptionReg := csrReadResp.exception
  val exceptionValid = csrReadResp.exception.valid
  csrWdataReg := Mux(exceptionValid, 0.U, newValue)
  instructionReg.csrAddr := Mux(exceptionValid, 0.U, instructionReg.csrAddr)
}

```

### 4.3 冲刷
ZicsrU 在等待操作数或 ROB 头部信号时可能被分支冲刷，因此需要在 `branchFlush` 信号有效且 `branchOH` 与当前指令 `branchMask` 时重置状态和寄存器：

```scala
when((state =/= ZiscrState.IDLE) && (io.branchOH & instructionReg.branchMask) =/= 0.U) {
  when(io.branchFlush) {
    state := ZicsrState.IDLE
    instructionReg := 0.U.asTypeOf(instructionReg)
    csrRdataReg := 0.U
    csrWdataReg := 0.U
    resultReg := 0.U
    exceptionReg := 0.U.asTypeOf(exceptionReg)
    resultBusy := false.B
  }.otherwise {
    // 移除对应的分支依赖
    instructionReg.branchMask := instructionReg.branchMask & ~io.branchOH
  }
}
```

### 4.4 序列化执行

CSR 指令在 ROB 头部序列化执行，确保 CSR 操作的原子性：

```scala
// CSR 写入请求
val src1IsX0 = instructionReg.data.src1Sel === Src1Sel.ZERO && instructionReg.data.imm === 0.U
io.csrWriteReq.valid := writeBack && !src1IsX0 // 根据 x0 判定控制 CSR 写请求
io.csrWriteReq.bits.csrAddr := instructionReg.csrAddr
io.csrWriteReq.bits.privMode := instructionReg.privMode
io.csrWriteReq.bits.data := csrWDataReg
```

### 4.5 结果寄存和广播

ZicsrU 在向 CDB 广播之前将结果存储在结果寄存器中：

```scala
when(calculate || canWrite) {
  resultBusy := true.B
}
when(io.cdb.fire){
  resultBusy := false.B
}

when(canWrite) {
  val csrWriteResp = Mux(srcIsX0, 0.asTypeOf(csrWriteResp), io.csrWriteResp) // 根据 x0 判定控制写响应
  exceptionReg := Mux(exceptionReg.valid, exceptionReg, csrWriteResp.exception)
}

// CDB 输出（使用 CDBMessage 结构体）
io.cdb.valid := resultBusy && !needFlush
io.cdb.bits.robId := instructionReg.robId
io.cdb.bits.phyRd := Mux(canWrite, 0.U, instructionReg.phyRd)
io.cdb.bits.data := Mux(canWrite, 0.U, resultReg)
io.cdb.bits.hasSideEffect := false.b
io.cdb.bits.exception := exceptionReg
```


## 5. 验证考虑

### 5.1 功能验证

ZicsrU 的关键验证点：

1. **操作正确性**：验证每个 ZicsrOp 操作产生正确的新值
2. **旧值正确性**：验证广播到 CDB 的旧值与 CSR 读取值一致
3. **操作数选择**：验证所有 `src1Sel` 选择器的正确操作数选择
4. **冲刷**：验证在分支冲刷时状态和寄存器正确重置或更新
5. **序列化执行**：验证 CSR 写入只在 ROB 发出提交信号时才执行
6. **副作用规避（RW 操作）**：
   - 验证当 `rd == x0` 时，CSRRW/CSRRWI 不执行 CSR 读操作
   - 验证当 `rd == x0` 时，不将 CSR 旧值广播到 CDB
   - 验证当 `rd != x0` 时，正常执行 CSR 读操作和广播
7. **副作用规避（RS 操作）**：
   - 验证当 `rs1 == x0`（CSRRS）或立即数为 0（CSRRSI）时，不执行 CSR 写操作
   - 验证当 `rs1 == x0` 或立即数为 0 时，仍然执行 CSR 读操作并广播旧值
   - 验证当 `rs1 != x0` 或立即数不为 0 时，正常执行 CSR 写操作
8. **副作用规避（RC 操作）**：
   - 验证当 `rs1 == x0`（CSRRC）或立即数为 0（CSRRCI）时，不执行 CSR 写操作
   - 验证当 `rs1 == x0` 或立即数为 0 时，仍然执行 CSR 读操作并广播旧值
   - 验证当 `rs1 != x0` 或立即数不为 0 时，正常执行 CSR 写操作


### 5.2 边界情况测试

测试用例应覆盖：

1. **所有 ZicsrOp 类型**：RW、RS、RC 操作
2. **寄存器操作数**：使用 rs1 作为操作数的 CSR 指令
3. **立即数操作数**：使用立即数作为操作数的 CSR 指令
4. **序列化执行**：多个 CSR 指令连续执行，验证序列化
5. **异常处理**：验证 CSR 读取和写入异常的正确处理
6. **ROB 队头检查**：验证 CSR 写入只在 ROB 队头时执行
7. **冲刷**：在等待操作数和等待 ROB 头部信号时触发分支冲刷，验证状态重置和寄存器更新
8. **复杂控制信号**：验证冲刷信号在各个状态到来都能被正确处理，比如与 commitReady 同时到来 （commitReady 为该指令在头部时一直拉高的长时程信号）