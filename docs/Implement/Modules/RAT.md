# RAT (Register Alias Table) 模块设计文档

## 1. 模块概述

RAT (Register Alias Table) 是乱序执行处理器移除 WAR, WAW 冒险的数据流核心组件，负责实现显式寄存器重命名。它维护架构寄存器到物理寄存器的映射关系，支持分支预测恢复和指令提交时的物理寄存器回收。

### 1.1 设计目标

- **消除 WAW/WAR 冒险**：通过寄存器重命名，将架构寄存器映射到不同的物理寄存器，消除写后写和写后读冒险
- **支持分支预测恢复**：通过快照机制，在分支预测失败时快速恢复到分支前的映射状态
- **高效资源管理**：维护物理寄存器池 (Free List)，动态分配和回收物理寄存器

### 1.2 关键特性

- **双 RAT 结构**：Frontend RAT (推测状态) 和 Retirement RAT (提交状态)
- **快照机制**：最多支持 4 个分支快照，用于分支预测失败时的快速恢复
- **双 Free List**：Frontend Free List 和 Retirement Free List，支持推测和提交的独立管理
- **位矢量表示**：使用位矢量表示物理寄存器的 busy 状态，便于快速查找和分配

## 2. 职责

1. **寄存器重命名**：为每条指令的目标寄存器分配新的物理寄存器，并更新映射关系
2. **快照管理**：为分支指令创建快照，保存当前映射状态
3. **恢复与冲刷**：处理分支预测失败和全局冲刷，恢复映射状态
4. **资源回收**：回收已提交指令的旧物理寄存器到 Free List

## 3. 接口定义

### 3.1 输入接口

| 信号名                    | 来源    | 类型           | 描述                          |
| ------------------------- | ------- | -------------- | ----------------------------- |
| **Decoder 请求**          |         |                |                               |
| `renameReq.valid`         | Decoder | `Bool`         | 重命名请求有效信号            |
| `renameReq.bits.rs1`      | Decoder | `ArchTag`      | 源寄存器 1 (架构寄存器号)     |
| `renameReq.bits.rs2`      | Decoder | `ArchTag`      | 源寄存器 2 (架构寄存器号)     |
| `renameReq.bits.rd`       | Decoder | `ArchTag`      | 目标寄存器 (架构寄存器号)     |
| `renameReq.bits.isBranch` | Decoder | `Bool`         | 是否为分支指令 (需要分配快照) |
| **ROB 提交信息**          |         |                |                               |
| `commit.valid`            | ROB     | `Bool`         | 提交请求有效信号              |
| `commit.bits.archRd`      | ROB     | `ArchTag`      | 提交的架构目标寄存器          |
| `commit.bits.phyRd`       | ROB     | `PhyTag`       | 提交的物理目标寄存器          |
| `commit.bits.preRd`       | ROB     | `PhyTag`       | 提交的旧物理寄存器 (需回收)   |
| `globalFlush`             | ROB     | `Bool`         | 全局冲刷信号                  |
| **BRU 分支决议**          |         |                |                               |
| `branchFlush`             | BRU     | `Bool`         | 分支预测失败信号              |
| `snapshotId`              | BRU     | `SnapshotId`   | 需要恢复的快照 ID             |
| `branchMask`              | BRU     | `SnapshotMask` | 分支掩码 (用于回收快照)       |

### 3.2 输出接口

| 信号名                      | 目标       | 类型           | 描述                                             |
| --------------------------- | ---------- | -------------- | ------------------------------------------------ |
| **Decoder 响应**            |            |                |                                                  |
| `renameReq.ready`           | Decoder    | `Bool`         | 重命名请求就绪信号                               |
| `renameRes.valid`           | Decoder/RS | `Bool`         | 重命名结果有效信号                               |
| `renameRes.bits.phyRs1`     | Decoder/RS | `PhyTag`       | 源寄存器 1 的物理寄存器号                        |
| `renameRes.bits.rs1Busy`    | Decoder/RS | `Bool`         | 源寄存器 1 是否 busy (需监听 CDB，实现 CDB 旁路) |
| `renameRes.bits.phyRs2`     | Decoder/RS | `PhyTag`       | 源寄存器 2 的物理寄存器号                        |
| `renameRes.bits.rs2Busy`    | Decoder/RS | `Bool`         | 源寄存器 2 是否 busy                             |
| `renameRes.bits.phyRd`      | Decoder/RS | `PhyTag`       | 目标寄存器的物理寄存器号                         |
| `renameRes.bits.snapshotId` | Decoder/RS | `SnapshotId`   | 分配的快照 ID (分支指令专用)                     |
| `renameRes.bits.branchMask` | Decoder/RS | `SnapshotMask` | 当前依赖的分支掩码                               |
| **ROB 数据包**              |            |                |                                                  |
| `robData.valid`             | ROB        | `Bool`         | ROB 数据包有效信号                               |
| `robData.bits.archRd`       | ROB        | `ArchTag`      | 架构目标寄存器                                   |
| `robData.bits.phyRd`        | ROB        | `PhyTag`       | 物理目标寄存器                                   |
| `robData.bits.preRd`        | ROB        | `PhyTag`       | 旧物理寄存器                                     |
| `robData.bits.branchMask`   | ROB        | `SnapshotMask` | 分支掩码                                         |

## 4. 内部实现

### 4.1 维护信息

```scala
// Frontend RAT: 32 * PhyTag 大小的映射表，表示推测状态，供重命名使用
// 初始化为全 0
val frontendRat = RegInit(VecInit(Seq.fill(32)(0.U(PhyRagIdWidth.W))))

// Retirement RAT: 提交状态，用于全局冲刷恢复
val retirementRat = RegInit(VecInit(Seq.fill(32)(0.U(PhyRagIdWidth.W))))

// Frontend Free List: 位矢量表示 128 个物理寄存器的 busy 状态
// 初始化: 前 32 个物理寄存器为 busy，其余为 free
val frontendFreeList = RegInit("hFFFFFFFF".U)

// Retirement Free List: ROB 提交的正被占据的物理寄存器对应 busy 位矢量
val retirementFreeList = RegInit("hFFFFFFFF".U)

// Snapshot: 保存分支指令时的 RAT 和 Free List 状态
class Snapshot extends Bundle with CPUConfig {
  val rat = Vec(32, PhyTag)           // Frontend RAT 的快照
  val freeList = UInt(128.W)          // Frontend Free List 的快照
  val branchMask = UInt(4.W)          // 分支掩码快照
}

// Snapshots: 4 个快照
val snapshots = Reg(Vec(4, new Snapshot))
// 快照忙位，同时作为 BranchMask
val snapshotsBusy = RegInit(0.U(4.W))  

val nextRetirementFreeLists = WireDefault(retirementFreeList)
val nextFrontendFreeList = WireDefault(frontendFreeList)
val nextSnapshotsFreeLists = Wire(Vec(4, UInt(128.W)))
for(i <- 0 until 4) {
  nextSnapshotsFreeLists(i) := snapshots(i).freeList
}
```

### 4.2 重命名逻辑

#### 4.2.1 输入处理

```scala
// 接收 Decoder 请求
val renameReq = io.renameReq
val rs1 = renameReq.bits.rs1
val rs2 = renameReq.bits.rs2
val rd = renameReq.bits.rd
val isBranch = renameReq.bits.isBranch

// 查找源寄存器的物理寄存器号和 busy 状态
val phyRs1 = frontendRat(rs1)
val phyRs2 = frontendRat(rs2)
val rs1Busy = frontendFreeList(phyRs1)
val rs2Busy = frontendFreeList(phyRs2)
```

#### 4.2.2 新物理寄存器分配

```scala
// 分配新物理寄存器
val allocPhyRd = WireDefault(0.U(PhyRegIdWidth.W))
val allocSuccess = WireDefault(false.B)

// 查找第一个 free 的物理寄存器 (最低位的 0)
val freeIndex = PriorityEncoder(~frontendFreeList)
val hasFree = (~frontendFreeList).orR

// 如果 rd != x0 且有 free 物理寄存器，则分配
when (rd =/= 0.U && hasFree) {
  allocPhyRd := freeIndex
  allocSuccess := true.B
}.otherwise {
  allocPhyRd := 0.U // 如果没有空闲，使用 x0（毕竟不会 valid）
  allocSuccess := (rd === 0.U)  // x0 总是成功
}
```

#### 4.2.3 更新 Frontend RAT

```scala
// 更新 Frontend RAT
when (renameReq.fire && rd =/= 0.U && allocSuccess) {
  frontendRat(rd) := allocPhyRd
}
```

#### 4.2.4 更新 Free List

```scala
// 更新 Free List
when (renameReq.fire && rd =/= 0.U && allocSuccess) {
  nextFrontendFreeList := frontendFreeList | (1.U(128.W) << allocPhyRd)
}
```

#### 4.2.5 输出重命名结果

```scala
// 输出重命名结果
io.renameRes.valid := allocSuccess && renameReq.valid
io.renameRes.bits.phyRs1 := phyRs1
io.renameRes.bits.rs1Busy := rs1Busy
io.renameRes.bits.phyRs2 := phyRs2
io.renameRes.bits.rs2Busy := rs2Busy
io.renameRes.bits.phyRd := allocPhyRd
io.renameRes.bits.branchMask := currentBranchMask
```

### 4.3 分支快照逻辑

#### 4.3.1 快照分配

```scala
val currentBranchMask = snapshotsBusy
val snapshotId = PriorityEncoder(~snapshotsBusy)
val hasSnapshotFree = (~snapshotsBusy).orR

// 更新快照
when (renameReq.fire && isBranch && hasSnapshotFree) {
  // 保存当前 Frontend RAT
  snapshots(snapshotId).rat := frontendRat
  // 保存当前 Free List
  snapshots(snapshotId).freeListBusy := freeListBusy
  // 保存当前分支掩码
  snapshots(snapshotId).branchMask := currentBranchMask
  // 标记快照有效，即更新当前分支掩码
  snapshotsBusy := snapshotsBusy | (1.U(4.W) << snapshotId)
}
```

#### 4.3.2 输出快照 ID

```scala
// 输出快照 ID
io.renameRes.bits.snapshotId := Mux(isBranch, snapshotId, 0.U)

// 判定 RAT 是否 ready
io.renameReq.ready := hasFree && hasSnapshotFree
```

### 4.4 回收与冲刷逻辑

* 每次 ROB 提交时，对应旧物理寄存器被回收，所有 Free list（包括 Snapshots 中）中对应位都应当被清空。
* 冲刷时 branchFlush 不影响当前提交更新，但是 globalFlush 会覆盖当前更新，即 branchFlush 时先提交后冲刷，但是 globalFlush 直接覆盖。

#### 4.4.1 ROB 提交回收

```scala
// ROB 提交: 将 PreRd 回收到 Free List，更新 Retirement RAT
when(io.commit.valid && io.commit.bits.preRd =/= 0.U) {
  val mask = ~(1.U(128.W) << io.commit.bits.preRd)
  for(i <- 0 until 4) {
    nextSnapshotsFreeLists(i) := snapshots(i).freeList & mask
  }
  nextFrontendFreeList := nextFrontendFreeList & mask
  nextRetirementFreeLists := nextRetirementFreeList & mask
}

val nextRetirementRat := WireDefault(retirementRat)
when (io.commit.valid) {
  val archRd = io.commit.bits.archRd
  val phyRd = io.commit.bits.phyRd  
  // 更新 Retirement RAT
  when (archRd =/= 0.U) {
    nextRetirementRat(archRd) := phyRd
  }
}
```

#### 4.4.2 Global Flush 与 Branch Flush/分支回收

```scala
// Global Flush: 直接将 Frontend RAT 覆盖为 Retirement RAT，回收所有 Snapshots，本周期 ROB 更新弃置不用。
when (io.globalFlush) {
  // 恢复 Frontend RAT
  frontendRat := retirementRat
  // 恢复 Free List
  frontendFreeList := retirementFreeList
  // 清空所有快照
  snapshotsBusy := 0.U
}
.otherwise{
  val snapshotId = io.snapshotId
  when(io.branchFlush){
    // 恢复 Frontend RAT
    frontendRat := snapshots(snapshotId).rat
    // 恢复 Free List，接受本周期 ROB 更新
    frontendFreeList := nextSnapshotsFreeLists(snapshotId)
    // 恢复分支掩码
    snapshotsBusy := snapshots(snapshotId).snapshotsBusy
  }.elsewhen(snapshotId =/= 0.U){
    // 清空该快照
    snapshotsBusy := snapshotsBusy & ~(1.U(128.W) << snapshotId)
    // 更新分支掩码
    currentBranchMask := currentBranchMask & ~(1.U(128.W) << snapshotId)
  }
  // ROB 提交更新采用
  retirementRat := nextretirementRat
  retirementFreeList := nextRetirementFreeLists
  for(i <- 0 until 4) {
    snapshots(i).freeList := nextSnapshotsFreeLists(i)
  }
}
```

### 4.5 ROB 数据包生成

```scala
// 生成 ROB 数据包
io.robData.valid := renameReq.fire
io.robData.bits.archRd := rd
io.robData.bits.phyRd := allocPhyRd
io.robData.bits.preRd := frontendRat(rd)  // 旧的物理寄存器
io.robData.bits.branchMask := currentBranchMask
```

## 5. 测试用例

### 5.1 正常重命名测试

- 测试不同类型的指令重命名
- 测试源寄存器 busy 状态的正确性
- 测试目标寄存器分配的正确性

### 5.2 分支快照测试

- 测试分支指令的快照分配
- 测试快照内容的正确性
- 测试分支掩码的更新

### 5.3 分支预测失败恢复测试

- 测试快照恢复的正确性
- 测试分支掩码的更新
- 测试不再需要的快照回收

### 5.4 Global Flush 测试

- 测试 Frontend RAT 恢复到 Retirement RAT
- 测试 Free List 的恢复
- 测试所有快照的清空

### 5.5 资源回收测试

- 测试 ROB 提交时的物理寄存器回收
- 测试分支预测成功时的快照回收
- 测试 Free List 的正确更新
