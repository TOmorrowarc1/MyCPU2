# AXI Arbiter (AXI 仲裁器) 设计文档

## 1. 概述

AXI Arbiter（AXI 仲裁器）是 MemorySystem 内部的总线仲裁模块，负责协调 I-Cache 和 D-Cache 对主存的总线访问请求，确保多个请求源能够有序地共享单一 AXI4 总线。

### 1.1 目的和作用

AXI Arbiter 在 CPU 内存系统中发挥以下关键作用：

- **请求汇聚**：将 I-Cache 的读请求与 D-Cache 的读/写请求进行仲裁，按照优先级顺序送往 Wide-AXI4 主总线。
- **响应分发**：通过 AXI ID 识别总线返回的数据（R 通道）或写确认（B 通道），将其路由回正确的 Cache 模块。
- **优先级仲裁**：在多个请求同时到达时，按照 D-Cache > I-Cache 的优先级进行仲裁，确保数据访存优先于指令取指。
- **状态维护**：维护 `isBusy` 状态，标记当前总线是否正在处理一个尚未完成的事务，防止多个请求同时占用总线。

AXI Arbiter 是 MemorySystem 的关键组件，位于 I-Cache、D-Cache 和 MainMemory 之间，实现了多对一的总线仲裁功能。

## 2. 模块接口

### 2.1 输入接口

AXI Arbiter 从 I-Cache、D-Cache 和 MainMemory 接收输入，使用 [`AXIArbiterIn`](../../src/main/scala/cpu/Protocol.scala:528-540) 结构体：

#### 2.1.1 来自 I-Cache（指令取指请求）

| 信号 | 类型 | 描述 |
|------|------|------|
| `iCacheReq.ar` | `AXIARBundle` | I-Cache 读请求（AR 通道） |

#### 2.1.2 来自 D-Cache（数据访存请求）

| 信号 | 类型 | 描述 |
|------|------|------|
| `dCacheReqR.ar` | `AXIARBundle` | D-Cache 读请求（AR 通道） |
| `dCacheReqW.aw` | `AXIAWBundle` | D-Cache 写地址请求（AW 通道） |
| `dCacheReqW.w` | `AXIWBundle` | D-Cache 写数据请求（W 通道） |

#### 2.1.3 来自 MainMemory（主存响应）

| 信号 | 类型 | 描述 |
|------|------|------|
| `mainMemResp.r` | `AXIRBundle` | 读数据响应（R 通道） |
| `mainMemResp.b` | `AXIBBundle` | 写确认响应（B 通道） |
| `mainMemResp.ar` | `AXIARBundle` | 读地址响应（AR 通道） |
| `mainMemResp.aw` | `AXIAWBundle` | 写地址响应（AW 通道） |
| `mainMemResp.w` | `AXIWBundle` | 写数据响应（W 通道） |

### 2.2 输出接口

AXI Arbiter 将结果输出到 I-Cache、D-Cache 和 MainMemory，使用 [`AXIArbiterOut`](../../src/main/scala/cpu/Protocol.scala:543-552) 结构体：

#### 2.2.1 到 I-Cache（指令取指响应）

| 信号 | 类型 | 描述 |
|------|------|------|
| `iCacheResp.r` | `AXIRBundle` | 返回 512-bit 指令块（R 通道） |

#### 2.2.2 到 D-Cache（数据访存响应）

| 信号 | 类型 | 描述 |
|------|------|------|
| `dCacheResp.r` | `AXIRBundle` | 返回 512-bit 数据块（R 通道） |
| `dCacheResp.b` | `AXIBBundle` | 返回写回确认（B 通道） |

#### 2.2.3 到 MainMemory（主存请求）

| 信号 | 类型 | 描述 |
|------|------|------|
| `mainMemReq.ar` | `AXIARBundle` | 读地址请求（AR 通道） |
| `mainMemReq.r` | `AXIRBundle` | 读数据响应（R 通道，Flipped） |
| `mainMemReq.aw` | `AXIAWBundle` | 写地址请求（AW 通道） |
| `mainMemReq.w` | `AXIWBundle` | 写数据请求（W 通道） |
| `mainMemReq.b` | `AXIBBundle` | 写确认响应（B 通道，Flipped） |

### 2.3 Chisel 接口定义

AXI Arbiter 模块的接口定义在 [`Protocol.scala`](../../src/main/scala/cpu/Protocol.scala) 中，使用以下结构体：

```scala
// AXI 事务 ID（在 Protocol.scala 第 124-127 行）
object AXIID extends ChiselEnum {
  val I_CACHE, D_CACHE = Value
}

// AXI 元数据（在 Protocol.scala 第 376-378 行）
class AXIContext extends Bundle {
  val epoch = UInt(2.W) // 用于回传上下文信息
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

// AXI 仲裁器输入（在 Protocol.scala 第 528-540 行）
class AXIArbiterIn extends Bundle {
  val iCacheReq = Flipped(Decoupled(new Bundle {
    val ar = new WideAXI4Bundle().ar.bits // I-Cache 读请求
  }))
  val dCacheReqR = Flipped(Decoupled(new Bundle {
    val ar = new WideAXI4Bundle().ar.bits // D-Cache 读请求
  }))
  val dCacheReqW = Flipped(Decoupled(new Bundle {
    val aw = new WideAXI4Bundle().aw.bits // D-Cache 写地址请求
    val w = new WideAXI4Bundle().w.bits // D-Cache 写数据请求
  }))
  val mainMemResp = Flipped(new WideAXI4Bundle) // 主存响应
}

// AXI 仲裁器输出（在 Protocol.scala 第 543-552 行）
class AXIArbiterOut extends Bundle {
  val iCacheResp = Decoupled(new Bundle {
    val r = new WideAXI4Bundle().r.bits // I-Cache 读响应
  })
  val dCacheResp = Decoupled(new Bundle {
    val r = new WideAXI4Bundle().r.bits // D-Cache 读响应
    val b = new WideAXI4Bundle().b.bits // D-Cache 写响应
  })
  val mainMemReq = new WideAXI4Bundle // 发往主存的统一 AXI 请求
}
```

## 3. 支持的功能

### 3.1 请求汇聚功能

AXI Arbiter 接收来自 I-Cache 和 D-Cache 的多个请求，将它们汇聚到单一 AXI4 总线上：

- **I-Cache 读请求**：通过 AR 通道接收指令取指请求
- **D-Cache 读请求**：通过 AR 通道接收数据读取请求
- **D-Cache 写请求**：通过 AW 和 W 通道接收数据写回请求

### 3.2 响应分发功能

AXI Arbiter 根据事务 ID 将 MainMemory 返回的响应分发到正确的 Cache 模块：

- **R 通道分发**：根据 `r.bits.id` 将读数据路由到 I-Cache（id=0）或 D-Cache（id=1）
- **B 通道分发**：将写确认路由到 D-Cache（只有 D-Cache 会发起写操作）

### 3.3 优先级仲裁（D-Cache > I-Cache）

AXI Arbiter 实现固定的优先级仲裁策略：

| 优先级 | 请求源 | 说明 |
|--------|--------|------|
| 1 | D-Cache（读/写） | 数据访存优先级最高 |
| 2 | I-Cache（读） | 指令取指优先级较低 |

仲裁逻辑确保当 D-Cache 和 I-Cache 同时请求时，D-Cache 的请求优先获得总线使用权。

### 3.4 状态维护（isBusy）

AXI Arbiter 维护一个 `isBusy` 状态寄存器，用于标记当前总线是否正在处理一个尚未完成的事务：

- **读事务**：从 `AR.fire` 开始，到 `R.fire && R.bits.last` 结束
- **写事务**：从 `AW.fire` 开始，到 `B.fire` 结束

在 `isBusy` 期间，新的请求不会被接受，确保同一时间只有一个事务占用总线。

## 4. 内部逻辑

### 4.1 仲裁逻辑

仲裁逻辑根据优先级和总线状态决定哪个请求获得总线使用权：

```scala
// 仲裁逻辑
val canIssue = !isBusy // 总线空闲
val dWins = dCacheRReq.valid || dCacheWReq.valid // D-Cache 赢得仲裁
val iWins = iCacheReq.valid && !dWins // I-Cache 赢得仲裁

// 握手输出
dCacheRReq.ready := canIssue && dWins && dCacheRReq.valid
dCacheWReq.ready := canIssue && dWins && dCacheWReq.valid
iCacheReq.ready := canIssue && iWins
```

### 4.2 请求缓冲

AXI Arbiter 不需要显式的请求缓冲，因为：

1. **单事务处理**：同一时间只处理一个事务，通过 `isBusy` 状态控制
2. **握手协议**：使用 AXI4 的 Valid/Ready 握手协议，上游模块在 `ready` 为低时保持请求
3. **直接转发**：将选中的请求直接转发到 MainMemory，无需缓冲

### 4.3 响应路由

响应路由逻辑根据事务 ID 将响应分发到正确的目标：

#### 4.3.1 读数据回传（R 通道）

```scala
val inflightId = mainMemResp.r.bits.id // 获取当前事务 ID

// 根据 ID 分发响应
iCacheResp.valid := mainMemResp.r.valid && (inflightId === AXIID.I_CACHE)
dCacheResp.r.valid := mainMemResp.r.valid && (inflightId === AXIID.D_CACHE)

// Ready 信号反压
mainMemResp.r.ready := Mux(inflightId === AXIID.D_CACHE, dCacheResp.r.ready, iCacheResp.r.ready)
```

#### 4.3.2 写确认回传（B 通道）

```scala
// 只有 D-Cache 会发起写操作
dCacheResp.b.valid := mainMemResp.b.valid
mainMemResp.b.ready := dCacheResp.b.ready
```

### 4.4 状态机

AXI Arbiter 使用简单的状态机来管理总线事务：

#### 4.4.1 状态定义

```scala
object ArbiterState extends ChiselEnum {
  val Idle, Busy = Value
}
```

#### 4.4.2 状态转换

```scala
val state = RegInit(ArbiterState.Idle)

switch(state) {
  is(ArbiterState.Idle) {
    // 空闲状态：等待请求
    when(canIssue && (dWins || iWins)) {
      state := ArbiterState.Busy
      // 转发请求到 MainMemory
    }
  }
  is(ArbiterState.Busy) {
    // 忙碌状态：等待事务完成
    when(mainMemResp.r.fire || mainMemResp.b.fire) {
      state := ArbiterState.Idle
    }
  }
}
```

#### 4.4.3 isBusy 维护

```scala
val isBusy = RegInit(false.B)

// 读事务开始
when(mainMemReq.ar.fire) {
  isBusy := true.B
}

// 写事务开始
when(mainMemReq.aw.fire) {
  isBusy := true.B
}

// 读事务结束（last 标志）
when(mainMemResp.r.fire && mainMemResp.r.bits.last) {
  isBusy := false.B
}

// 写事务结束（B 通道握手）
when(mainMemResp.b.fire) {
  isBusy := false.B
}
```

## 5. 验证考虑

### 5.1 功能验证

AXI Arbiter 的关键验证点：

1. **仲裁正确性**：验证 D-Cache > I-Cache 的优先级仲裁逻辑
2. **请求转发**：验证选中的请求正确转发到 MainMemory
3. **响应分发**：验证响应根据事务 ID 正确路由到目标 Cache
4. **状态维护**：验证 `isBusy` 状态的正确转换
5. **握手协议**：验证 Valid/Ready 握手协议的正确性

### 5.2 协议验证

关键协议验证点：

1. **AXI4 协议合规性**：验证所有通道的 Valid/Ready 握手符合 AXI4 规范
2. **事务 ID 分配**：验证 I-Cache 和 D-Cache 的事务 ID 分配正确（0 和 1）
3. **Last 标志处理**：验证读事务的 last 标志正确触发状态转换
4. **写完成确认**：验证写事务的 B 通道握手正确完成

### 5.3 集成验证

关键集成验证点：

1. **I-Cache 接口**：验证与 I-Cache 的请求和响应接口正确性
2. **D-Cache 接口**：验证与 D-Cache 的请求和响应接口正确性
3. **MainMemory 接口**：验证与 MainMemory 的 AXI4 接口正确性
4. **并发请求处理**：验证多个并发请求的正确仲裁和处理
5. **性能测试**：验证仲裁器在高负载下的性能表现
