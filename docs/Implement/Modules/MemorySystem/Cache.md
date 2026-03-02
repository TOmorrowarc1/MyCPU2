# Cache (缓存) 设计文档

## 1. 概述

Cache 是 MemorySystem 中的缓存模块，负责提供基于物理地址（PA）的高速数据读取与写入。Cache 分为两个实例：**I-Cache**（指令缓存）和 **D-Cache**（数据缓存），分别处理指令取指和数据访存请求。

### 1.1 目的和作用

Cache 在 CPU 内存系统中发挥以下关键作用：

- **物理地址缓存**：提供基于物理地址的高速数据读取与写入，减少对主存的访问延迟。
- **指令取指服务**（I-Cache）：为 Fetcher 提供指令取指服务，支持流水线化的指令获取。
- **数据访存服务**（D-Cache）：为 LSU 提供数据读写服务，支持 Load/Store 指令的执行。
- **主存访问协调**：通过 512-bit 宽总线向主存发起请求，通过 AXIArbiter 进行总线仲裁。
- **一致性维护**：执行 FENCE.I 指令的脏行清空，确保指令与数据的一致性。
- **逻辑撤销支持**：配合 Epoch/BranchMask 处理在途指令的逻辑废除，支持分支预测错误的恢复。

Cache 是 Tomasulo 架构中内存系统的核心组件，与 LSU、AXIArbiter、MainMemory 协同工作，确保内存访问的正确性和高效性。

## 2. 模块接口

### 2.1 输入接口

Cache 从 CPU 中的多个源接收输入，使用 [`CacheReq`](../../src/main/scala/cpu/Protocol.scala:442-448) 结构体：

#### 2.1.1 I-Cache 输入接口

| 信号 | 类型 | 描述 |
|------|------|------|
| `pc` | `AddrW` | 取指地址（程序计数器） |
| `instEpoch` | `EpochW` | 指令纪元标记 |
| `prediction` | `Prediction` | 分支预测信息 |
| `exception` | `Exception` | 异常信息 |
| `privMode` | `PrivMode` | 当前特权模式 |

#### 2.1.2 D-Cache 输入接口

| 信号 | 类型 | 描述 |
|------|------|------|
| `addr` | `UInt(32.W)` | 物理地址 |
| `isWrite` | `Bool()` | 写操作标志 |
| `data` | `UInt(512.W)` | 写数据（仅写操作有效） |
| `strb` | `UInt(64.W)` | 字节掩码（仅写操作有效） |
| `ctx.epoch` | `EpochW` | 纪元标记（用于逻辑撤销） |
| `ctx.branchMask` | `SnapshotMask` | 分支掩码（用于分支冲刷） |
| `ctx.robId` | `RobTag` | ROB 条目 ID |

#### 2.1.3 控制信号

| 信号 | 源 | 描述 |
|------|-----|------|
| `fenceIReq` | ROB | FENCE.I 请求信号 |
| `currentEpoch` | ROB | 当前纪元（D-Cache） |
| `globalFlush` | CSRsUnit | 全局冲刷信号 |
| `branchFlush` | BRU | 分支预测错误冲刷信号 |
| `branchOH` | BRU | 分支独热码 |

### 2.2 输出接口

Cache 将结果输出到多个目标，使用 [`CacheResp`](../../src/main/scala/cpu/Protocol.scala:451-455) 结构体：

#### 2.2.1 I-Cache 输出接口

| 信号 | 目标 | 类型 | 描述 |
|------|------|------|------|
| `inst` | Decoder | `InstW` | 指令数据 |
| `instMetadata` | Decoder | `IFetchPacket` | 取指元数据包 |
| `fenceIDone` | ROB | `Bool()` | FENCE.I 完成信号 |

#### 2.2.2 D-Cache 输出接口

| 信号 | 目标 | 类型 | 描述 |
|------|------|------|------|
| `data` | LSU | `UInt(512.W)` | 读数据 |
| `ctx` | LSU | `MemContext` | 回传的上下文 |
| `exception` | LSU | `Exception` | 访问异常 |

#### 2.2.3 AXI 总线接口

| 信号 | 目标 | 类型 | 描述 |
|------|------|------|------|
| `ar` | AXIArbiter | `Decoupled` | 读地址通道请求 |
| `aw` | AXIArbiter | `Decoupled` | 写地址通道请求 |
| `w` | AXIArbiter | `Decoupled` | 写数据通道请求 |

### 2.3 Chisel 接口定义

Cache 模块的接口定义在 [`Protocol.scala`](../../src/main/scala/cpu/Protocol.scala) 中，使用以下结构体：

```scala
// 内存上下文（在 Protocol.scala 第 420-424 行）
class MemContext extends Bundle with CPUConfig {
  val epoch = EpochW // 纪元标记 (用于逻辑撤销)
  val branchMask = SnapshotMask // 分支掩码 (用于分支冲刷)
  val robId = RobTag // ROB 条目 ID
}

// 取指请求（在 Protocol.scala 第 427-430 行）
class FetchReq extends Bundle with CPUConfig {
  val pc = AddrW // 取指地址
  val ctx = new MemContext // 上下文信息
}

// 取指响应（在 Protocol.scala 第 433-437 行）
class FetchResp extends Bundle with CPUConfig {
  val data = UInt(512.W) // 512-bit 指令块
  val ctx = new MemContext // 回传的上下文
  val exception = new Exception // 访问异常
}

// Cache 请求（在 Protocol.scala 第 442-448 行）
class CacheReq extends Bundle with CPUConfig {
  val addr = UInt(32.W) // 物理地址
  val isWrite = Bool() // 写操作标志
  val data = UInt(512.W) // 写数据 (仅写操作有效)
  val strb = UInt(64.W) // 字节掩码 (仅写操作有效)
  val ctx = new MemContext // 上下文信息
}

// Cache 响应（在 Protocol.scala 第 451-455 行）
class CacheResp extends Bundle with CPUConfig {
  val data = UInt(512.W) // 读数据
  val ctx = new MemContext // 回传的上下文
  val exception = new Exception // 访问异常
}

// Cache 写回请求（在 Protocol.scala 第 458-462 行）
class CacheWriteback extends Bundle with CPUConfig {
  val addr = UInt(32.W) // 脏行地址
  val data = UInt(512.W) // 脏行数据
  val ctx = new MemContext // 上下文信息
}

// 宽总线 AXI4 接口（在 Protocol.scala 第 381-415 行）
class WideAXI4Bundle extends Bundle {
  // --- 读路径 ---
  val ar = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id = AXIID() // 事务 ID (区分 I/D Cache)
    val len = UInt(8.W) // Burst 长度，宽总线通常为 0
    val user = new AXIContext // 携带元数据
  })

  val r = Flipped(Decoupled(new Bundle {
    val data = UInt(512.W) // 512-bit 宽数据
    val id = AXIID()
    val last = Bool() // Burst 结束标志，宽总线返回结果当周期拉高
    val user = new AXIContext // 回传元数据
  }))

  // --- 写路径 ---
  val aw = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id = AXIID()
    val len = UInt(8.W)
    val user = new AXIContext
  })

  val w = Decoupled(new Bundle {
    val data = UInt(512.W)
    val strb = UInt(64.W) // 字节掩码 (64 bytes -> 64 bits)
    val last = Bool()
  })

  val b = Flipped(Decoupled(new Bundle {
    val id = AXIID()
    val user = new AXIContext
  }))
}
```

## 3. 支持的功能

### 3.1 2 周期流水线访问

Cache 采用 2 周期流水线设计，提高吞吐量：

- **Stage 1**：锁存进入请求的地址、上下文等信息，发起对 Tag 和 Data SRAM 的同步读取。
- **Stage 2**：请求信息前进一位，比对 SRAMTag 与请求地址的 Tag，返回结果或发起缺失处理。

### 3.2 缓存行管理

Cache 使用三个独立的阵列管理缓存行：

| 阵列 | 类型 | 描述 |
|------|------|------|
| Tag Array | SRAM | 存储缓存行的标签信息 |
| Data Array | SRAM | 存储缓存行的数据（512-bit） |
| Status Array | Regs | 存储缓存行的状态（有效位、脏位等） |

### 3.3 AXI 请求缓冲（AXIReqBuffer）

Cache 维护一个大小为 2 的 AXIReqBuffer，用于缓存待发送的总线请求：

- 当 Cache Miss 且该行 Dirty 时，将总线事务压入 AXIReqBuffer。
- 通过 AXIArbiter 发起总线请求，携带 Epoch 以便后续冲刷逻辑使用。
- 请求结果返回后，清空 AXIReqBuffer。

### 3.4 FENCE.I 指令序列化

Cache 支持 FENCE.I 指令的序列化执行：

- 当 `fenceIReq` 有效时，检查当前 Cache 状态。
- 若为 `AXIReqPending`，先等待主线事务完成并返回结果。
- 若为 `Pipeline`，将状态切换至 `FENCEIActive`，进入独占式的扫描回写状态。
- 扫描回写：每周期检查一行 status，清除所有 Cache Line 的有效位和脏位，将脏位为 1 的行写回主存。
- 完成条件：当所有行的有效位和脏位都为 0 时，拉高 `fenceIDone` 信号。

### 3.5 Cache 冲刷逻辑

Cache 支持多种冲刷机制：

| 冲刷类型 | 触发条件 | 处理方式 |
|----------|----------|----------|
| Global Flush | CSRsUnit 发送全局冲刷信号 | 清空所有在途请求，更新纪元 |
| Branch Flush | BRU 发送分支预测错误信号 | 根据 branchMask 清理对应的在途指令 |
| 逻辑撤销 | Decoder 检测到 InsEpoch 过期 | I-Cache 原样给出结果，由 Decoder 处理 |

### 3.6 写回策略

Cache 采用写回（Write-Back）策略：

- 写操作只更新 Cache 行，不立即写回主存。
- 当 Cache 行被替换时，如果该行 Dirty，则将其写回主存。
- FENCE.I 指令会强制将所有脏行写回主存。

## 4. 内部逻辑

### 4.1 流水线阶段设计

Cache 使用两级流水线锁存请求信息：

```scala
// Stage 1：锁存请求信息
val stage1_pc = Reg(UInt(32.W))
val stage1_ctx = Reg(new MemContext)
// 发起对 Tag 和 Data SRAM 的同步读取
tagArray.read(addr)
dataArray.read(addr)

// Stage 2：请求信息前进一位
val stage2_pc = Reg(UInt(32.W))
val stage2_ctx = Reg(new MemContext)
// 比对 SRAMTag 与 ReqPATag
val hit = (tagData === stage2_pc(tagBits))
```

### 4.2 Tag 比较逻辑

Tag 比较逻辑在 Stage 2 执行：

```scala
// 地址分解
val tag = io.addr(tagBits + indexBits + offsetBits - 1, indexBits + offsetBits)
val index = io.addr(indexBits + offsetBits - 1, offsetBits)
val offset = io.addr(offsetBits - 1, 0)

// Tag 比较逻辑
val sramTag = tagArray.read(index)
val status = statusArray(index)
val hit = status.valid && (sramTag === tag) && (status.epoch === io.ctx.epoch)
```

### 4.3 缓存替换策略

Cache 采用随机替换策略：

- 当发生 Cache Miss 且需要替换时，随机选择一个 Way 进行替换。
- 如果被替换的行 Dirty，则先将其写回主存。

### 4.4 缺失处理流程

Cache Miss 处理流程：

1. **检测 Miss**：Tag 比较失败或有效位为 0。
2. **检查 Dirty**：如果被替换的行 Dirty，将状态切换到 `AXIReqPending`。
3. **生成总线事务**：将总线事务压入 AXIReqBuffer。
4. **发起总线请求**：通过 AXIArbiter 发起总线请求，携带 Epoch。
5. **等待响应**：等待主存返回数据。
6. **更新 Cache**：将返回的数据写入 Cache 行，更新 Tag 和状态。
7. **返回结果**：如正常执行一般返回结果，状态切回 `Pipeline`。

### 4.5 写回机制

写回机制在以下情况下触发：

1. **Cache 替换**：当 Cache 行被替换且 Dirty 时。
2. **FENCE.I 指令**：当执行 FENCE.I 指令时，扫描所有 Cache 行，将脏行写回主存。
3. **全局冲刷**：当发生全局冲刷时，可能需要写回脏行。

写回流程：

```scala
// 写回请求生成
val writeback = Wire(new CacheWriteback)
writeback.addr := Cat(tag, index, 0.U(offsetBits.W))
writeback.data := dataArray(index)
writeback.ctx := io.ctx

// 发起写回请求
io.axi.aw.valid := writebackValid
io.axi.aw.bits.addr := writeback.addr
io.axi.aw.bits.id := AXIID.D_CACHE // 或 I_CACHE
io.axi.aw.bits.user.epoch := writeback.ctx.epoch

io.axi.w.valid := writebackValid
io.axi.w.bits.data := writeback.data
io.axi.w.bits.strb := 0xFFFFFFFFFFFFFFFF.U // 全部有效
io.axi.w.bits.last := true.B
```

### 4.6 FENCE.I 处理

FENCE.I 处理状态机：

```scala
object CacheState extends ChiselEnum {
  val Pipeline, AXIReqPending, FENCEIActive = Value
}

// FENCE.I 处理逻辑
when (fenceIReq) {
  when (state === CacheState.AXIReqPending) {
    // 等待主线事务完成
  } .otherwise {
    state := CacheState.FENCEIActive
    scanIndex := 0.U
  }
}

when (state === CacheState.FENCEIActive) {
  // 扫描回写
  val status = statusArray(scanIndex)
  when (status.valid && status.dirty) {
    // 发起写回
    initiateWriteback(scanIndex)
  } .otherwise {
    // 清除有效位和脏位
    statusArray(scanIndex).valid := false.B
    statusArray(scanIndex).dirty := false.B
  }

  // 更新扫描索引
  when (!writebackPending) {
    scanIndex := scanIndex + 1.U
    when (scanIndex === (numLines - 1).U) {
      fenceIDone := true.B
      state := CacheState.Pipeline
    }
  }
}
```

## 5. 验证考虑

### 5.1 功能验证

Cache 的关键验证点：

1. **Hit/Miss 处理**：验证 Cache Hit 和 Miss 的正确处理。
2. **Tag 比较逻辑**：验证 Tag 比较的正确性。
3. **数据完整性**：验证读写数据的正确性。
4. **写回机制**：验证脏行写回的正确性。
5. **FENCE.I 处理**：验证 FENCE.I 指令的正确执行。

### 5.2 协议验证

关键协议验证点：

1. **AXI4 协议**：验证与 AXIArbiter 的接口符合 AXI4 协议规范。
2. **握手协议**：验证与 Fetcher/LSU 的握手协议正确性。
3. **Epoch 机制**：验证纪元机制的正确性，确保逻辑撤销的正确执行。
4. **BranchMask 机制**：验证分支掩码机制的正确性，确保分支冲刷的正确执行。

### 5.3 集成验证

关键集成验证点：

1. **I-Cache 接口**：验证与 Fetcher 的接口正确性。
2. **D-Cache 接口**：验证与 LSU 的接口正确性。
3. **AXIArbiter 接口**：验证与 AXIArbiter 的接口正确性。
4. **全局控制信号**：验证对全局和分支冲刷信号的正确响应。
5. **内存一致性**：验证 FENCE.I 指令确保指令与数据的一致性。
