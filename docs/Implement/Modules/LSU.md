# LSU (加载存储单元) 设计文档

## 1. 概述

LSU（Load Store Unit，加载存储单元）是 CPU 中负责处理所有内存访问指令的执行单元。作为后端执行流水线的一部分，LSU 管理加载队列（LQ）和存储队列（SQ），通过 AGU（地址生成单元）和 PMPChecker（物理内存保护检查器）完成地址计算和权限检查，并通过 D-Cache 执行实际的内存访问操作。

### 1.1 目的和作用

LSU 在 CPU 中发挥以下关键作用：

- **内存访问管理**：管理 Load/Store 指令的发射与完成，确保乱序执行环境下的内存一致性与正确性。
- **队列管理**：维护加载队列（LQ）和存储队列（SQ），分别跟踪在途的 Load 和 Store 指令。
- **地址生成**：通过 AGU 子模块计算物理地址（PA = Base + Offset）。
- **权限检查**：通过 PMPChecker 子模块进行 PMP 权限检查，确保内存访问符合 RISC-V 规范。
- **Store-Load 转发**：实现 Store 到 Load 的数据转发机制，避免不必要的内存访问。
- **依赖追踪**：维护 Load 指令对 Store 指令的依赖关系，确保内存顺序一致性。
- **结果广播**：将 Load 指令的结果通过 CDB 广播到 RS、PRF 和 ROB。

LSU 是 Tomasulo 架构中的四个执行单元之一，与算术逻辑单元（ALU）、分支解决单元（BRU）和 Zicsr 指令单元（ZICSRU）并列。其在 CDB 仲裁中具有第二优先级（ZICSRU > LSU > BRU > ALU）。

## 2. 模块接口

### 2.1 输入接口

LSU 从 CPU 中的多个源接收输入，使用 [`LSUDispatch`](../../src/main/scala/cpu/Protocol.scala:492-501) 结构体：

#### 2.1.1 来自保留站（RS）

| 信号 | 类型 | 描述 |
|------|------|------|
| `opcode` | `LSUOp()` | 指定要执行的 LSU 操作的操作码（LOAD/STORE/NOP） |
| `memWidth` | `LSUWidth()` | 访存位宽（BYTE/HALF/WORD） |
| `memSign` | `LSUsign()` | 符号扩展标志（UNSIGNED/SIGNED） |
| `data` | `DataReq` | 数据包（包含操作数选择、操作数标签、就绪状态、立即数、PC） |
| `phyRd` | `ArchTag` | 目标寄存器（Load 指令） |
| `robId` | `RobTag` | 用于结果跟踪的重排序缓冲区条目标识符 |
| `branchMask` | `SnapshotMask` | 分支掩码（用于分支冲刷） |
| `privMode` | `PrivMode()` | 当前特权级（U/S/M） |

#### 2.1.2 来自物理寄存器文件（PRF）

| 信号 | 类型 | 描述 |
|------|------|------|
| `rdata1` | `DataW` | 从物理寄存器文件读取的第一个操作数（基地址） |
| `rdata2` | `DataW` | 从物理寄存器文件读取的第二个操作数（存储数据） |

#### 2.1.3 来自 D-Cache

| 信号 | 类型 | 描述 |
|------|------|------|
| `cacheRResp` | `LSUCacheRResp` | Cache 读响应（包含数据和上下文） |
| `cacheWResp` | `LSUCacheWResp` | Cache 写响应（包含上下文） |

#### 2.1.4 来自 ROB

| 信号 | 类型 | 描述 |
|------|------|------|
| `storeEnable` | `Bool()` | Store 指令退休信号 |
| `currentEpoch` | `EpochW` | 当前纪元（用于逻辑撤销） |
| `fenceIReq` | `Bool()` | FENCE.I 请求信号 |

#### 2.1.5 来自 CSRsUnit

| 信号 | 类型 | 描述 |
|------|------|------|
| `csrPMP` | `PMPConfig` | PMP 配置寄存器值 |

#### 2.1.6 控制信号

| 信号 | 源 | 描述 |
|------|-----|------|
| `GlobalFlush` | ROB/CSRsUnit | 全局冲刷信号，用于丢弃所有进行中的操作 |
| `BranchFlush` | BRU | 分支预测错误冲刷信号 |
| `branchOH` | BRU | 分支独热码（用于移除分支依赖） |

#### 2.1.7 CDB 监听

| 信号 | 类型 | 描述 |
|------|------|------|
| `CDBMessage` | `CDBMessage` | 来自 CDB 的广播消息（包含 robId、phyRd、data、hasSideEffect、exception） |

### 2.2 输出接口

LSU 将结果输出到公共数据总线（CDB）和 D-Cache，使用 [`CDBMessage`](../../src/main/scala/cpu/Protocol.scala:361-367)、[`LSUCacheRReq`](../../src/main/scala/cpu/Protocol.scala:504-507) 和 [`LSUCacheWReq`](../../src/main/scala/cpu/Protocol.scala:509-513) 结构体：

#### 2.2.1 到 CDB

| 信号 | 类型 | 描述 |
|------|------|------|
| `robId` | `RobTag` | 重排序缓冲区条目标识符 |
| `phyRd` | `PhyTag` | 目标物理寄存器标识符 |
| `data` | `DataW` | 加载的数据（Load 指令） |
| `hasSideEffect` | `Bits(1.W)` | 副作用标志（非幂等 Load 指令为 1） |
| `exception` | `Exception` | 异常信息（如果有） |

#### 2.2.2 到 D-Cache

| 信号 | 类型 | 描述 |
|------|------|------|
| `addr` | `UInt(32.W)` | 物理地址 |
| `data` | `UInt(32.W)` | 写数据（Store 指令） |
| `ctx` | `MemContext` | 上下文信息（包含 epoch、branchMask、robId） |

#### 2.2.3 到 ROB

| 信号 | 类型 | 描述 |
|------|------|------|
| `storeAck` | `Bool()` | Store 完成确认信号 |
| `fenceIDone` | `Bool()` | FENCE.I 完成信号 |

### 2.3 Chisel 接口定义

LSU 模块的接口定义在 [`Protocol.scala`](../../src/main/scala/cpu/Protocol.scala) 中，使用以下结构体：

```scala
// LSU 分派请求（在 Protocol.scala 第 492-501 行）
class LSUDispatch extends Bundle with CPUConfig {
  val opcode = LSUOp()       // Load / Store
  val memWidth = LSUWidth()   // 访存位宽
  val memSign = LSUsign()     // 符号扩展标志
  val data = new DataReq      // 数据包
  val phyRd = ArchTag         // 目标寄存器 (Load 指令)
  val robId = RobTag          // ROB 条目 ID
  val branchMask = SnapshotMask // 分支掩码
  val privMode = PrivMode()   // 特权级
}

// 数据请求（在 Protocol.scala 第 235-244 行）
class DataReq extends Bundle with CPUConfig {
  val src1Sel = Src1Sel()
  val src1Tag = PhyTag
  val src1Ready = Bool()
  val src2Sel = Src2Sel()
  val src2Tag = PhyTag
  val src2Ready = Bool()
  val imm = DataW
  val pc = AddrW
}

// LSU Cache 读请求（在 Protocol.scala 第 504-507 行）
class LSUCacheRReq extends Bundle with CPUConfig {
  val addr = UInt(32.W)       // 物理地址
  val ctx = new MemContext    // 上下文信息
}

// LSU Cache 写请求（在 Protocol.scala 第 509-513 行）
class LSUCacheWReq extends Bundle with CPUConfig {
  val addr = UInt(32.W)       // 物理地址
  val data = UInt(32.W)       // 写数据
  val ctx = new MemContext    // 上下文信息
}

// LSU Cache 读响应（在 Protocol.scala 第 516-519 行）
class LSUCacheRResp extends Bundle with CPUConfig {
  val data = UInt(32.W)       // 读数据
  val ctx = new MemContext    // 回传的上下文
}

// LSU Cache 写响应（在 Protocol.scala 第 521-523 行）
class LSUCacheWResp extends Bundle with CPUConfig {
  val ctx = new MemContext    // 回传的上下文
}

// 内存上下文（在 Protocol.scala 第 420-424 行）
class MemContext extends Bundle with CPUConfig {
  val epoch = EpochW          // 纪元标记（用于逻辑撤销）
  val branchMask = SnapshotMask // 分支掩码（用于分支冲刷）
  val robId = RobTag          // ROB 条目 ID
}

// CDB 消息（在 Protocol.scala 第 361-367 行）
class CDBMessage extends Bundle with CPUConfig {
  val robId = RobTag          // 用于 ROB 标记完成
  val phyRd = PhyTag          // 用于 RS 唤醒依赖指令 & PRF 写入
  val data = DataW            // 写入 PRF 的数据
  val hasSideEffect = Bool()  // 是否有副作用（非幂等 Load 指令专用）
  val exception = new Exception // 执行阶段产生的异常
}

// 访存操作枚举（在 Protocol.scala 第 57-59 行）
object LSUOp extends ChiselEnum {
  val LOAD, STORE, NOP = Value
}

// 访存位宽枚举（在 Protocol.scala 第 61-63 行）
object LSUWidth extends ChiselEnum {
  val BYTE, HALF, WORD = Value
}

// 符号扩展枚举（在 Protocol.scala 第 65-67 行）
object LSUsign extends ChiselEnum {
  val UNSIGNED, SIGNED = Value
}

// 异常包定义（在 Protocol.scala 第 134-138 行）
class Exception extends Bundle {
  val valid = Bool()
  val cause = UInt(32.W)
  val tval = UInt(32.W)
}
```

## 3. 支持的功能

### 3.1 加载队列（LQ）管理

LSU 维护一个 8 项的加载队列（LQ），用于跟踪在途的 Load 指令：

| 功能 | 描述 |
|------|------|
| **指令入队** | 当 RS 分发 Load 指令时，将其信息写入 LQ 的空闲条目 |
| **操作数监听** | 监听 CDB 广播，更新操作数的就绪状态 |
| **地址计算** | 当 rs1 就绪时，向 AGU 发起地址生成请求 |
| **依赖追踪** | 维护 storeMask，跟踪依赖的 Store 指令 |
| **转发检查** | 检查是否可以从 Store 指令转发数据 |
| **结果广播** | 当 Load 完成时，将结果广播到 CDB |

### 3.2 存储队列（SQ）管理

LSU 维护一个 8 项的存储队列（SQ），用于跟踪在途的 Store 指令：

| 功能 | 描述 |
|------|------|
| **指令入队** | 当 RS 分发 Store 指令时，将其信息写入 SQ 的空闲条目 |
| **操作数监听** | 监听 CDB 广播，更新操作数的就绪状态 |
| **地址计算** | 当 rs1 就绪时，向 AGU 发起地址生成请求 |
| **数据准备** | 当 rs2 就绪时，获取存储数据 |
| **提交等待** | 等待 ROB 发送 storeEnable 信号 |
| **写回执行** | 收到 storeEnable 后，向 D-Cache 发起写请求 |

### 3.3 Store-Load 转发

LSU 实现 Store 到 Load 的数据转发机制：

| 阶段 | 描述 |
|------|------|
| **依赖建立** | 当 Load 计算出 PA 时，拷贝当前 SQ 中有效、PA 尚未计算或与 Load 重复的对应位作为 storeMask |
| **地址比对** | 当 Store 计算出 PA 后，广播 PA + sqIndex，LQ 中所有 storeMask 对应位为 1 的 Load 立即进行地址比对 |
| **冲突检测** | 若地址不冲突，Load 将该位清零；若冲突，Load 保持该位 |
| **数据转发** | 当 Store 执行时，广播 data + sqIndex，LQ 中所有 storeMask 对应位为 1 的 Load 将该位清零并将 isForwarding 置 1，forwardingData 置为对应数据 |
| **转发完成** | 当 storeMask === 0 时，Load 指令被允许执行，若 isForwarding 为 1 直接提交；若为 0 则发向 D-Cache |

### 3.4 内存依赖追踪

LSU 维护 Load 指令对 Store 指令的依赖关系：

| 功能 | 描述 |
|------|------|
| **storeMask 维护** | 每个 LQ 条目维护一个 8 位 storeMask，标记依赖的 Store 指令 |
| **依赖清除** | 当 Store 完成地址比对或数据转发后，清除对应的 storeMask 位 |
| **依赖完成** | 当 storeMask === 0 时，Load 指令的所有依赖都已满足，可以执行 |
| **顺序保证** | 通过依赖追踪确保 Load 指令在所有依赖的 Store 指令完成后才执行 |

### 3.5 冲刷处理

LSU 支持两种冲刷机制：

| 冲刷类型 | 描述 |
|---------|------|
| **Global Flush** | 清空 LQ 和 SQ 的所有条目 |
| **Branch Flush** | 根据指令携带的 branchMask 执行 `valid &= ~kill`，清理对应的在途指令。若不冲刷则移除对应位分支依赖 |

### 3.6 异常处理

LSU 支持多种异常类型：

| 异常 | 原因编码 | 触发条件 |
|------|---------|---------|
| `LOAD_ADDRESS_MISALIGNED` | 4 | Load 指令地址未对齐 |
| `STORE_ADDRESS_MISALIGNED` | 6 | Store 指令地址未对齐 |
| `LOAD_ACCESS_FAULT` | 5 | Load 指令 PMP 权限违规 |
| `STORE_ACCESS_FAULT` | 7 | Store 指令 PMP 权限违规 |

## 4. 内部逻辑

### 4.1 LQ/SQ 队列结构

LSU 维护两个队列：加载队列（LQ）和存储队列（SQ），每个队列有 8 项。

#### 4.1.1 LQ 条目结构

每个 LQ 条目包含以下字段：

```scala
class LQEntry extends Bundle with CPUConfig {
  val valid = Bool()              // 条目有效位
  val memWidth = LSUWidth()       // 访存位宽
  val memSign = LSUsign()         // 符号扩展标志
  val phyRd = PhyTag              // 目标物理寄存器
  val robId = RobTag              // ROB 条目 ID
  val branchMask = SnapshotMask   // 分支掩码
  val epoch = EpochW              // 纪元标记
  val privMode = PrivMode()       // 特权级
  val src1Tag = PhyTag            // 基地址寄存器标签
  val src1Ready = Bool()          // 基地址寄存器就绪状态
  val PAReady = Bool()            // 物理地址就绪状态
  val PA = UInt(32.W)             // 物理地址
  val exception = new Exception   // 异常信息
  val storeMask = UInt(8.W)       // Store 依赖掩码（8 位）
  val isForwarding = Bool()       // 是否发生 Store-Load 转发
  val forwardingData = DataW      // 转发的数据
}
```

#### 4.1.2 SQ 条目结构

每个 SQ 条目包含以下字段：

```scala
class SQEntry extends Bundle with CPUConfig {
  val valid = Bool()              // 条目有效位
  val memWidth = LSUWidth()       // 访存位宽
  val robId = RobTag              // ROB 条目 ID
  val branchMask = SnapshotMask   // 分支掩码
  val epoch = EpochW              // 纪元标记
  val privMode = PrivMode()       // 特权级
  val src1Tag = PhyTag            // 基地址寄存器标签
  val src1Ready = Bool()          // 基地址寄存器就绪状态
  val src2Tag = PhyTag            // 存储数据寄存器标签
  val src2Ready = Bool()          // 存储数据寄存器就绪状态
  val PAReady = Bool()            // 物理地址就绪状态
  val PA = UInt(32.W)             // 物理地址
  val storeData = DataW           // 存储数据
  val exception = new Exception   // 异常信息
}
```

### 4.2 依赖检查逻辑

LSU 使用 storeMask 机制维护 Load 对 Store 的依赖关系：

```scala
// 当 Load 计算出 PA 时，建立依赖
when (loadEntry.PAReady && !loadEntry.PAReadyPrev) {
  // 拷贝当前 SQ 中有效、PA 尚未计算或与 Load 重复的对应位
  val newStoreMask = UInt(8.W)
  for (i <- 0 until 8) {
    when (sq(i).valid && (!sq(i).PAReady || isAddressOverlap(loadEntry.PA, sq(i).PA, loadEntry.memWidth, sq(i).memWidth))) {
      newStoreMask(i) := 1.U
    } .otherwise {
      newStoreMask(i) := 0.U
    }
  }
  loadEntry.storeMask := newStoreMask
}

// 当 Store 计算出 PA 后，进行地址比对
when (storeEntry.PAReady && !storeEntry.PAReadyPrev) {
  val sqIndex = storeEntry.index
  for (i <- 0 until 8) {
    when (lq(i).valid && lq(i).storeMask(sqIndex)) {
      when (!isAddressOverlap(lq(i).PA, storeEntry.PA, lq(i).memWidth, storeEntry.memWidth)) {
        // 地址不冲突，清除依赖
        lq(i).storeMask := lq(i).storeMask & ~(1.U << sqIndex)
      }
    }
  }
}
```

### 4.3 转发逻辑

LSU 实现 Store 到 Load 的数据转发机制：

```scala
// 当 Store 执行时，进行数据转发
when (storeEntry.valid && storeEntry.PAReady && storeEntry.src2Ready) {
  val sqIndex = storeEntry.index
  for (i <- 0 until 8) {
    when (lq(i).valid && lq(i).storeMask(sqIndex)) {
      // 发生 Store-Load 转发
      lq(i).isForwarding := true.B
      lq(i).forwardingData := storeEntry.storeData
      // 清除依赖
      lq(i).storeMask := lq(i).storeMask & ~(1.U << sqIndex)
    }
  }
}

// 当 Load 的所有依赖都满足时，可以执行
when (loadEntry.valid && loadEntry.PAReady && loadEntry.storeMask === 0.U) {
  when (loadEntry.isForwarding) {
    // 直接使用转发的数据
    val loadData = signExtend(loadEntry.forwardingData, loadEntry.memSign, loadEntry.memWidth)
    // 广播到 CDB
    io.cdb.valid := true.B
    io.cdb.bits.robId := loadEntry.robId
    io.cdb.bits.phyRd := loadEntry.phyRd
    io.cdb.bits.data := loadData
    io.cdb.bits.hasSideEffect := false.B
    io.cdb.bits.exception := loadEntry.exception
  } .otherwise {
    // 发向 D-Cache
    io.cacheRReq.valid := true.B
    io.cacheRReq.bits.addr := loadEntry.PA
    io.cacheRReq.bits.ctx := loadEntry.ctx
  }
}
```

### 4.4 地址计算（通过 AGU）

LSU 通过 AGU 子模块计算物理地址：

```scala
// 当 Load/Store 的 rs1 就绪时，向 AGU 发起地址生成请求
when (entry.valid && entry.src1Ready && !entry.PAReady) {
  val aguReq = Wire(new AGUReq)
  aguReq.baseAddr := io.prfData.rdata1  // 来自 PRF 的基地址
  aguReq.offset := io.lsuDispatch.data.imm  // 来自指令的立即数
  aguReq.memWidth := entry.memWidth
  aguReq.memOp := Mux(isLoad, LSUOp.LOAD, LSUOp.STORE)
  aguReq.privMode := entry.privMode
  aguReq.ctx := entry.ctx
  
  // 发送到 AGU
  io.aguReq <> aguReq
}

// 接收 AGU 响应
when (io.aguResp.valid) {
  entry.PA := io.aguResp.pa
  entry.PAReady := true.B
  entry.exception := io.aguResp.exception
}
```

### 4.5 权限检查（通过 PMP）

AGU 内部通过 PMPChecker 进行权限检查：

```scala
// 在 AGU 内部，生成 PMP 检查请求
val pmpCheckReq = Wire(new PMPCheckReq)
pmpCheckReq.addr := pa
pmpCheckReq.memOp := io.aguReq.memOp
pmpCheckReq.privMode := io.aguReq.privMode

// 发送到 PMPChecker
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

### 4.6 提交流程

#### 4.6.1 Load 提交流程

```scala
// Load 提交流程
// 1. 指令入队
when (io.lsuDispatch.valid && io.lsuDispatch.bits.opcode === LSUOp.LOAD) {
  val emptyIdx = PriorityEncoder(~lq.map(_.valid))
  lq(emptyIdx).valid := true.B
  lq(emptyIdx).memWidth := io.lsuDispatch.bits.memWidth
  lq(emptyIdx).memSign := io.lsuDispatch.bits.memSign
  lq(emptyIdx).phyRd := io.lsuDispatch.bits.phyRd
  lq(emptyIdx).robId := io.lsuDispatch.bits.robId
  lq(emptyIdx).branchMask := io.lsuDispatch.bits.branchMask
  lq(emptyIdx).epoch := io.currentEpoch
  lq(emptyIdx).privMode := io.lsuDispatch.bits.privMode
  lq(emptyIdx).src1Tag := io.lsuDispatch.bits.data.src1Tag
  lq(emptyIdx).src1Ready := io.lsuDispatch.bits.data.src1Ready
  lq(emptyIdx).PAReady := false.B
  lq(emptyIdx).exception := 0.U.asTypeOf(new Exception)
  lq(emptyIdx).storeMask := 0.U
  lq(emptyIdx).isForwarding := false.B
}

// 2. 监听 CDB，更新操作数就绪状态
when (io.cdb.valid) {
  for (i <- 0 until 8) {
    when (lq(i).valid && lq(i).src1Tag === io.cdb.bits.phyRd) {
      lq(i).src1Ready := true.B
    }
  }
}

// 3. 地址计算和依赖建立
// （见 4.2 和 4.4 节）

// 4. 数据转发或 Cache 访问
// （见 4.3 节）

// 5. 结果广播
// （见 4.3 节）
```

#### 4.6.2 Store 提交流程

```scala
// Store 提交流程
// 1. 指令入队
when (io.lsuDispatch.valid && io.lsuDispatch.bits.opcode === LSUOp.STORE) {
  val emptyIdx = PriorityEncoder(~sq.map(_.valid))
  sq(emptyIdx).valid := true.B
  sq(emptyIdx).memWidth := io.lsuDispatch.bits.memWidth
  sq(emptyIdx).robId := io.lsuDispatch.bits.robId
  sq(emptyIdx).branchMask := io.lsuDispatch.bits.branchMask
  sq(emptyIdx).epoch := io.currentEpoch
  sq(emptyIdx).privMode := io.lsuDispatch.bits.privMode
  sq(emptyIdx).src1Tag := io.lsuDispatch.bits.data.src1Tag
  sq(emptyIdx).src1Ready := io.lsuDispatch.bits.data.src1Ready
  sq(emptyIdx).src2Tag := io.lsuDispatch.bits.data.src2Tag
  sq(emptyIdx).src2Ready := io.lsuDispatch.bits.data.src2Ready
  sq(emptyIdx).PAReady := false.B
  sq(emptyIdx).exception := 0.U.asTypeOf(new Exception)
}

// 2. 监听 CDB，更新操作数就绪状态
when (io.cdb.valid) {
  for (i <- 0 until 8) {
    when (sq(i).valid && sq(i).src1Tag === io.cdb.bits.phyRd) {
      sq(i).src1Ready := true.B
    }
    when (sq(i).valid && sq(i).src2Tag === io.cdb.bits.phyRd) {
      sq(i).src2Ready := true.B
      sq(i).storeData := io.cdb.bits.data
    }
  }
}

// 3. 地址计算
// （见 4.4 节）

// 4. 等待 ROB 提交信号
when (sq(i).valid && sq(i).PAReady && sq(i).src2Ready && io.storeEnable && sq(i).robId === io.storeRobId) {
  // 发向 D-Cache
  io.cacheWReq.valid := true.B
  io.cacheWReq.bits.addr := sq(i).PA
  io.cacheWReq.bits.data := sq(i).storeData
  io.cacheWReq.bits.ctx := sq(i).ctx
  
  // 发送确认信号到 ROB
  io.storeAck := true.B
}
```

## 5. 验证考虑

### 5.1 功能验证

LSU 的关键验证点：

1. **Load 指令正确性**：验证 Load 指令从内存读取数据并正确广播到 CDB
2. **Store 指令正确性**：验证 Store 指令将数据正确写入内存
3. **地址计算正确性**：验证 AGU 计算的物理地址正确
4. **权限检查正确性**：验证 PMPChecker 的权限检查逻辑
5. **异常处理正确性**：验证各种异常情况的正确生成和处理

### 5.2 依赖验证

关键依赖验证点：

1. **Store-Load 依赖**：验证 Load 指令正确等待依赖的 Store 指令完成
2. **依赖建立**：验证 storeMask 的正确建立和更新
3. **依赖清除**：验证 storeMask 的正确清除
4. **依赖完成**：验证 Load 指令在所有依赖满足后才执行

### 5.3 转发验证

关键转发验证点：

1. **转发条件**：验证 Store-Load 转发的触发条件
2. **转发数据正确性**：验证转发的数据正确
3. **转发优先级**：验证转发优先于 Cache 访问
4. **转发冲突处理**：验证地址冲突时的转发行为

### 5.4 集成验证

关键集成验证点：

1. **RS 接口**：验证与 RS 的分派接口正确性
2. **PRF 接口**：验证与 PRF 的数据读取接口正确性
3. **CDB 接口**：验证与 CDB 的结果广播接口正确性
4. **D-Cache 接口**：验证与 D-Cache 的读写接口正确性
5. **ROB 接口**：验证与 ROB 的提交接口正确性
6. **AGU 接口**：验证与 AGU 的地址生成接口正确性
7. **PMPChecker 接口**：验证与 PMPChecker 的权限检查接口正确性
8. **冲刷处理**：验证对全局和分支冲刷信号的正确响应
