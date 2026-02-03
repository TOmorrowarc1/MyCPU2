# RAT (Register Alias Table) 模块设计文档

## 1. 模块概述

RAT (Register Alias Table) 是乱序执行处理器移除 WAR, WAW 冒险的数据流核心组件，负责实现显式寄存器重命名。它维护架构寄存器到物理寄存器的映射关系，支持分支预测恢复和指令提交时的物理寄存器回收。

### 1.1 设计目标

- **消除 WAW/WAR 冒险**：通过寄存器重命名，将架构寄存器映射到不同的物理寄存器，消除写后写和写后读冒险
- **支持分支预测恢复**：通过快照机制，在分支预测失败时快速恢复到分支前的映射状态
- **高效资源管理**：维护物理寄存器池 (Free List)，动态分配和回收物理寄存器

## 2. 职责

1. **寄存器重命名**：为每条指令的目标寄存器分配新的物理寄存器，并更新映射关系
2. **快照管理**：为分支指令创建快照，保存当前映射状态
3. **恢复与冲刷**：处理分支预测失败和全局冲刷，恢复映射状态
4. **资源回收与管理**：回收已提交指令的旧物理寄存器到 Free List；每周期更新 Ready List 以反映物理寄存器的数据可用状态。

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
| **CDB 广播**              |         |                |                               |
| `cdb.valid`               | CBD     | `Bool`         | CDB 广播有效信号              |
| `cdb.bits.phyRd`          | CBD     | `PhyTag`       | 广播的物理目标寄存器          |

### 3.2 输出接口

| 信号名                      | 目标       | 类型           | 描述                                             |
| --------------------------- | ---------- | -------------- | ------------------------------------------------ |
| **Decoder 响应**            |            |                |                                                  |
| `renameReq.ready`           | Decoder    | `Bool`         | 重命名请求就绪信号                               |
| `renameRes.valid`           | Decoder/RS | `Bool`         | 重命名结果有效信号                               |
| `renameRes.bits.phyRs1`     | Decoder/RS | `PhyTag`       | 源寄存器 1 的物理寄存器号                        |                          |
| `renameRes.bits.rs1Ready`   | Decoder/RS | `Bool`         | 源寄存器 1 数据是否 ready (用于 CDB 旁路)         |
| `renameRes.bits.phyRs2`     | Decoder/RS | `PhyTag`       | 源寄存器 2 的物理寄存器号                        |                           |
| `renameRes.bits.rs2Ready`   | Decoder/RS | `Bool`         | 源寄存器 2 数据是否 ready (用于 CDB 旁路)         |
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

// Frontend Ready List: 位矢量表示 128 个物理寄存器的 ready 状态
// 1 表示数据已准备好，0 表示数据未准备好
val frontendReadyList = RegInit("h00000000".U)

// Snapshot: 保存分支指令时的 RAT、Free List 和 Ready List 状态
class Snapshot extends Bundle with CPUConfig {
  val rat = Vec(32, PhyTag)           // Frontend RAT 的快照
  val freeList = UInt(128.W)          // Frontend Free List 的快照
  val readyList = UInt(128.W)         // Frontend Ready List 的快照
  val snapshotsBusy = UInt(4.W)      // 记录拍下快照时刻的依赖关系
}

// Snapshots: 4 个快照
val snapshots = Reg(Vec(4, new Snapshot))
// 快照忙位，同时作为 BranchMask（独热码）
val snapshotsBusy = RegInit(0.U(4.W))
val snapshotsBusyAfterAlloc = WireDefault(snapshotsBusy)
val snapshotsBusyAfterCommit = WireDefault(snapshotsBusy)

// 中间组合逻辑变量：区分 Alloc 和 Commit 阶段
val retirementFreeListAfterCommit = WireDefault(retirementFreeList)
val retirementRatAfterCommit = WireDefault(retirementRat)
val frontendFreeListAfterAlloc = WireDefault(frontendFreeList)
val frontendFreeListAfterCommit = WireDefault(frontendFreeListAfterAlloc)
val frontendReadyListAfterAlloc = WireDefault(frontendReadyList)
val frontendReadyListAfterBroadcast = WireDefault(frontendReadyListAfterAlloc)
val snapshotsFreeListsAfterCommit = Wire(Vec(4, UInt(128.W)))
val snapshotsReadyListsAfterBroadcast = Wire(Vec(4, UInt(128.W)))

for(i <- 0 until 4) {
  snapshotsFreeListsAfterCommit(i) := snapshots(i).freeList
  snapshotsReadyListsAfterBroadcast(i) := snapshots(i).readyList
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

// 查找源寄存器的物理寄存器号和 busy/ready 状态
val phyRs1 = frontendRat(rs1)
val phyRs2 = frontendRat(rs2)
val rs1Ready = frontendReadyList(phyRs1)
val rs2Ready = frontendReadyList(phyRs2)
// CDB bypass forwarding
when (io.cdb.valid) {
  when (io.cdb.bits.phyRd === phyRs1 && phyRs1 =/= 0.U) {
    rs1Ready := true.B
  }
  when (io.cdb.bits.phyRd === phyRs2 && phyRs2 =/= 0.U) {
    rs2Ready := true.B
  }
}
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
}.otherwise {
  allocPhyRd := 0.U // 如果没有空闲，使用 x0（毕竟不会 valid）
}
```

#### 4.2.3 更新 Frontend RAT

```scala
// 更新 Frontend RAT
when (renameReq.fire && rd =/= 0.U) {
  frontendRat(rd) := allocPhyRd
}
```

#### 4.2.4 更新 Free List 与 Ready List

```scala
// 预计算分配后的 FreeList 与 ReadyList
when(renameReq.fire && rd =/= 0.U) {
  frontendFreeListAfterAlloc := frontendFreeList | (1.U(128.W) << allocPhyRd)
  frontendReadyListAfterAlloc := frontendReadyList & ~(1.U(128.W) << allocPhyRd)
}
```

#### 4.2.5 输出重命名结果

```scala
// 输出重命名结果
io.renameRes.valid := allocSuccess && renameReq.valid
io.renameRes.bits.phyRs1 := phyRs1
io.renameRes.bits.rs1Ready := rs1Ready
io.renameRes.bits.phyRs2 := phyRs2
io.renameRes.bits.rs2Ready := rs2Ready
io.renameRes.bits.phyRd := allocPhyRd
io.renameRes.bits.branchMask := currentBranchMask
```

### 4.3 分支快照逻辑

#### 4.3.1 快照分配

```scala
// 独热码快照分配逻辑
val allocSnapshotOH = PriorityEncoderOH(~snapshotsBusy) // 找出第一个 0 位，返回独热码
val hasSnapshotFree = (~snapshotsBusy).orR
val currentBranchMask = snapshotsBusy

// 拍摄快照
when(renameReq.fire && isBranch && hasSnapshotFree) {
  for (i <- 0 until 4) {
    when(allocSnapshotOH(i)) {
      snapshots(i).rat := frontendRat
      snapshots(i).freeList := frontendFreeListAfterCommit
      snapshots(i).readyList := frontendReadyListAfterBroadcast
      snapshots(i).snapshotsBusy := snapshotsBusy // 记录当前已存在的快照依赖
    }
  }
  snapshotsBusyAfterAlloc := snapshotsBusy | allocSnapshotOH
}
```

#### 4.3.2 输出快照 ID

```scala
// 输出快照 ID（独热码）
io.renameRes.bits.snapshotId := Mux(isBranch, allocSnapshotOH, 0.U)

// 判定 RAT 是否 ready
io.renameReq.ready := hasFree && hasSnapshotFree
```

### 4.4 回收与冲刷逻辑

* 每次 ROB 提交时，对应旧物理寄存器被回收，所有 Free list（包括 Snapshots 中）中对应位都应当被清空。
* 每次 CDB 提交时，对应物理寄存器在包括 Snapshots 中所有 Ready lists 中被标记为 ready。
* 冲刷时 branchFlush 不影响当前提交更新，但是 globalFlush 会覆盖当前更新，即 branchFlush 时先提交后冲刷，但是 globalFlush 直接覆盖。

#### 4.4.1 CDB Ready List 更新

```scala
// CDB 广播: 将 phyRd 标记为 ready，更新所有 Ready List
when(io.cdb.valid && io.cdb.bits.phyRd =/= 0.U) {
  val mask = (1.U(128.W) << io.cdb.bits.phyRd)
  // 更新 Frontend Ready List（基于 AfterAlloc）
  frontendReadyListAfterBroadcast := frontendReadyListAfterAlloc | mask
  // 更新所有快照的 Ready List
  for(i <- 0 until 4) {
    snapshotsReadyListsAfterBroadcast(i) := snapshots(i).readyList | mask
  }
}
```

#### 4.4.2 ROB 提交回收

```scala
// ROB 提交: 将 PreRd 回收到 Free List，更新 Retirement RAT
when(io.commit.valid && io.commit.bits.preRd =/= 0.U) {
  val mask = ~(1.U(128.W) << io.commit.bits.preRd)
  for(i <- 0 until 4) {
    snapshotsFreeListsAfterCommit(i) := snapshots(i).freeList & mask
  }
  frontendFreeListAfterCommit := frontendFreeListAfterAlloc & mask
  retirementFreeListsAfterCommit := retirementFreeList & mask
}

when(io.commit.valid && io.commit.bits.archRd =/= 0.U) {
  retirementRatAfterCommit(io.commit.bits.archRd) := io.commit.bits.phyRd
}
```

#### 4.4.2 Global Flush 与 Branch Flush/分支回收

```scala
when(io.globalFlush) {
  // 恢复 Frontend RAT
  frontendRat := retirementRat
  // 恢复 Free List
  frontendFreeList := retirementFreeList
  // 清空所有快照
  snapshotsBusy := 0.U
}.otherwise {
  // 默认行为：接受提交更新
  retirementRat := retirementRatAfterCommit
  retirementFreeList := retirementFreeListAfterCommit
  for (i <- 0 until 4) {
    snapshots(i).freeList := snapshotsFreeListsAfterCommit(i)
    snapshots(i).readyList := snapshotsReadyListsAfterBroadcast(i)
  }
  // Branch Flush 与 Branch 回收
  when(io.branchFlush) {
    // 1. 分支预测失败恢复
    // 使用 Mux1H 快速选择独热码对应的快照状态
    frontendRat := Mux1H(io.snapshotId, snapshots.map(_.rat))
    frontendFreeList := Mux1H(io.snapshotId,snapshotsFreeListsAfterCommit)
    frontendReadyList := Mux1H(io.snapshotId, snapshotsReadyListsAfterBroadcast)
    // 恢复到该分支点时的快照占用状态（即该分支之前的快照仍然有效）
    snapshotsBusy := Mux1H(io.snapshotId, snapshots.map(_.snapshotsBusy))
  }.otherwise {
    // 2. 正常运行逻辑
    // 只有在无 Flush 的情况下才从分配上更新 Frontend RAT
    when(renameReq.fire && rd =/= 0.U) { frontendRat(rd) := allocPhyRd }
    frontendFreeList := frontendFreeListAfterCommit
    frontendReadyList := frontendReadyListAfterBroadcast
    when(io.branchMask =/= 0.U) {
      snapshotsBusyAfterCommit := snapshotsBusyAfterAlloc & ~io.snapshotId
    }
    snapshotsBusy := snapshotsBusyAfterCommit
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
- 测试源寄存器 ready 状态的正确性
- 测试目标寄存器分配的正确性

### 5.2 分支快照测试

- 测试分支指令的快照分配
- 测试快照内容的正确性（包括 Ready List）
- 测试分支掩码的更新

### 5.3 分支预测失败恢复测试

- 测试快照恢复的正确性（包括 Ready List 恢复）
- 测试分支掩码的更新
- 测试不再需要的快照回收

### 5.4 Global Flush 测试

- 测试 Frontend RAT 恢复到 Retirement RAT
- 测试 Free List 的恢复
- 测试 Ready List 的清空
- 测试所有快照的清空

### 5.5 资源回收测试

- 测试 ROB 提交时的物理寄存器回收
- 测试分支预测成功时的快照回收
- 测试 Free List 的正确更新

### 5.6 CDB Ready List 更新测试

- 测试 CDB 广播时 Ready List 的正确更新
- 测试所有快照的 Ready List 同步更新
- 测试 Ready List 与 Free List 的独立性
