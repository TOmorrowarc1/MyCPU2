# RS (Reservation Stations) 模块

## 总体架构

RS 模块由三个子模块组成，三个模块放置在一个 .scala 中，但是不必连接且测试分别进行。

- **Dispatcher**：指令分派器，负责将来自 Decoder 和 RAT 的指令分发到各个执行单元的保留站
- **AluRS**：ALU 保留站，维护等待 ALU 执行的指令队列
- **BruRS**：BRU 保留站，维护等待 BRU 执行的指令队列

> 注意：Load/Store 指令由 LSU 自主维护，Zicsr 与其他特权级相关指令由 ZicsrU 维护，RS 模块只负责分派。

## Dispatcher 模块

### 职责

Dispatcher 是 RS 模块的前端，主要负责以下核心功能：

1. **指令接收与整合**：接收来自 Decoder 的控制信息（`DispatchPacket`）和来自 RAT 的数据信息（`RenameRes`），两者构成完整的指令数据包输入。

2. **指令分派**：根据指令的 opcode 类型，将指令分发到对应的执行单元保留站（AluRS、BruRS、LSU、ZicsrU）同时需要注意不同保留站需要的信息不同。

3. **握手控制**：与 Decoder 和 RAT 进行 Decoupled 握手，与各个 EU 进行握手，控制指令流入 RS 的节奏。

4. **冲刷响应**：响应来自 ROB 的全局冲刷（GlobalFlush）和来自 BRU 的分支冲刷（BranchFlush）信号。

### 接口定义

#### 输入接口

| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `decoder` | Decoder | `Flipped(Decoupled(new DispatchPacket))` | 来自 Decoder 的分派信息，使用 Decoupled 握手协议 |
| `rat` | RAT | `Flipped(Decoupled(new RenameRes))` | 来自 RAT 的重命名结果，使用 Decoupled 握手协议 |
| `globalFlush` | ROB | `Bool` | 全局冲刷信号，高电平有效 |
| `branchFlush` | BRU | `Bool` | 分支冲刷信号，高电平有效 |

`DispatchPacket` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class DispatchPacket extends Bundle with CPUConfig {
  val robId     = RobTag
  val microOp   = new MicroOp
  val pc        = AddrW
  val imm       = DataW
  val privMode  = PrivMode()
  val prediction = new Prediction
  val exception  = new Exception
}
```

`RenameRes` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class RenameRes extends Bundle with CPUConfig {
  val phyRs1      = PhyTag      // 源寄存器1 物理号
  val rs1Busy     = Bool()      // 源寄存器1 是否 Busy
  val phyRs2      = PhyTag      // 源寄存器2 物理号
  val rs2Busy     = Bool()      // 源寄存器2 是否 Busy
  val phyRd       = PhyTag      // 目标寄存器 物理号
  val snapshotId  = UInt(SnapshotIdWidth.W) // 分配的快照 ID (分支专用)
  val branchMask  = SnapshotMask            // 当前依赖的分支掩码
}
```

#### 输出接口

| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `aluRS` | AluRS | `Decoupled(new AluRSDispatch)` | 向 ALU 保留站发送 ALU 指令 |
| `bruRS` | BruRS | `Decoupled(new BruRSDispatch)` | 向 BRU 保留站发送分支指令 |
| `lsu` | LSU | `Decoupled(new LSUDispatch)` | 向 LSU 发送 Load/Store 指令 |
| `zicsr` | ZicsrU | `Decoupled(new ZicsrDispatch)` | 向 ZicsrU 发送 CSR 指令 |

**说明**：输出接口使用 `Decoupled(...)` 封装，表示 Dispatcher 是生产者。

`AluRSDispatch` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class AluRSDispatch extends Bundle with CPUConfig {
  val aluOp       = ALUOp()
  val data        = new DataReq
  val robId       = RobTag
  val phyRd       = PhyTag
  val branchMask  = SnapshotMask
  val exception   = new Exception
}
```

`BruRSDispatch` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class BruRSDispatch extends Bundle with CPUConfig {
  val bruOp       = BRUOp()
  val data        = new DataReq
  val robId       = RobTag
  val phyRd       = PhyTag
  val snapshotId  = UInt(SnapshotIdWidth.W)
  val branchMask  = SnapshotMask
  val prediction  = new Prediction
  val exception   = new Exception
}
```

`DataReq` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class DataReq extends Bundle with CPUConfig {
  val src1Sel    = Src1Sel()
  val src1Tag    = PhyTag
  val src1Busy   = Bool()
  val src2Sel    = Src2Sel()
  val src2Tag    = PhyTag
  val src2Busy   = Bool()
  val imm        = DataW
  val pc         = AddrW
}
```

### 内部逻辑

#### 1. 数据包整合

Dispatcher 需要将 Decoder 和 RAT 的信息整合为完整的指令数据包：

```scala
// 整合数据请求
val dataReq = Wire(new DataReq)
dataReq.src1Sel := io.decoder.bits.microOp.op1Src
dataReq.src1Tag := io.rat.bits.phyRs1
dataReq.src1Busy := io.rat.bits.rs1Busy
dataReq.src2Sel := io.decoder.bits.microOp.op2Src
dataReq.src2Tag := io.rat.bits.phyRs2
dataReq.src2Busy := io.rat.bits.rs2Busy
dataReq.imm := io.decoder.bits.imm
dataReq.pc := io.decoder.bits.pc
```

#### 2. 指令类型判断

根据 `MicroOp` 中的 opcode 判断指令类型，决定分派目标：

```scala
val isALU = io.decoder.bits.microOp.aluOp =/= ALUOp.NOP
val isBRU = io.decoder.bits.microOp.bruOp =/= BRUOp.NOP
val isLSU = io.decoder.bits.microOp.lsuOp =/= LSUOp.NOP
val isZicsr = io.decoder.bits.microOp.zicsrOp =/= ZicsrOp.NOP
```

#### 3. 握手控制

与 Decoder 和 RAT 进行 Decoupled 握手：

```scala
// Ready 信号：当所有输出目标都 ready 时，才接受新指令
val needFlush = io.globalFlush || io.branchFlush
val aluReady = io.aluRS.ready || !isALU
val bruReady = io.bruRS.ready || !isBRU
val lsuReady = io.lsu.ready || !isLSU
val zicsrReady = io.zicsr.ready || !isZicsr
val downStreamReady = aluReady && bruReady && lsuReady && zicsrReady
val allReady = downStreamReady && !needFlush
io.decoder.ready := allReady
io.rat.ready := allReady
```

#### 4. 分派逻辑

根据指令类型，将数据包发送到对应的保留站：

```scala
// ALU 指令分派
io.aluRS.valid := io.decoder.valid && isALU && !needFlush
io.aluRS.bits.aluOp := io.decoder.bits.microOp.aluOp
io.aluRS.bits.data := dataReq
io.aluRS.bits.robId := io.decoder.bits.robId
io.aluRS.bits.phyRd := io.rat.bits.phyRd
io.aluRS.bits.branchMask := io.rat.bits.branchMask
io.aluRS.bits.exception := io.decoder.bits.exception

// BRU 指令分派
io.bruRS.valid := io.decoder.valid && isBRU && !needFlush
io.bruRS.bits.bruOp := io.decoder.bits.microOp.bruOp
io.bruRS.bits.data := dataReq
io.bruRS.bits.robId := io.decoder.bits.robId
io.bruRS.bits.phyRd := io.rat.bits.phyRd
io.bruRS.bits.snapshotId := io.rat.bits.snapshotId
io.bruRS.bits.branchMask := io.rat.bits.branchMask
io.bruRS.bits.prediction := io.decoder.bits.prediction
io.bruRS.bits.exception := io.decoder.bits.exception

// LSU 指令分派
io.lsu.valid := io.decoder.valid && isLSU && !io.globalFlush && !io.branchFlush
// ... LSU 相关字段赋值

// Zicsr 指令分派
io.zicsr.valid := io.decoder.valid && isZicsr && !io.globalFlush && !io.branchFlush
// ... Zicsr 相关字段赋值
```

#### 5. 冲刷处理

当收到冲刷信号时，停止接收新指令，同时所有 Valid 拉低：

## AluRS 模块

### 职责

AluRS 是 ALU 指令的保留站，主要负责以下核心功能：

1. **指令池维护**：维护一个 Waiting Pool（等待池），存储等待 ALU 执行的指令。

2. **CDB 监听与唤醒**：监听 CDB 广播的结果，当 `ResultReg` 与 RS 中指令的 `srcTag` 匹配时，将对应操作数置为 valid。

3. **指令发射**：使用 PriorityEncoder 选择第一条就绪的指令，当该指令的所有操作数都 valid，且 ALU ready 时，将该指令发射到 ALU 执行。

4. **PRF 读取请求**：发射指令时，向 PRF 发送源寄存器读取请求。

5. **冲刷处理**：响应来自 ROB 的全局冲刷（GlobalFlush）和来自 BRU 的分支冲刷（BranchFlush）信号。

### 接口定义

#### 输入接口

| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `req` | Dispatcher | `Flipped(Decoupled(new AluRSDispatch))` | 来自 Dispatcher 的 ALU 指令入队请求 |
| `cdb` | CDB | `PhyTag` | 来自 CDB 的结果广播 |
| `globalFlush` | ROB | `Bool` | 全局冲刷信号，高电平有效 |
| `branchFlush` | BRU | `Bool` | 分支冲刷信号，高电平有效 |
| `killMask` | BRU | `SnapshotMask` | 需清除的分支掩码 |

#### 输出接口

| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `prfRead` | PRF | `Decoupled(new PrfReadPacket)` | 向 PRF 发送源寄存器读取请求 |
| `aluReq` | ALU | `Decoupled(new AluReq)` | 向 ALU 发送执行请求 |

**说明**：`PrfReadPacket` 和 `AluReq` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class PrfReadPacket extends Bundle with CPUConfig {
  val raddr1 = PhyTag
  val raddr2 = PhyTag
}

class AluReq extends Bundle with CPUConfig {
  val aluOp = ALUOp()
  val meta = new IssueMetaPacket
  val data = new IssueDataPacket
}

class IssueMetaPacket extends Bundle with CPUConfig {
  val robId    = RobTag
  val phyRd    = PhyTag
  val exception = new Exception
}

class IssueDataPacket extends Bundle with CPUConfig {
  val src1Sel = Src1Sel()
  val src2Sel = Src2Sel()
  val imm     = DataW
  val pc      = AddrW
}
```

### 内部逻辑

#### 0. Entry 结构体定义

在模块内部定义包含 busy 位的 Entry 结构体：

```scala
class AluRSEntry extends Bundle with CPUConfig {
  val busy = Bool()
  val aluOp = ALUOp()
  val data = new DataReq
  val robId = RobTag
  val phyRd = PhyTag
  val branchMask = SnapshotMask
  val exception = new Exception
}
```

#### 1. Waiting Pool 定义

使用 Vec 定义一个固定大小的 Waiting Pool（假设大小为 8），复位时所有 busy 位为 0：

```scala
val RS_SIZE = 8
val rsEntries = RegInit(VecInit(Seq.fill(RS_SIZE)(0.U.asTypeOf(new AluRSEntry))))

// 统计空闲槽位
val freeEntries = rsEntries.map(!_.busy)
val hasFree = freeEntries.reduce(_ || _)
val freeIdx = PriorityEncoder(freeEntries)
val needFlush = !io.globalFlush && !io.branchFlush
```

#### 2. 入队逻辑

当 `req.fire` 时，将新指令写入第一个空闲槽位：

```scala
io.req.ready := hasFree && needFlush

when (io.req.fire) {
  val newEntry = Wire(new AluRSEntry)
  newEntry.busy := true.B
  newEntry.aluOp := io.req.bits.aluOp
  newEntry.data := io.req.bits.data
  newEntry.robId := io.req.bits.robId
  newEntry.phyRd := io.req.bits.phyRd
  newEntry.branchMask := io.req.bits.branchMask
  newEntry.exception := io.req.bits.exception
  rsEntries(freeIdx) := newEntry
}
```

#### 3. CDB 监听与唤醒

监听 CDB 广播，更新池中指令的操作数状态：

```scala
// 遍历所有 RS 条目
for (i <- 0 until RS_SIZE) {
  val entry = rsEntries(i)

  // 检查 src1
  when (io.cdb.valid && io.cdb.bits === entry.data.src1Tag) {
    entry.data.src1Busy := false.B
  }

  // 检查 src2
  when (io.cdb.valid && io.cdb.bits === entry.data.src2Tag) {
    entry.data.src2Busy := false.B
  }
}
```

#### 4. 指令就绪判断

计算所有条目的就绪状态：

```scala
// 计算每个条目的就绪状态
val readyEntries = VecInit(rsEntries.map { entry =>
  val src1Ready = !entry.data.src1Busy || (entry.data.src1Sel =/= Src1Sel.REG)
  val src2Ready = !entry.data.src2Busy || (entry.data.src2Sel =/= Src2Sel.REG)
  entry.busy && src1Ready && src2Ready
})

val hasReady = readyEntries.reduce(_ || _)
val readyIdx = PriorityEncoder(readyEntries)
val canIssue = hasReady && needFlush
```

#### 5. 发射逻辑

当有就绪指令且未被冲刷时，发射到 ALU：

```scala
val selectedEntry = rsEntries(readyIdx)

// 向 PRF 发送读取请求
io.prfRead.valid := canIssue
io.prfRead.bits.raddr1 := selectedEntry.data.src1Tag
io.prfRead.bits.raddr2 := selectedEntry.data.src2Tag

// 向 ALU 发送执行请求
io.aluReq.valid := canIssue
io.aluReq.bits.aluOp := selectedEntry.aluOp
io.aluReq.bits.meta.robId := selectedEntry.robId
io.aluReq.bits.meta.phyRd := selectedEntry.phyRd
io.aluReq.bits.meta.exception := selectedEntry.exception
io.aluReq.bits.data.src1Sel := selectedEntry.data.src1Sel
io.aluReq.bits.data.src2Sel := selectedEntry.data.src2Sel
io.aluReq.bits.data.imm := selectedEntry.data.imm
io.aluReq.bits.data.pc := selectedEntry.data.pc

// 发射成功后，清除对应条目的 busy 位
when (io.aluReq.fire) {
  rsEntries(readyIdx).busy := false.B
}
```

#### 6. 冲刷处理

无论哪一种冲刷，本周期都不接受新指令也不发送就绪指令。

**全局冲刷**：清空所有指令：

```scala
when (io.globalFlush) {
  for (i <- 0 until RS_SIZE) {
    rsEntries(i).busy := false.B
  }
}
```

**分支冲刷**：根据 `branchMask` 清除依赖该分支的指令：

```scala
when (io.branchFlush) {
  for (i <- 0 until RS_SIZE) {
    when ((rsEntries(i).branchMask & io.killMask) =/= 0.U) {
      rsEntries(i).busy := false.B
    }
  }
}
```

## BruRS 模块

### 职责

BruRS 是 BRU 指令的保留站，主要负责以下核心功能：

1. **指令池维护**：维护一个 Waiting Pool（等待池），存储等待 BRU 执行的指令。

2. **CDB 监听与唤醒**：监听 CDB 广播的结果，当 `ResultReg` 与 RS 中指令的 `srcTag` 匹配时，将对应操作数置为 valid。

3. **指令发射**：使用 PriorityEncoder 选择第一条就绪的指令，当该指令的所有操作数都 valid，且 BRU ready 时，将该指令发射到 BRU 执行。

4. **PRF 读取请求**：发射指令时，向 PRF 发送源寄存器读取请求。

5. **冲刷处理**：响应来自 ROB 的全局冲刷（GlobalFlush）和来自 BRU 的分支冲刷（BranchFlush）信号。

### 接口定义

#### 输入接口

| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `enq` | Dispatcher | `Flipped(Decoupled(new BruRSDispatch))` | 来自 Dispatcher 的 BRU 指令入队请求 |
| `cdb` | CDB | `PhyTag` | 来自 CDB 的结果广播 |
| `globalFlush` | ROB | `Bool` | 全局冲刷信号，高电平有效 |
| `branchFlush` | BRU | `Bool` | 分支冲刷信号，高电平有效 |
| `killMask` | BRU | `SnapshotMask` | 需清除的分支掩码 |

#### 输出接口

| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `prfRead` | PRF | `Decoupled(new PrfReadPacket)` | 向 PRF 发送源寄存器读取请求 |
| `bruReq` | BRU | `Decoupled(new BruReq)` | 向 BRU 发送执行请求 |

**说明**：`BruReq` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class BruReq extends Bundle with CPUConfig {
  val bruOp = BRUOp()
  val prediction = new Prediction
  val meta = new IssueMetaPacket
  val data = new IssueDataPacket
}
```

### 内部逻辑

#### 0. Entry 结构体定义

在模块内部定义包含 busy 位的 Entry 结构体：

```scala
class BruRSEntry extends Bundle with CPUConfig {
  val busy = Bool()
  val bruOp = BRUOp()
  val data = new DataReq
  val robId = RobTag
  val phyRd = PhyTag
  val snapshotId = UInt(SnapshotIdWidth.W)
  val branchMask = SnapshotMask
  val prediction = new Prediction
  val exception = new Exception
}
```

#### 1. Waiting Pool 定义

使用 Vec 定义一个固定大小的 Waiting Pool（假设大小为 4，在途分支指令数 $\leq$ snapshots 个数），复位时所有 busy 位为 0：

```scala
val RS_SIZE = 4
val rsEntries = RegInit(VecInit(Seq.fill(RS_SIZE)(0.U.asTypeOf(new BruRSEntry))))

// 统计空闲槽位
val freeEntries = rsEntries.map(!_.busy)
val hasFree = freeEntries.reduce(_ || _)
val freeIdx = PriorityEncoder(freeEntries)
```

#### 2. 入队逻辑

当 `enq.fire` 时，将新指令写入第一个空闲槽位：

```scala
io.enq.ready := hasFree && !io.globalFlush && !io.branchFlush

when (io.enq.fire) {
  val newEntry = Wire(new BruRSEntry)
  newEntry.busy := true.B
  newEntry.bruOp := io.enq.bits.bruOp
  newEntry.data := io.enq.bits.data
  newEntry.robId := io.enq.bits.robId
  newEntry.phyRd := io.enq.bits.phyRd
  newEntry.snapshotId := io.enq.bits.snapshotId
  newEntry.branchMask := io.enq.bits.branchMask
  newEntry.prediction := io.enq.bits.prediction
  newEntry.exception := io.enq.bits.exception
  rsEntries(freeIdx) := newEntry
}
```

#### 3. CDB 监听与唤醒

监听 CDB 广播，更新池中指令的操作数状态：

```scala
// 遍历所有 RS 条目
for (i <- 0 until RS_SIZE) {
  val entry = rsEntries(i)

  // 检查 src1
  when (io.cdb.valid && io.cdb.bits === entry.data.src1Tag) {
    entry.data.src1Busy := false.B
  }

  // 检查 src2
  when (io.cdb.valid && io.cdb.bits === entry.data.src2Tag) {
    entry.data.src2Busy := false.B
  }
}
```

#### 4. 指令就绪判断

计算所有条目的就绪状态：

```scala
// 计算每个条目的就绪状态
val readyEntries = VecInit(rsEntries.map { entry =>
  val src1Ready = !entry.data.src1Busy || (entry.data.src1Sel =/= Src1Sel.REG)
  val src2Ready = !entry.data.src2Busy || (entry.data.src2Sel =/= Src2Sel.REG)
  entry.busy && src1Ready && src2Ready
})

val hasReady = readyEntries.reduce(_ || _)
val readyIdx = PriorityEncoder(readyEntries)
val canIssue = hasReady && io.bruReady && !io.globalFlush && !io.branchFlush
```

#### 5. 发射逻辑

当有就绪指令时，发射到 BRU：

```scala
val selectedEntry = rsEntries(readyIdx)

// 向 PRF 发送读取请求
io.prfRead.valid := canIssue
io.prfRead.bits.raddr1 := selectedEntry.data.src1Tag
io.prfRead.bits.raddr2 := selectedEntry.data.src2Tag

// 向 BRU 发送执行请求
io.bruReq.valid := canIssue
io.bruReq.bits.bruOp := selectedEntry.bruOp
io.bruReq.bits.prediction := selectedEntry.prediction
io.bruReq.bits.meta.robId := selectedEntry.robId
io.bruReq.bits.meta.phyRd := selectedEntry.phyRd
io.bruReq.bits.meta.exception := selectedEntry.exception
io.bruReq.bits.data.src1Sel := selectedEntry.data.src1Sel
io.bruReq.bits.data.src2Sel := selectedEntry.data.src2Sel
io.bruReq.bits.data.imm := selectedEntry.data.imm
io.bruReq.bits.data.pc := selectedEntry.data.pc

// 发射成功后，清除对应条目的 busy 位
when (io.bruReq.fire) {
  rsEntries(readyIdx).busy := false.B
}
```

#### 6. 冲刷处理

**全局冲刷**：清空所有指令：

```scala
when (io.globalFlush) {
  for (i <- 0 until RS_SIZE) {
    rsEntries(i).busy := false.B
  }
}
```

**分支冲刷**：根据 `branchMask` 清除依赖该分支的指令：

```scala
when (io.branchFlush) {
  for (i <- 0 until RS_SIZE) {
    when ((rsEntries(i).branchMask & io.killMask) =/= 0.U) {
      rsEntries(i).busy := false.B
    }
  }
}
```
