# MainMemory (主存) 设计文档

## 1. 概述

MainMemory 模块模拟物理内存的行为，提供高延迟、宽带宽的内存访问接口，支持仿真文件的预加载，通过 AXI4 协议与上层缓存系统进行通信。

MainMemory 是 CPU 内存层次结构的最后一层，位于 AXIArbiter 下方，直接响应来自 I-Cache 和 D-Cache 的内存访问请求。

## 2. 模块接口

### 2.1 输入接口

MainMemory 作为 AXI4 从设备，接收来自 AXIArbiter 的请求。接口使用 [`Flipped(new WideAXI4Bundle)`](../../../src/main/scala/cpu/Protocol.scala)，表示 MainMemory 是 Slave 端。

#### 2.1.1 读地址通道（AR Channel）

| 信号 | 类型 | 描述 |
|------|------|------|
| `ar.valid` | `Bool` | 读地址有效信号 |
| `ar.ready` | `Bool` | 读地址就绪信号（MainMemory 输出） |
| `ar.bits.addr` | `UInt(32.W)` | 读地址（字节地址） |
| `ar.bits.id` | `AXIID` | 事务 ID（区分 I/D Cache） |
| `ar.bits.len` | `UInt(8.W)` | Burst 长度，宽总线通常为 0 |
| `ar.bits.user.epoch` | `UInt(2.W)` | 纪元信息，用于内存一致性 |

#### 2.1.2 写地址通道（AW Channel）

| 信号 | 类型 | 描述 |
|------|------|------|
| `aw.valid` | `Bool` | 写地址有效信号 |
| `aw.ready` | `Bool` | 写地址就绪信号（MainMemory 输出） |
| `aw.bits.addr` | `UInt(32.W)` | 写地址（字节地址） |
| `aw.bits.id` | `AXIID` | 事务 ID |
| `aw.bits.len` | `UInt(8.W)` | Burst 长度，宽总线通常为 0 |
| `aw.bits.user.epoch` | `UInt(2.W)` | 纪元信息 |

#### 2.1.3 写数据通道（W Channel）

| 信号 | 类型 | 描述 |
|------|------|------|
| `w.valid` | `Bool` | 写数据有效信号 |
| `w.ready` | `Bool` | 写数据就绪信号（MainMemory 输出） |
| `w.bits.data` | `UInt(512.W)` | 写数据（512-bit 宽数据） |
| `w.bits.strb` | `UInt(64.W)` | 字节写掩码（64 bytes -> 64 bits） |
| `w.bits.last` | `Bool` | Burst 结束标志，宽总线一次传完 |

#### 2.1.4 读响应通道（R Channel - MainMemory 输出）

| 信号 | 类型 | 描述 |
|------|------|------|
| `r.valid` | `Bool` | 读响应有效信号（MainMemory 输出） |
| `r.ready` | `Bool` | 读响应就绪信号 |
| `r.bits.data` | `UInt(512.W)` | 读数据（512-bit） |
| `r.bits.id` | `AXIID` | 事务 ID（回传请求的 ID） |
| `r.bits.last` | `Bool` | Burst 结束标志（宽总线恒为 true） |
| `r.bits.user.epoch` | `UInt(2.W)` | 纪元信息（原样回传） |

#### 2.1.5 写响应通道（B Channel - MainMemory 输出）

| 信号 | 类型 | 描述 |
|------|------|------|
| `b.valid` | `Bool` | 写响应有效信号（MainMemory 输出） |
| `b.ready` | `Bool` | 写响应就绪信号 |
| `b.bits.id` | `AXIID` | 事务 ID（回传请求的 ID） |
| `b.bits.user.epoch` | `UInt(2.W)` | 纪元信息（原样回传） |

### 2.2 Chisel 接口定义

MainMemory 模块的接口定义在 [`Protocol.scala`](../../../src/main/scala/cpu/Protocol.scala) 中，使用以下结构体：

```scala
// AXI 上下文元数据（在 Protocol.scala 中定义）
class AXIContext extends Bundle with CPUConfig {
  val epoch = UInt(2.W)  // 纪元信息，用于内存一致性维护
}

// Wide-AXI4 总线接口（在 Protocol.scala 中定义）
class WideAXI4Bundle extends Bundle with CPUConfig {
  // --- 读路径 ---
  val ar = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id   = new AXIID
    val len  = UInt(8.W)
    val user = new AXIContext
  })
  
  val r = Flipped(Decoupled(new Bundle {
    val data = UInt(512.W)
    val id   = new AXIID
    val last = Bool()
    val user = new AXIContext
  }))

  // --- 写路径 ---
  val aw = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id   = new AXIID
    val len  = UInt(8.W)
    val user = new AXIContext
  })
  
  val w = Decoupled(new Bundle {
    val data = UInt(512.W)
    val strb = UInt(64.W)
    val last = Bool()
  })
  
  val b = Flipped(Decoupled(new Bundle {
    val id   = new AXIID
    val user = new AXIContext
  }))
}
```

## 3. 支持的操作

### 3.1 读操作（Read Operation）

MainMemory 支持基于 Wide-AXI4 协议的读操作，具有以下特性：

| 特性 | 描述 |
|------|------|
| **地址对齐** | 地址必须与 64 字节边界对齐（addr[5:0] == 0） |
| **数据宽度** | 512-bit（64 字节） |
| **延迟模型** | 可配置延迟（例如 10 个周期） |
| **响应时机** | 延迟周期后返回数据，一次性返回全部 512-bit |
| **上下文传递** | 原样回传请求中的 epoch 信息 |

**读操作时序**：
1. **Cycle 0**：AR 握手完成，锁存请求信息（addr, id, epoch）
2. **Cycle 1 ~ LATENCY-1**：延迟模拟，计数器递减
3. **Cycle LATENCY**：发起 SRAM 读操作
4. **Cycle LATENCY+1**：SRAM 返回数据，通过 R 通道返回

### 3.2 写操作（Write Operation）

MainMemory 支持基于 Wide-AXI4 协议的写操作，具有以下特性：

| 特性 | 描述 |
|------|------|
| **地址对齐** | 地址必须与 64 字节边界对齐 |
| **数据宽度** | 512-bit（64 字节） |
| **字节掩码** | 64-bit 字节写掩码（strb），支持部分字节写入 |
| **延迟模型** | 可配置延迟（例如 10 个周期） |
| **响应时机** | 延迟周期后返回写确认（B 通道） |
| **上下文传递** | 原样回传请求中的 epoch 信息 |

**写操作时序**：
1. **Cycle 0**：AW 和 W 握手完成，锁存请求信息（addr, id, data, strb, epoch）
2. **Cycle 1 ~ LATENCY-1**：延迟模拟，计数器递减
3. **Cycle LATENCY**：执行 SRAM 写操作
4. **Cycle LATENCY+1**：通过 B 通道返回写确认

### 3.3 操作优先级

当读请求和写请求同时到达时，MainMemory 采用**读优先（Read Priority）**策略：

| 场景 | 优先级 | 原因 |
|------|--------|------|
| 读 vs 写 | 读优先 | Load 指令阻塞流水线，Store 指令不阻塞 |
| 实际情况 | 不会同时发生 | AXIArbiter 保证同一时间只有一个请求 |

## 4. 内部逻辑

### 4.1 SRAM 实现

MainMemory 使用 Chisel 的 [`SyncReadMem`](../../../src/main/scala/cpu/Protocol.scala) 原语实现内存存储：

#### 4.1.1 SRAM 特性

| 特性 | 描述 |
|------|------|
| **原语类型** | `SyncReadMem`（同步读存储器） |
| **读延迟** | 1 个周期（时钟同步读） |
| **写延迟** | 0 个周期（同一周期生效） |
| **寻址方式** | 按字节寻址 |
| **存储组织** | `Vec(64, UInt(8.W))` 表示 64 字节块 |

#### 4.1.2 SRAM 定义

```scala
// 定义 SRAM：size/64 个条目，每个条目 64 字节
val mem = SyncReadMem(
  size / 64,                    // 条目数量
  Vec(64, UInt(8.W))           // 每个条目 64 字节
)

// 从文件预加载内存内容（例如 kernel.hex）
loadMemoryFromFile(mem, "kernel.hex")
```

#### 4.1.3 SRAM 读操作

```scala
// 在延迟周期结束后发起读操作
val readData = mem.read(req_reg.addr >> 6.U, enable = true)
// 注意：readData 在下一个周期才有效
```

#### 4.1.4 SRAM 写操作

```scala
// 执行写操作，使用字节掩码
mem.write(
  req_reg.addr >> 6.U,          // 写地址（块地址）
  req_reg.data,                 // 写数据
  req_reg.strb                  // 字节写掩码
)
// 注意：写操作在同一周期生效
```

### 4.2 状态机设计

MainMemory 使用有限状态机（FSM）来模拟内存访问的延迟和处理流程。

#### 4.2.1 状态定义

```scala
object RamState extends ChiselEnum {
  val Idle     = Value  // 空闲状态，等待新请求
  val Delay    = Value  // 延迟模拟状态
  val Response = Value  // 响应状态，发送响应数据
}
```

#### 4.2.2 维护状态

| 状态变量 | 类型 | 描述 |
|----------|------|------|
| `state` | `RamState` | 当前状态寄存器，初始为 `Idle` |
| `counter` | `UInt(32.W)` | 延迟计数器，初始为 LATENCY - 1 |
| `req_reg` | `RequestReg` | 请求信息锁存寄存器 |
| `is_read` | `Bool` | 标记当前是读操作还是写操作 |

#### 4.2.3 请求信息锁存寄存器

```scala
// 请求信息锁存寄存器
class RequestReg extends Bundle with CPUConfig {
  val addr = UInt(32.W)       // 请求地址
  val id   = new AXIID        // 事务 ID
  val user = new AXIContext   // 上下文信息（epoch）
  val data = UInt(512.W)     // 写数据（仅写操作）
  val strb = UInt(64.W)      // 字节写掩码（仅写操作）
}

val req_reg = Reg(new RequestReg)
```

### 4.3 状态流转逻辑

#### 4.3.1 IDLE 状态（空闲态）

**条件**：`state === RamState.Idle`

**逻辑**：
- 同时监听 `io.axi.ar.valid` 和 `io.axi.aw.valid`
- 仲裁逻辑：读优先（Load 阻塞流水线）

**读请求处理**：
```scala
when (io.axi.ar.valid) {
  // 锁存读请求信息
  req_reg.addr := io.axi.ar.bits.addr
  req_reg.id   := io.axi.ar.bits.id
  req_reg.user := io.axi.ar.bits.user
  
  // 初始化延迟计数器
  counter := LATENCY.U - 1.U
  
  // 标记为读操作
  is_read := true.B
  
  // 跳转到延迟状态
  state := RamState.Delay
  
  // 握手完成
  io.axi.ar.ready := true.B
}
```

**写请求处理**：
```scala
.elsewhen (io.axi.aw.valid && io.axi.w.valid) {
  // 锁存写请求信息
  req_reg.addr := io.axi.aw.bits.addr
  req_reg.id   := io.axi.aw.bits.id
  req_reg.user := io.axi.aw.bits.user
  req_reg.data := io.axi.w.bits.data
  req_reg.strb := io.axi.w.bits.strb
  
  // 初始化延迟计数器
  counter := LATENCY.U - 1.U
  
  // 标记为写操作
  is_read := false.B
  
  // 跳转到延迟状态
  state := RamState.Delay
  
  // 握手完成
  io.axi.aw.ready := true.B
  io.axi.w.ready  := true.B
}
```

#### 4.3.2 DELAY 状态（延迟模拟态）

**条件**：`state === RamState.Delay`

**逻辑**：
- 递减延迟计数器
- 当计数器归零时，发起 SRAM 访问并跳转到响应状态

```scala
when (state === RamState.Delay) {
  when (counter === 0.U) {
    // 延迟结束，发起 SRAM 访问
    when (is_read) {
      // 读操作：发起 SRAM 读
      val readData = mem.read(req_reg.addr >> 6.U, enable = true)
      // readData 在下一个周期才有效
    } .otherwise {
      // 写操作：执行 SRAM 写
      mem.write(
        req_reg.addr >> 6.U,
        req_reg.data,
        req_reg.strb
      )
    }
    
    // 跳转到响应状态
    state := RamState.Response
  } .otherwise {
    // 继续延迟
    counter := counter - 1.U
  }
}
```

#### 4.3.3 RESPONSE 状态（响应态）

**条件**：`state === RamState.Response`

**逻辑**：
- 通过 R 通道（读）或 B 通道（写）发送响应
- 等待握手完成（ready === true）
- 返回空闲状态

**读响应处理**：
```scala
when (state === RamState.Response && is_read) {
  // 输出读响应
  io.axi.r.valid := true.B
  io.axi.r.bits.data := readData        // SRAM 读数据
  io.axi.r.bits.id   := req_reg.id      // 回传事务 ID
  io.axi.r.bits.last := true.B          // 宽总线一次传完
  io.axi.r.bits.user := req_reg.user    // 回传上下文（epoch）
  
  // 等待握手完成
  when (io.axi.r.ready) {
    state := RamState.Idle
  }
}
```

**写响应处理**：
```scala
when (state === RamState.Response && !is_read) {
  // 输出写响应
  io.axi.b.valid := true.B
  io.axi.b.bits.id   := req_reg.id      // 回传事务 ID
  io.axi.b.bits.user := req_reg.user    // 回传上下文（epoch）
  
  // 等待握手完成
  when (io.axi.b.ready) {
    state := RamState.Idle
  }
}
```

### 4.4 时序模型

MainMemory 的时序模型由以下参数控制：

| 参数 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `LATENCY` | `Int` | 10 | 内存访问延迟（周期数） |
| `SIZE` | `Int` | 64MB | 内存容量（字节） |

**时序图示例（读操作）**：

```
Cycle | AR Channel | State      | Counter | SRAM Read | R Channel
------|------------|------------|---------|-----------|------------
  0   | fire       | Idle→Delay | 9       | -         | -
  1   | -          | Delay      | 8       | -         | -
  2   | -          | Delay      | 7       | -         | -
  ... | ...        | ...        | ...     | ...       | ...
  9   | -          | Delay      | 0       | -         | -
 10   | -          | Delay→Resp | -       | read()    | -
 11   | -          | Response   | -       | data      | valid
 12   | -          | Idle       | -       | -         | fire
```

**时序图示例（写操作）**：

```
Cycle | AW/W Channel| State      | Counter | SRAM Write| B Channel
------|-------------|------------|---------|-----------|------------
  0   | fire        | Idle→Delay | 9       | -         | -
  1   | -           | Delay      | 8       | -         | -
  2   | -           | Delay      | 7       | -         | -
  ... | ...         | ...        | ...     | ...       | ...
  9   | -           | Delay      | 0       | -         | -
 10   | -           | Delay→Resp | -       | write()   | -
 11   | -           | Response   | -       | -         | valid
 12   | -           | Idle       | -       | -         | fire
```

## 5. 验证考虑

### 5.1 功能验证

MainMemory 的关键验证点：

1. **读操作正确性**：
   - 验证读地址正确映射到 SRAM 地址
   - 验证读数据正确返回
   - 验证 epoch 信息正确回传

2. **写操作正确性**：
   - 验证写地址正确映射到 SRAM 地址
   - 验证写数据正确写入
   - 验证字节掩码（strb）正确生效
   - 验证写确认正确返回

3. **延迟模型**：
   - 验证读操作的延迟周期数正确
   - 验证写操作的延迟周期数正确
   - 验证延迟计数器正确递减

4. **状态机正确性**：
   - 验证状态转换正确
   - 验证所有状态都能正确回到 Idle
   - 验证异常情况的处理

### 5.2 协议验证

关键 AXI4 协议验证点：

1. **握手协议**：
   - 验证 Valid/Ready 握手正确
   - 验证不会发生死锁
   - 验证反压信号正确传播

2. **通道独立性**：
   - 验证 AR/R 通道独立工作
   - 验证 AW/W/B 通道独立工作
   - 验证读和写操作互不干扰

3. **上下文传递**：
   - 验证 ID 字段正确回传
   - 验证 epoch 信息正确回传
   - 验证 User 字段完整传递

### 5.3 集成验证

关键集成验证点：

1. **AXIArbiter 接口**：
   - 验证与 AXIArbiter 的正确连接
   - 验证多请求仲裁正确处理
   - 验证响应正确路由到对应的 Cache

2. **Cache 接口**：
   - 验证 I-Cache 和 D-Cache 的请求都能正确处理
   - 验证 Cache Miss 时的正确行为
   - 验证数据正确填充到 Cache

3. **内存一致性**：
   - 验证 epoch 信息正确传递
   - 验证过期的内存访问被正确处理
   - 验证 FENCE.I 操作的正确性

### 5.4 边界情况测试

关键边界情况：

1. **地址边界**：
   - 测试地址对齐和不对齐的情况
   - 测试内存边界访问
   - 测试地址回绕

2. **数据边界**：
   - 测试全 0 和全 1 数据
   - 测试部分字节写入（strb 掩码）
   - 测试数据边界对齐

3. **时序边界**：
   - 测试最小延迟（LATENCY = 1）
   - 测试最大延迟
   - 测试连续请求的处理

4. **并发访问**：
   - 测试读后读（Read-after-Read）
   - 测试读后写（Read-after-Write）
   - 测试写后读（Write-after-Read）
   - 测试写后写（Write-after-Write）
