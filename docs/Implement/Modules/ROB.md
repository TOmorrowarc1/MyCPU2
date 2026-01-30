# ROB (Reorder Buffer) 模块设计文档

## 职责

ROB 是乱序执行核心中的“指令提交中心”和“架构状态维护者”，主要负责以下核心功能：

1.  **维护指令顺序（In-order Commit）**：确保指令虽然乱序执行，但严格按照程序序（Program Order）退休，从而维护精确异常（Precise Exception）。
2.  **管理物理寄存器生命周期**：在指令提交时，通知 RAT 回收被覆盖的旧物理寄存器（`prd_old`）到 Free List。
3.  **异常与中断的终审**：作为异常处理的唯一入口，仅当异常指令到达队列头部时才触发 Trap 流程，防止推断执行的错误触发异常。
4.  **序列化指令控制**：处理 `Store`、`CSR`、`MRET/SRET`、`SFENCE.VMA` 等具有副作用或需要序列化的指令，协调 LSU 和 CSRUnit 进行原子操作。
5.  **冲刷与重定向**：产生 `GlobalFlush` 信号，管理 `D-Epoch`（数据纪元）和 `I-Epoch`（指令纪元），并向 Fetcher 提供重定向 PC。

---

## 接口定义

### 输入接口

| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `dispatch` | Decoder | `Flipped(Decoupled(new MicroOp))` | 接收分发阶段的指令元数据（含 PC, LRD, PRD, PRD_Old, Exception, Mask 等） |
| `cdb` | CBD | `Valid(new CDBPacket)` | 监听公共数据总线。包含 `rob_id`、`exception`、分支结果等。**注意：这是广播接口，无反压** |
| `lsu_store_ack`| LSU | `Bool` | 来自 LSU 的握手信号，表示 Head 的 Store 操作已物理写入 Cache/Buffer |
| `csr_ack` | CSRUnit | `Bool` | 来自 CSRUnit 的握手信号，表示 CSR 读写已完成 |
| `branch_kill` | BRU | `Valid(UInt(4.W))` | 来自 BRU 的快速冲刷信号，携带 `kill_mask`，用于清除 ROB 内的推断路径 |

### 输出接口

| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `rob_id_tail` | Decoder | `UInt` | 当前分配给新指令的 ROB ID（即 Tail 指针），用于 Rename 阶段 |
| `commit` | RAT/PRF | `Valid(new CommitPacket)` | 提交信号。通知 RAT 更新架构映射并释放 `prd_old` |
| `lsu_commit` | LSU | `Valid(new LSUCommitPacket)` | 通知 LSU 执行 Store 或原子操作的物理写，携带 `rob_id` |
| `csr_commit` | CSRUnit | `Valid(new CSRCommitPacket)` | 通知 CSRUnit 执行物理写操作 |
| `flush` | 全局 | `Valid(new FlushPacket)` | **全局冲刷信号**。包含 `redirect_pc`、`new_d_epoch`、`new_i_epoch`。高扇出 |
| `csr_pending` | Decoder | `Bool` | 指示当前 ROB 中是否有未提交的 CSR 指令，用于阻塞前端取指 |

---

## 数据结构

### ROB Entry (存储条目)

ROB 内部维护一个环形缓冲区（Circular Buffer），每个条目包含以下信息：

```scala
class ROBEntry extends Bundle with CPUConfig {
  // 1. 状态位
  val busy        = Bool()       // 条目是否有效
  val completed   = Bool()       // 指令是否执行完毕 (来自 CDB)
  
  // 2. 指令元数据 (来自 Dispatch)
  val pc          = AddrW        // 指令 PC (用于 mepc/sepc)
  val instruction = UInt(32.W)   // (可选) 原始指令，用于 mtval 记录非法指令
  val instrType   = InstrType()  // 枚举：ALU, STORE, LOAD, CSR, MRET, SRET, SFENCE...
  
  // 3. 资源管理
  val lrd         = UInt(5.W)    // 逻辑目标寄存器
  val prd         = UInt(PhyRegW)// 物理目标寄存器
  val prd_old     = UInt(PhyRegW)// 旧物理寄存器 (Commit 时释放)
  val has_write_rf= Bool()       // 是否写寄存器堆 (Store/Branch 为 false)

  // 4. 异常与预测
  val exception   = new Exception// 异常信息 (valid, cause, tval)
  val br_mask     = UInt(4.W)    // 依赖的分支掩码
  val br_mispred  = Bool()       // 分支预测是否错误 (来自 CDB)
  val br_target   = AddrW        // 正确跳转地址 (来自 CDB)
}
```

---

## 内部逻辑

### 1. 状态机与指针

- **Head Ptr**: 指向最早进入 ROB 的指令（提交端）。
- **Tail Ptr**: 指向下一个空闲位置（分发端）。
- **Count**: 当前有效条目数，用于生成 `dispatch.ready`。
- **Commit FSM**: 用于处理 Head 指令的复杂提交逻辑（如 Store/CSR 的握手）。
  - `s_IDLE`: 正常检查 Head。
  - `s_WAIT_LSU`: 等待 Store 完成。
  - `s_WAIT_CSR`: 等待 CSR 完成。

- **Epoch 寄存器**:
  - `d_epoch`: 数据纪元 (1-bit)，仅在 Trap/CSR 写时翻转。
  - `i_epoch`: 指令纪元 (2-bit)，在 Trap/CSR 写/分支预测错误时更新。

### 2. 分发逻辑 (Dispatch)

当 `io.dispatch.fire` 时：
1. 将 `io.dispatch.bits` 写入 `rob_ram[tail]`。
2. 初始化状态：`busy=true`, `completed=false` (除非 Dispatch 时已标记异常，则 `completed=true`)。
3. `tail <= tail + 1`。

### 3. 写回逻辑 (Writeback)

当 `io.cdb.valid` 为高时：
1. 根据 `io.cdb.bits.rob_id` 作为索引，直接访问 ROB 条目。
2. 置 `completed := true`。
3. **异常合并**：如果 CDB 携带异常（如 Load Page Fault），更新条目的 `exception` 字段。
4. **分支更新**：如果是分支指令，记录 `mispredict` 和 `target`。

当 `io.branch_kill.valid` 为高时（快速冲刷）：
1. 遍历所有条目（或使用掩码逻辑）。
2. 若 `entry.br_mask` 与 `kill_mask` 匹配，则标记为 `killed` 或直接置 `busy=false`（注意：指针回滚逻辑较复杂，大作业可简化为标记 Invalid 等待 Head 丢弃，或者直接 Flush 整个后端）。

### 4. 提交逻辑 (Commit) —— 核心状态机

每个周期检查 `rob_ram[head]`：

#### 场景 A：指令未完成
- 若 `!completed`，保持阻塞（Stall）。

#### 场景 B：发生异常 (Exception / Trap)
- 若 `exception.valid` 为真：
  1. **不执行写回**（不更新 RAT，不回收 PRF）。
  2. 触发 **Trap 处理流程**：
     - 更新 CSR (`mcause`, `mepc`, `mtval`, `mstatus`)。
     - 计算跳转目标（`mtvec` 或 `stvec`）。
     - 翻转 `d_epoch` 和 `i_epoch`。
  3. 发出 `io.flush` 信号，清空全流水线。
  4. 重置 Head/Tail 指针。

#### 场景 C：特殊指令序列化 (Store / CSR / Fence)
- **Store 指令**：
  - 进入 `s_WAIT_LSU` 状态。
  - 拉高 `io.lsu_commit.valid`。
  - 等待 `io.lsu_store_ack`。收到 Ack 后，执行正常退休流程。
- **CSR / xRET 指令**：
  - 进入 `s_WAIT_CSR` 状态。
  - 拉高 `io.csr_commit.valid`。
  - 等待 `io.csr_ack`。
  - 完成后，**强制触发 Global Flush**（因为特权级或地址空间可能已变）。
  - 跳转地址：普通 CSR 为 `PC+4`，xRET 为 `epc`。

#### 场景 D：正常退休 (ALU / Load / Correct Branch)
- **资源回收**：
  - `io.commit.valid := true`
  - `io.commit.bits.prd_old := entry.prd_old` (通知 FreeList 回收)
  - `io.commit.bits.lrd := entry.lrd` (通知 ArchRAT 更新)
- **指针移动**：
  - `head <= head + 1`
  - 性能计数器 `minstret++`。

#### 场景 E：分支预测错误 (Branch Mispredict)
- 若 `br_mispred` 为真：
  1. 触发 `Global Flush` (或者 Partial Flush，取决于策略)。
  2. 翻转 `i_epoch`。
  3. 重定向 Fetcher 到 `br_target`。
  4. 恢复 RAT 映射 (从 Snapshot 或 ArchRAT)。

---

## 伪代码示例 (Commit FSM)

```scala
switch (state) {
  is (s_IDLE) {
    val head = rob_ram(head_ptr)
    
    when (head.busy && head.completed) {
      // 1. 处理异常
      if (head.exception.valid) {
        // Handle Trap logic...
        io.flush.valid := true.B
        io.flush.bits.target := trap_vector
        state := s_IDLE // Reset logic will handle ptrs
      }
      // 2. 处理 Store
      else if (head.is_store) {
        io.lsu_commit.valid := true.B
        state := s_WAIT_LSU
      }
      // 3. 处理 CSR / xRET
      else if (head.is_csr || head.is_xret) {
        io.csr_commit.valid := true.B
        state := s_WAIT_CSR
      }
      // 4. 处理分支错误
      else if (head.is_branch && head.br_mispred) {
        io.flush.valid := true.B
        io.flush.bits.target := head.br_target
        // Update I-Epoch
      }
      // 5. 正常退休
      else {
        Retire(head)
        head_ptr := head_ptr + 1.U
      }
    }
  }
  
  is (s_WAIT_LSU) {
    when (io.lsu_store_ack) {
      Retire(rob_ram(head_ptr))
      head_ptr := head_ptr + 1.U
      state := s_IDLE
    }
  }
  
  // CSR 类似...
}

def Retire(entry: ROBEntry) = {
  if (entry.has_write_rf) {
    io.commit.valid := true.B
    io.commit.bits.lrd := entry.lrd
    io.commit.bits.prd := entry.prd
    io.commit.bits.old_prd := entry.prd_old
  }
}
```