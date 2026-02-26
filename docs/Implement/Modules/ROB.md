# ROB (Reorder Buffer) 模块设计文档

## 职责

ROB 是乱序执行核心中的"指令提交中心"和"架构状态维护者"，主要负责以下核心功能：

1.  **维护指令顺序（In-order Commit）**：确保指令虽然乱序执行，但严格按照程序序（Program Order）退休，从而维护精确异常（Precise Exception）。
2.  **管理物理寄存器生命周期**：在指令提交时，通知 RAT 回收被覆盖的旧物理寄存器（`preRd`）到 Free List。
3.  **异常与中断的终审**：作为异常处理的唯一入口，仅当异常指令到达队列头部时才触发 Trap 流程，防止推断执行的错误触发异常。
4.  **序列化指令控制**：处理 `Store`、`CSR`、`MRET/SRET`、`SFENCE.VMA`、`FENCE.I` 等具有副作用或需要序列化的指令，协调 LSU 和 CSRsUnit 进行原子操作。
5.  **冲刷与重定向**：产生 `GlobalFlush` 信号，管理 `IEpoch`（指令纪元）和 `DEpoch`（数据纪元），并向 Fetcher 提供重定向 PC。
6.  **全局控制信息维护**：管理 `CSRPending` 信号，标记 CSR 指令或某些改变 CSR 状态指令正在提交中以阻塞前端取指。

---

## 接口定义

### 输入接口

| 信号名         | 来源     | 类型                                     | 描述                                                                         |
| :------------- | :------- | :--------------------------------------- | :--------------------------------------------------------------------------- |
| **指令入队**   |          |                                          |                                                                              |
| `controlInit`  | Decoder  | `Flipped(Decoupled(new ROBInitControl))` | 接收解码模块传入的指令控制信息（pc, prediction, exception, specialInstr）    |
| `dataInit`     | RAT      | `Flipped(Decoupled(new ROBinitData))`    | 接受重命名寄存器表传入的指令数据依赖信息（archRd, phyRd, preRd, branchMask） |
| **完成与冲刷** |          |                                          |                                                                              |
| `cdb`          | CDB      | `Flipped(Decoupled(new CDBMessage))`     | 监听公共数据总线，标记指令完成或待机，携带物理目标寄存器结果                 |
| `branchOH`     | BRU      | `Input(SnapshotMask)`                    | 独热码，表示 BRU 求解出的分支指令对应的 Snapshot ID                          |
| `branchFlush`  | BRU      | `Input(Bool)`                            | 分支预测错误信号，触发分支冲刷                                               |
| `branchRobId`  | BRU      | `Input(RobTag)`                          | 分支指令在 ROB 中的 ID，用于定位和更新预测信息                               |
| `redirectPC`   | BRU      | `Input(UInt(32.W))`                      | 分支预测错误时的重定向 PC                                                    |
| `globalFlush`  | CSRsUnit | `Input(Bool)`                            | 全局冲刷信号（异常或中断处理）                                               |

### 输出接口

| 信号名             | 目标         | 类型                       | 描述                                                            |
| :----------------- | :----------- | :------------------------- | :-------------------------------------------------------------- |
| **分配与控制**     |              |                            |                                                                 |
| `freeRobID`        | Decoder      | `Output(RobTag)`           | 当前分配给新指令的 ROB ID（即 Tail 指针），用于 Rename 阶段     |
| `csrPending`       | Decoder      | `Output(Bool)`             | 指示当前 ROB 中是否有未提交的 CSR 指令，用于阻塞前端取指        |
| **纪元信息**       |              |                            |                                                                 |
| `iEpoch`           | Fetcher      | `Output(EpochW)`           | 指令纪元，用于内存访问的顺序一致性维护                          |
| `dEpoch`           | MemorySystem | `Output(EpochW)`           | 数据纪元，用于内存访问的顺序一致性维护                          |
| **提交与回收**     |              |                            |                                                                 |
| `commitRAT`        | RAT          | `Decoupled(new CommitRAT)` | 提交信号。通知 RAT 更新架构映射并释放 `preRd`                   |
| **特殊指令控制**   |              |                            |                                                                 |
| `storeEnable`      | LSU          | `Output(Bool)`             | 通知 LSU 执行 Store 的物理写操作（仅在队头时有效）              |
| `csrEnable`        | ZICSRU       | `Output(Bool)`             | 通知 ZICSRU 执行 CSR 的物理写操作（仅在队头时有效）             |
| **异常与特权处理** |              |                            |                                                                 |
| `exception`        | CSRsUnit     | `Output(new Exception)`    | 异常信息（valid, cause, tval），用于 Trap 处理                  |
| `isCSR`            | CSRsUnit     | `Output(Bool())`           | 当前提交指令是否为 CSR 指令，用于 CSRsUnit 判断是否触发全局冲刷 |
| `mret`             | CSRsUnit     | `Output(Bool())`           | MRET 指令提交信号                                               |
| `sret`             | CSRsUnit     | `Output(Bool())`           | SRET 指令提交信号                                               |
| **内存同步**       |              |                            |                                                                 |
| `fenceI`           | Cache        | `Output(Bool())`           | FENCE.I 指令提交信号，用于 I-Cache 同步                         |
| `sfenceVma`        | MMU          | `Output(new SFenceReq)`    | SFENCE.VMA 指令提交信号，用于 TLB 刷新                          |

---

## 数据结构

### ROB Entry (存储条目)

ROB 内部维护一个环形缓冲区（Circular Buffer），每个条目包含以下信息：

```scala
class ROBEntry extends Bundle with CPUConfig {
  // 1. 状态位
  val busy        = Bool()       // 条目是否有效（已入队且未退休）
  val completed   = Bool()       // 指令是否执行完毕（来自 CDB）

  // 2. 指令控制数据（来自 Decoder）
  val pc          = AddrW        // 指令 PC（用于 mepc/sepc）
  val prediction  = new Prediction // 分支预测信息（taken, targetPC）
  val exception   = new Exception // 异常信息（valid, cause, tval）
  val specialInstr = SpecialInstr() // 特殊指令标记（BRANCH, STORE, CSR, MRET, SRET, SFENCE, FENCE, FENCEI, ECALL, EBREAK, WFI, NONE）

  // 3. 指令资源管理（来自 RAT）
  val archRd      = ArchTag      // 架构目标寄存器
  val phyRd       = PhyTag       // 物理目标寄存器
  val preRd       = PhyTag       // 旧物理寄存器（Commit 时释放）
  val branchMask  = SnapshotMask // 分支掩码，代表依赖的分支（用于冲刷）

  // 4. 特殊标记
  val hasSideEffect = Bool()     // 是否有副作用（非幂等 Load 指令专用）
}
```

---

## 内部逻辑

### 1. 全部需维护状态

- **Head Ptr**: 指向最早进入 ROB 的指令（提交端），类型为 `RegInit(0.U(RobIdWidth.W))`
- **Tail Ptr**: 指向下一个空闲位置（分发端），类型为 `RegInit(0.U(RobIdWidth.W))`
- **Count**: 当前有效条目数，用于生成 `controlInit.ready` 和 `dataInit.ready`
- **Commit FSM**: 用于处理 Head 指令的复杂提交逻辑（如 Store/CSR 的握手）
  - `s_IDLE`: 正常检查 Head
  - `s_WAIT`: 等待 Store/CSR/FENCE.I/SFENCE.VMA 完成
- **Epoch 寄存器**:
  - `iEpoch`: 指令纪元（2-bit），在分支冲刷或全局冲刷时更新
  - `dEpoch`: 数据纪元（2-bit），仅在全局冲刷时更新
- **CSRPending 寄存器**: 标记当前 ROB 中是否有未提交的 CSR 指令

### 2. 全局控制信息维护

**纪元管理**:
- `iEpoch`: 在 `branchFlush` 或 `globalFlush` 时更新
- `dEpoch`: 仅在 `globalFlush` 时更新

**CSRPending 维护**:
- 当 CSR 指令或 xRET 指令或 `FENCE`、`FENCE.I`、`SFENCE` 入队时，拉高 `CSRPending` 信号
- 在这类指令提交时拉低该信号（由于阻塞取指一段时间内只有一条该类指令）

### 3. 队列维护

维护一个最大长度确定的循环队列（32 条指令），包含队头 `robHead` 与队尾 `robTail`。

**Full 判断**: 若满则阻塞 Decoder 入队信号，将 Decoder 与 RAT 接口 ready 拉低。
```scala
val full = (robHead === robTail) && robQueue(robHead).busy
```

**Empty 判断**:
```scala
val empty = (robHead === robTail) && !robQueue(robHead).busy
```

### 4. 指令入队

当 ROB 未满时，将 Decoder 输入接口 `ready` 拉高，输出对应 `RobTail` 位置创建新 Entry。

**入队条件**:
```scala
val canEnqueue = !full && !io.globalFlush && !io.branchFlush
```

**入队逻辑**:
1. 当 `io.controlInit.fire` 和 `io.dataInit.fire` 时：
   - 根据 `io.controlInit.bits` 填写 `pc`, `prediction`, `exception`, `specialInstr`
   - 根据 `io.dataInit.bits` 填写 `archRd`, `phyRd`, `preRd`, `branchMask`
   - 初始化状态：`busy := true.B`, `completed := false.B`
   - 如果进入时指令已经出现异常（`exception.valid === true.B`），则 `completed := true.B`
   - `hasSideEffect := false` 该标志可能在之后由 CDB 结果驱动更新。
2. `robTail := robTail + 1.U`
3. 更新 `CSRPending` 信号

### 5. 指令完成

当 CDB 送入 `{RobID, Exception, phyRd, data, hasSideEffect}` 时：

1. 找到对应 Entry（通过 `io.cdb.bits.robId` 索引）
2. 将 `completed` 置 1
3. 若 `Exception` 有效则更新 Entry 的 `Exception` 字段
4. 更新 `hasSideEffect` 标记（用于非幂等 Load 指令）

### 6. 指令提交

每个周期检查 `rob[robHead]`：

#### 状态机定义

```scala
object CommitState extends ChiselEnum {
  val s_IDLE, s_WAIT = Value
}

val commitState = RegInit(CommitState.s_IDLE)
```

**步骤 1: Check Head**
- 若 `!completed`，等待（阻塞）
- 若 `!busy`，跳过（空队列）
- 否则进入提交流程：

**步骤 2: Handle Serialization**

根据 `specialInstr` 类型或提交，或进入等待状态等待 CDB 上的二次提交，但拉高不同信号：

- **Store 指令** (`specialInstr === SpecialInstr.STORE`):
  - 进入 `s_WAIT` 状态
  - 拉高 `io.storeEnable` 信号
  - 等待 LSU 的确认信号（通过 CDB 第二次对应 `robId` 信号）
  > 注意：CDB 第二次传来信号时仍可能携带异常信息，需要再次检查异常并处理，以下亦然。
  - 收到确认后当周期执行正常退休流程。状态机跳转回 `s_IDLE`。

- **CSR Write 指令** (`specialInstr === SpecialInstr.CSR`):
  - 进入 `s_WAIT` 状态
  - 拉高 `io.csrEnable` 信号
  - 等待 ZICSRU 的确认信号（通过 CDB 第二次对应 `robId` 信号）
  - 收到确认后：
    - 向 CSRsUnit 发送对应信息 `io.isCSR`，从而触发全局冲刷
    - 当周期执行正常退休流程

- **xRET 指令** (`specialInstr === SpecialInstr.MRET || specialInstr === SpecialInstr.SRET`):
  - 向 CSRsUnit 发送对应信息（`io.mret` 或 `io.sret`），触发全局冲刷
  - 当周期执行正常退休流程

- **WFI 指令** (`specialInstr === SpecialInstr.WFI`):
  - 当做 NOP 处理，直接进入退休流程

- **FENCE.I 指令** (`specialInstr === SpecialInstr.FENCEI`):
  - 进入 `s_WAIT` 状态
  - 拉高 `io.fenceI` 信号
  - 等待 CDB 返回信号
  - 收到信号后当周期执行正常退休流程

- **SFENCE.VMA 指令** (`specialInstr === SpecialInstr.SFENCE`):
  - 进入 `s_WAIT` 状态
  - 拉高 `io.sfenceVma` 信号
  - 等待 CDB 返回信号
  - 收到信号后当周期执行正常退休流程

**步骤 3: Retire**
- 通知 RAT `Retire(archRd, phyRd, preRd)`：
  - `io.commitRAT.valid := true.B`
  - `io.commitRAT.bits.archRd := entry.archRd`
  - `io.commitRAT.bits.phyRd := entry.phyRd`
  - `io.commitRAT.bits.preRd := entry.preRd`
- 释放 `preRd` 到 Free List
- `robHead := (robHead + 1).U` 由于队列长度为2的幂次，自然形成回绕。
- 更新 `CSRPending` 信号（如果当前提交的是 CSR 指令）
- 若 `exception.valid === true.B`：向 CSRsUnit 输出该指令 `exception` 与 `pc` 信息，以便 CSRsUnit 做出 Trap 处理，正常执行退休流程。

### 7. 分支冲刷

当 BRU 送入 `{branchOH, branchFlush, branchRobId, redirectPC}` 时：

1. 若 `branchFlush` 有效：
   - 根据 `branchOH` 定位到对应分支指令
   - 清除所有依赖该分支的 Entry（通过 `branchMask` 位运算）：`entry.busy := entry.busy && !(entry.branchMask & io.branchOH).orR`
   - 更新 `iEpoch`（翻转纪元）
   - 使用 `branchRobId` 直接定位并更新对应 Entry 的 `prediction` 字段（用于提交时更新 BTB）
2. 若 `branchFlush` 无效（预测正确）：
   - 单纯移除 `branchMask`：`entry.branchMask := entry.branchMask & ~io.branchOH`

### 8. 全局冲刷处理

当 `io.globalFlush` 有效时：

1. 清空所有 ROB 条目：`robQueue(i).busy := false.B`
2. 重置 Head/Tail 指针：`robHead := 0.U`, `robTail := 0.U`
3. 更新 `iEpoch` 和 `dEpoch`
4. 重置 `CSRPending` 信号：`csrPending := false.B`
5. 重置提交状态机：`commitState := CommitState.s_IDLE`
6. 所有输出信号置为默认状态
  
> 注意：当 branchFlush 或 globalFlush 信号被拉高时，其他的提交，更新等行为都被阻塞，即 ready 被拉低。

---

## 伪代码示例

```scala
object CommitState extends ChiselEnum {
  val s_IDLE, s_WAIT = Value
}

class ROB extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 输入接口
    val controlInit = Flipped(Decoupled(new ROBInitControl))
    val dataInit = Flipped(Decoupled(new ROBInitData))
    val cdb = Flipped(Decoupled(new CDBMessage))
    val branchOH = Input(SnapshotMask)
    val branchFlush = Input(Bool())
    val branchRobId = Input(RobTag)  // 分支指令在 ROB 中的 ID，用于定位和更新预测信息
    val redirectPC = Input(UInt(32.W))
    val globalFlush = Input(Bool())

    // 输出接口
    val freeRobID = Output(RobTag)
    val csrPending = Output(Bool())
    val iEpoch = Output(EpochW)
    val dEpoch = Output(EpochW)
    val commitRAT = Decoupled(new CommitRAT)
    val storeEnable = Output(Bool())
    val csrEnable = Output(Bool())
    val exception = Output(new Exception)
    val isCSR = Output(Bool())
    val mret = Output(Bool())
    val sret = Output(Bool())
    val fenceI = Output(Bool())
    val sfenceVma = Output(Bool())
  })

  // ==================== 1. 状态定义 ====================
  
  // ROB 环形缓冲区
  val robQueue = Reg(Vec(RobSize, new ROBEntry))
  
  // 队列指针
  val robHead = RegInit(0.U(RobIdWidth.W))
  val robTail = RegInit(0.U(RobIdWidth.W))
  val robCount = RegInit(0.U(RobIdWidth.W))
  
  // 纪元寄存器
  val iEpoch = RegInit(0.U(EpochW.W))
  val dEpoch = RegInit(0.U(EpochW.W))
  
  // CSR 指令待处理标志
  val csrPending = RegInit(false.B)
  
  // 提交状态机
  val commitState = RegInit(CommitState.s_IDLE)
  
  // ==================== 2. 队列状态判断 ====================
  
  val full = (robHead === robTail) && robQueue(robHead).busy
  val empty = (robHead === robTail) && !robQueue(robHead).busy
  
  // ==================== 3. 入队逻辑 ====================
  
  // 入队条件
  val needFlush = io.globalFlush || io.branchFlush
  val canEnqueue = !full && !needFlush
  
  io.controlInit.ready := canEnqueue
  io.dataInit.ready := canEnqueue
  io.cdb.ready := !needFlush // 当需要冲刷时，CDB 的完成信号也被阻塞
  io.freeRobID := Mux(canEnqueue, robTail, 0.U) // 只有可以入队时才输出有效 ID
  
  // 当 Decoder 和 RAT 都准备好时，创建新 Entry
  when(io.controlInit.fire && io.dataInit.fire) {
    val entry = robQueue(robTail)
    
    // 填写指令控制数据
    entry.pc := io.controlInit.bits.pc
    entry.prediction := io.controlInit.bits.prediction
    entry.exception := io.controlInit.bits.exception
    entry.specialInstr := io.controlInit.bits.specialInstr
    
    // 填写资源管理数据
    entry.archRd := io.dataInit.bits.archRd
    entry.phyRd := io.dataInit.bits.phyRd
    entry.preRd := io.dataInit.bits.preRd
    entry.branchMask := io.dataInit.bits.branchMask
    
    // 初始化状态
    entry.busy := true.B
    entry.hasSideEffect := false.B
    
    // 如果入队时已有异常，标记为已完成
    entry.completed := io.controlInit.bits.exception.valid
    
    // 更新队尾指针
    robTail := robTail + 1.U
    
    // 更新 CSRPending 信号
    val isCSRInstr = 
      io.controlInit.bits.specialInstr === SpecialInstr.CSR || 
      io.controlInit.bits.specialInstr === SpecialInstr.MRET ||
      io.controlInit.bits.specialInstr === SpecialInstr.SRET ||
      io.controlInit.bits.specialInstr === SpecialInstr.FENCE ||
      io.controlInit.bits.specialInstr === SpecialInstr.FENCEI ||
      io.controlInit.bits.specialInstr === SpecialInstr.SFENCE
    when(isCSRInstr) {
      csrPending := true.B
    }
  }
  
  // ==================== 4. 指令完成逻辑 ====================
  when(io.cdb.fire) {
    val robId = io.cdb.bits.robId
    val entry = robQueue(robId)
    
    // 标记指令完成
    entry.completed := true.B
    
    // 更新异常信息
    when(io.cdb.bits.exception.valid) {
      entry.exception := io.cdb.bits.exception
    }
    
    // 更新副作用标记
    entry.hasSideEffect := io.cdb.bits.hasSideEffect
  }
  
  // ==================== 5. 提交逻辑 ====================
  
  // 默认输出值
  io.storeEnable := false.B
  io.csrEnable := false.B
  io.isCSR := false.B
  io.mret := false.B
  io.sret := false.B
  io.fenceI := false.B
  io.sfenceVma := false.B
  io.commitRAT.valid := false.B
  io.exception.valid := false.B
  
  // 获取队头条目
  val headEntry = robQueue(robHead)
  
  // 状态机逻辑
  switch(commitState) {
    is(CommitState.s_IDLE) {
      // 检查队头
      when(!empty && headEntry.completed && !needFlush) {
        // 根据指令类型处理
        switch(headEntry.specialInstr) {
          // Store 指令
          is(SpecialInstr.STORE) {
            commitState := CommitState.s_WAIT
            io.storeEnable := true.B
          }
          // CSR 写指令
          is(SpecialInstr.CSR) {
            commitState := CommitState.s_WAIT
            io.csrEnable := true.B
          }
          // xRET 指令
          is(SpecialInstr.MRET) {
            io.mret := true.B
            retireHead(headEntry)
          }
          is(SpecialInstr.SRET) {
            io.sret := true.B
            retireHead(headEntry)
          }
          // FENCE.I 指令
          is(SpecialInstr.FENCEI) {
            commitState := CommitState.s_WAIT
            io.fenceI := true.B
          }
          // SFENCE.VMA 指令
          is(SpecialInstr.SFENCE) {
            commitState := CommitState.s_WAIT
            io.sfenceVma := true.B
          }
          // WFI 指令（当作 NOP）
          is(SpecialInstr.WFI) {
            retireHead(headEntry)
          }
          // 普通指令
          is(SpecialInstr.NONE) {
            retireHead(headEntry)
          }
          // 其他指令类型
          is(_) {
            retireHead(headEntry)
          }
        }
      }
    }
    
    is(CommitState.s_WAIT) {
      // 等待 CDB 确认信号
      when(io.cdb.fire && io.cdb.bits.robId === robHead) {
        // 根据指令类型处理
        switch(headEntry.specialInstr) {
          // CSR 指令需要触发全局冲刷
          is(SpecialInstr.CSR) {
            io.isCSR := true.B
          }
        }
        // 执行退休
        retireHead(headEntry)
        commitState := CommitState.s_IDLE
      }
    }
  }
  
  // 退休函数
  def retireHead(entry: ROBEntry): Unit = {
    // 通知 RAT 更新架构映射并释放 preRd
    io.commitRAT.valid := true.B
    io.commitRAT.bits.archRd := entry.archRd
    io.commitRAT.bits.phyRd := entry.phyRd
    io.commitRAT.bits.preRd := entry.preRd
    
    // 将队头置空
    entry.busy := false.B
    // 更新队头指针
    robHead := robHead + 1.U
    
    // 更新 CSRPending 信号
    val isCSRInstr = entry.specialInstr === SpecialInstr.CSR ||
                     entry.specialInstr === SpecialInstr.MRET ||
                     entry.specialInstr === SpecialInstr.SRET ||
                     entry.specialInstr === SpecialInstr.FENCE ||
                     entry.specialInstr === SpecialInstr.FENCEI ||
                     entry.specialInstr === SpecialInstr.SFENCE
    when(isCSRInstr) {
      csrPending := false.B
    }
    
    // 处理异常
    when(entry.exception.valid) {
      io.exception := entry.exception
    }
  }
  
  // ==================== 6. 分支冲刷逻辑 ====================
  
  when(io.branchFlush && !io.globalFlush) {
    // 清除所有依赖该分支的 Entry
    for(i <- 0 until RobSize) {
      val entry = robQueue(i)
      val shouldFlush = (entry.branchMask & io.branchOH).orR
      when(shouldFlush) {
        entry.busy := false.B
      }
    }
    
    // 更新指令纪元
    iEpoch := iEpoch + 1.U
    
    // 更新对应 Entry 的 prediction 字段（使用 branchRobId 定位）
    robQueue(io.branchRobId).prediction.taken := !robQueue(io.branchRobId).prediction.taken
  }.otherwise {
    // 预测正确，移除 branchMask
    for(i <- 0 until RobSize) {
      robQueue(i).branchMask := robQueue(i).branchMask & ~io.branchOH
    }
  }
  
  // ==================== 7. 全局冲刷逻辑 ====================
  
  when(io.globalFlush) {
    // 清空所有 ROB 条目
    for(i <- 0 until RobSize) {
      robQueue(i).busy := false.B
    }
    
    // 重置指针
    robHead := 0.U
    robTail := 0.U
    
    // 更新纪元
    iEpoch := iEpoch + 1.U
    dEpoch := dEpoch + 1.U
    
    // 重置 CSRPending
    csrPending := false.B
    
    // 重置状态机
    commitState := CommitState.s_IDLE
  }
  
  // ==================== 8. 输出信号 ====================
  
  io.csrPending := csrPending
  io.iEpoch := iEpoch
  io.dEpoch := dEpoch
}
```

---

## 测试

### 1. 正确性测试

- 正常指令流：验证指令正确入队、完成和退休
- 分支预测正确：验证分支指令正确更新预测信息，无冲刷发生
- 阻塞情况：验证当 ROB 满时 Decoder 入队被阻塞

### 2. 精确异常处理

- 异常仅在指令到达队头且 `completed` 时才触发
- 异常处理会触发全局冲刷，清空整个流水线
- 异常指令不会更新 RAT 或回收物理寄存器

### 3. 特殊指令序列化

- Store、CSR、xRET、FENCE 等指令需要序列化执行
- 这些指令在队头时才会触发相应的操作
- 通过状态机等待外部模块的确认信号

### 4. 分支预测错误处理

- 分支预测错误会触发分支冲刷
- 通过 `branchMask` 清除依赖该分支的指令
- 使用 `branchRobId` 直接定位并更新分支指令的预测信息
- 更新 `iEpoch` 以标记指令纪元变更

### 5. 纪元管理

- `iEpoch`: 用于指令和内存访问的顺序一致性
- `dEpoch`: 用于数据访问的顺序一致性
- 纪元更新确保内存操作的顺序正确性

### 6. CSRPending 信号

- 阻塞前端取指，确保 CSR 指令序列化执行
- 在 CSR 指令入队时拉高，提交时拉低

### 7. 非幂等 Load 处理

- 通过 `hasSideEffect` 标记非幂等 Load 指令
- 这些指令需要在队头时才能真正执行内存访问

### 8. 全局冲刷处理

- 触发全局冲刷后，ROB 应立即清空所有条目，重置指针，并更新纪元
