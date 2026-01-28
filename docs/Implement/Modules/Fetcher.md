# Fetcher 模块

## 职责

Fetcher 是流水线前端的第一级，主要负责以下核心功能：

1. **PC 维护与更新**：维护当前程序计数器（`currentPC`）和下一周期程序计数器（`nextPC`），并根据各种控制信号正确更新 PC 值。

2. **取指请求发起**：向指令缓存（Icache）发起取指请求，通过 Decoupled 握手协议控制取指流程。

3. **分支预测协调**：与分支预测模块（BranchPredict）交互，获取分支预测结果并据此更新 `nextPC`。

4. **异常与冲刷响应**：响应来自 ROB 的全局冲刷（GlobalFlush）和来自 BRU 的分支冲刷（BranchFlush）信号，及时重定向 PC。

5. **特权模式传递**：接收并传递当前特权模式（PrivMode）信息，用于后续的内存访问权限检查。

## 接口定义

### 输入接口

| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `insEpoch` | ROB | `UInt` | 指令纪元，用于标识当前取指所属的指令批次 |
| `globalFlush` | ROB | `Bool` | 全局冲刷信号，高电平有效，表示需要重定向到异常处理入口 |
| `globalFlushPC` | ROB | `AddrW` | 全局冲刷的目标 PC |
| `branchFlush` | BRU | `Bool` | 分支冲刷信号，高电平有效，表示分支预测错误需要纠正 |
| `branchFlushPC` | BRU | `AddrW` | 分支冲刷的目标 PC，即正确的分支目标地址 |
| `ifStall` | Decoder | `Bool` | 取指暂停信号，高电平有效，表示需要暂停取指（如遇到 CSR/特权指令） |
| `privMode` | CSRsUnit | `PrivMode()` | 当前特权模式（User/Supervisor/Machine），用于内存访问权限检查 |

### 输出接口

| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `icache` | Icache | `Decoupled(new IFetchPacket)` | 向 Icache 发送取指请求，使用 Decoupled 握手协议 |

**说明**：输出接口使用 `Decoupled(new IFetchPacket)` 封装，其中 `IFetchPacket` 包含以下字段（定义见 [`Protocol.md`](../Protocol.md)）：

```scala
class IFetchPacket extends Bundle with CPUConfig {
  val pc          = AddrW        // 取指地址
  val insEpoch    = EpochW       // 指令纪元
  val prediction  = new Prediction // 分支预测信息
  val exception   = new Exception  // 异常信息
  val privMode    = PrivMode()    // 特权模式
}
```

`Decoupled` 接口提供三个信号：
- **`bits`**：`IFetchPacket` 类型的数据包
- **`valid`**：高电平有效，表示取指请求有效（受 `ifStall` 控制）
- **`ready`**：由 Icache 驱动，表示 Icache 可以接收取指请求

### 内部接口

| 信号名 | 类型 | 描述 |
| :--- | :--- | :--- |
| `currentPC` | `AddrW` | 当前周期的 PC，用于发起取指请求 |
| `nextPC` | `Reg(AddrW)` | 下一周期的 PC，寄存器存储，用于流水线传递 |
| `predict` | `new Prediction` | 分支预测结果，包含目标 PC 和跳转标志 |

## 内部逻辑

### 1. 状态机与寄存器

Fetcher 内部需要维护以下状态：

  - **nextPC 寄存器**：使用 `RegInit` 存储，初始值为复位地址（如 0x8000_0000）。
  ```scala
  val nextPC = RegInit(0x80000000.U(32.W))
  ```

### 2. PC 选择逻辑（判断当前周期 PC）

Fetcher 需要在每个周期根据优先级决定 `currentPC` 的值，优先级从高到低为：

1. **全局冲刷（globalFlush）**：当 `globalFlush` 为高时，表示发生了异常（如 Trap），需要立即跳转到异常处理入口。
   ```scala
   currentPC := io.globalFlushPC
   ```

2. **分支冲刷（branchFlush）**：当 `branchFlush` 为高时，表示分支预测错误，需要跳转到正确的分支目标。
   ```scala
   currentPC := io.branchFlushPC
   ```

3. **正常取指（nextPC）**：若无冲刷信号，则使用上一周期计算好的 `nextPC`。
   ```scala
   currentPC := nextPC
   ```

**实现示例**：
```scala
val currentPC = MuxCase(nextPC, Seq(
  (io.globalFlush) -> io.globalFlushPC,
  (io.branchFlush) -> io.branchFlushPC
))
```

### 3. 取指请求发起逻辑（使用 Decoupled）

Fetcher 通过 Decoupled 握手协议与 Icache 通信：

- **Valid 信号**：由 `ifStall` 控制。当 `ifStall` 为高时，`valid` 为低，暂停取指；否则 `valid` 为高，表示取指请求有效。
  > 当 `Flush` 信号发生时，无视 `ifStall` 以确保能够及时发起取指请求。
  ```scala
  io.icache.valid := !io.ifStall || is_flush
  ```

- **Bits 信号**：填充 `IFetchPacket` 数据包（部分字段计算方式见后）
  ```scala
  io.icache.bits.pc := currentPC
  io.icache.bits.prediction := predict
  io.icache.bits.exception := fetchException
  io.icache.bits.privMode := io.privMode
  ```

- **Ready 信号**：由 Icache 提供，表示 Icache 可以接收取指请求。

- **Fire 条件**：当 `io.icache.fire`（即 `valid && ready`）为真时，表示取指握手成功，数据成功从 Fetcher 流向 Icache。

### 4. NextPC 更新逻辑

`nextPC` 的更新取决于取指握手是否成功：

- **握手成功（fire）**：
  1. 将 `currentPC` 送入 `BranchPredict` 模块。
  2. 获取预测结果 `predict`（类型为 `new Prediction`），包含：
     - `targetPC`：预测的目标 PC
     - `taken`：是否跳转的标志
    > 分支预测方式见后。
  3. 更新 `nextPC` 为预测的 PC：
     ```scala
     when (io.icache.fire) {
       nextPC := predict.targetPC
     }
     ```

- **握手失败**：
  - `nextPC` 维持当周期 PC，等待下一周期再次尝试取指：
    ```scala
    .otherwise {
      nextPC := currentPC
    }
    ```

### 5. 分支预测协调

Fetcher 需要与分支预测模块（BranchPredict）紧密配合：

- **预测输入**：将 `currentPC` 传递给 BranchPredict 模块。
- **预测输出**：接收预测结果，类型为 `new Prediction`（定义见 [`Protocol.md`](../Protocol.md)），包括：
  - `targetPC`：如果预测跳转，则为目标地址；否则为 `currentPC + 4`（假设 32 位指令）
  - `taken`：指示是否预测跳转

**预测逻辑示例**：
```scala
val predict = Wire(new Prediction)
predict := BranchPredictModule.io.predict(currentPC)
```

### 6. 异常处理

Fetcher 需要处理的通过 PC 发起请求阶段的异常为`INSTRUCTION_ADDRESS_MISALIGNED`，若发生需要标注到 exception 中。

**异常打包示例**：
```scala
val fetchException = Wire(new Exception)
fetchException.valid := exceptionDetected
fetchException.cause := ExceptionCause.INSTRUCTION_ADDRESS_MISALIGNED
fetchException.tval := currentPC
```

## 完整伪代码

```scala
class Fetcher extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 输入
    val insEpoch = Input(UInt())
    val globalFlush = Input(Bool())
    val globalFlushPC = Input(AddrW)
    val branchFlush = Input(Bool())
    val branchFlushPC = Input(AddrW)
    val ifStall = Input(Bool())
    val privMode = Input(PrivMode())
    val icache = Flipped(Decoupled(new IFetchPacket))
    
    // 输出
    val icache = Decoupled(new IFetchPacket)
  })
  
  // 内部状态
  val nextPC = RegInit(0x80000000.U(32.W))
  
  // 1. PC 选择逻辑
  val currentPC = MuxCase(nextPC, Seq(
    (io.globalFlush) -> io.globalFlushPC,
    (io.branchFlush) -> io.branchFlushPC
  ))
  
  // 2. 异常检测与处理
  val fetchException = Wire(new Exception)
  fetchException.valid := currentPC(1,0) =/= 0.U
  fetchException.cause := ExceptionCause.INSTRUCTION_ADDRESS_MISALIGNED
  fetchException.tval := currentPC

  // 3. 分支预测
  val predict = Wire(new Prediction)
  predict := branchPredictModule.io.predict(currentPC)
  
  // 4. 取指请求发起（使用 Decoupled）
  io.icache.valid := (!io.ifStall) || (io.globalFlush || io.branchFlush)
  io.icache.bits.pc := currentPC
  io.icache.bits.prediction := predict
  io.icache.bits.exception := fetchException
  io.icache.bits.privMode := io.privMode
  
  // 5. NextPC 更新
  when (io.icache.fire) {
    nextPC := predict.targetPC
  }
  .otherwise {
    nextPC := currentPC
  }
  
}
```