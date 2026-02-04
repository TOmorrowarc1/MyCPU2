# PRF (Physical Register File) 模块设计文档

## 1. 模块概述

### 1.1 职责
PRF（Physical Register File）存储指令执行过程中产生的中间结果和最终结果。作为 Tomasulo 架构中的数据存储核心，PRF 为执行单元提供操作数，并接收执行单元的结果写回。

### 1.2 设计特点
- **双读单写端口**：每个 PRF 实例内部寄存器堆支持两个读端口和一个写端口
- **统一写回机制**：监听 CDB（Common Data Bus）以获取所有执行单元的结果
- **多实例设计**：为 ALU、BRU、LSU 分别配备独立的 PRF 实例，避免端口冲突

## 2. 接口定义

### 2.1 输入接口

#### 2.1.1 PrfReadPacket（读请求）
来自 RS 的读请求，在 Issue 阶段发起。

```scala
class PrfReadPacket extends Bundle {
  val rs1 = UInt(phyRegIdWidth.W)  // 源寄存器 1 物理编号
  val rs2 = UInt(phyRegIdWidth.W)  // 源寄存器 2 物理编号
}
```

#### 2.1.2 WriteBackData（写回）
来自 CDB 的执行结果，用于更新 PRF。

```scala
class WriteBackData extends Bundle {
  // 目标物理寄存器，当本周期广播指令为 Store 等非 WB 指令时值为 0
  val rd    = UInt(phyRegIdWidth.W)  
  // 写回数据
  val data  = UInt(32.W)            
}
```

### 2.2 输出接口

#### 2.2.1 PrfReadData（读数据）
向 RS/EU 返回的操作数数据。

```scala
class PrfReadData extends Bundle {
  val rData1 = UInt(32.W)  // 源寄存器 1 数据
  val rData2 = UInt(32.W)  // 源寄存器 2 数据
}
```

### 2.3 完整 IO Bundle

```scala
class PRFIO extends Bundle {
  // 读接口（来自 RS）
  val readReq  = Flipped(Decoupled(new PrfReadPacket))
  val readResp = Decoupled(new PrfReadData)

  // 写接口（来自 CBD）
  val write = Flipped(Valid(new WriteBackData))
}
```

## 3. 维护状态

128 个 32 位物理寄存器。
```scala
val phyRegNum = 1 << PhyRegIdWidth
// 创建 128 个 32 位寄存器，复位时全部初始化为 0
val regs = RegInit(VecInit(Seq.fill(phyRegNum)(0.U(32.W))))
```

## 4. 内部逻辑

### 4.1 读请求处理

当 RS 发射指令时，发起读请求，将读出的数据直接发送给执行单元：

```scala
val rData1 = WireDefault(0.U(32.W))
val rData2 = WireDefault(0.U(32.W))
val readReqValid = io.readReq.valid

when(io.readReq.fire) {
  // 读取两个源寄存器
  rData1 := regs(io.readReq.bits.rs1)
  rData2 := regs(io.readReq.bits.rs2)
}

io.readResp.bits.rData1 := rData1
io.readResp.bits.rData2 := rData2
io.readResp.valid := readReqValid
```

### 4.2 写回处理

监听 CDB，当检测到有效写回时更新寄存器：

```scala
when(io.write.valid) {
  val rd = io.write.bits.rd
  val data = io.write.bits.data
  
  when(rd =/= 0.U) {
    regs.write(rd, data)
  }
}
```

## 5. 测试要点

- [ ] 正常读写操作
- [ ] x0 寄存器读写（应始终返回 0）
- [ ] 连续写同一寄存器
- [ ] 读写冲突处理（理论而言不会发生，此时正常读写寄存器即可）