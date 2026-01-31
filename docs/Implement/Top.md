# RV32I-Privileged Tomasulo 架构设计

## 1. Frontend

### 1.1 Fetcher
*   **职责**：判断 `CurrentPC` 并发起取指请求，维护 `NextPC`。
*   **输入**：ROB 提供的 `InsEpoch` `GlobalFlush` 与 `GlobalFlushPC`；BRU 提供的 `BranchFlush` 与 `BranchFlushPC`；Decoder 传入的 `IFStall`；CSRsUnit 传入的 `PrivMode`。
*   **逻辑简述**：
    *  **判断当前周期 PC**：
        *   若 `GlobalFlush` 有效，`CurrentPC = GlobalFlushPC`。
        *   否则若 `BranchFlush` 有效，`CurrentPC = BranchFlushPC`。
        *   否则 `CurrentPC = NextPC`。
    *  **发起取指请求**：
        *   与 Icache 的握手协议中 `valid` 由 `IFStall` 控制（`IFStall` 高时 `valid` 低，暂停取指）。
    *  **更新 NextPC**：
        *   若取指部分握手成功（`fire` 即 `ready && valid`），则更新 `NextPC`：将 `CurrentPC` 送入 `BranchPredict` 模块获得预测结果 `Predict`（包括目标 PC 与是否跳转），并令 `NextPC` 为预测 PC。
        *   否则 `NextPC` 保持不变。   
*   **输出**：向 Memory Access (ITLB) 发送 `{PC, PrivMode, InsEpoch, Prediction, Exception}`。

### 1.2 Decoder
*   **职责**：将指令解析到微指令；进行寄存器重命名请求与快照请求；异常检测。
*   **输入**：来自 Icache 的 `{Instruction, PC, PrivMode, InsEpoch, Prediction, Exception}`；来自 ROB 的 `FreeRobID`， `GlobalFlush` `CSRPending` 与来自 BRU 的 `BranchFlush`。
*   **逻辑简述**：
    *   **指令解析**：检测 `InsEpoch` 是否过期，若是则丢弃，否则继续解析。将 `Instruction` 解码为 `MinOps`（EU 使能信号）、`Data`（rd, rs1, rs2）、`Exceptions`（IF + Decode 可能的异常）。
    *   **寄存器重命名请求**：将 `Data` 送入 RAT，获取对应的物理寄存器号 `PhyRd` 和旧物理寄存器号 `PreRd` 以及依赖的数据。
    *   **快照请求**：若指令解析为分支指令，向 RAT 请求创建分支快照，即拉高 `IsBranch` 信号。
    *   **异常处理**：将 `Exceptions` 向 Dispatch 单元透传，但如果此时 `exception.valid` 已经为 1，则不向 RS 内传输信息，向 RAT 内请求 x0 即可。
    *   **阻塞逻辑**：若 ROB, RS, LSQ, 或 RAT Free List 满，拉低对 Icache 的 `ready`；如果解析出指令属于 Zicsr 扩展或是 Privileged ISA，或 `CSRPending` 信号为 1，则拉高 `IFStall` 信号试图暂停取指，直到 CSR 指令完成（`CSRPending` 置 0）。
*   **输出**：
    *   向 RAT 发送 `{Data, IsBranch}` 请求。
    *   向 ROB 发送 `{RobID, Exception, Prediction}`。
    *   向 RS 发送 `{MinOps, Exceptions, RobID, Prediction}`。
    *   向 Fetcher 发送 `IFStall` 信号。

### 1.3 RAT (Register Alias Table)
*   **职责**：维护如下**架构寄存器->物理寄存器**映射 -- Frontend RAT (推断状态), Retirement RAT (提交状态), 最多 4 个 SnapShots ；维护 Free List (物理寄存器池，使用位矢量表示 busy 部分) 以及 Retirement Free List (ROB 提交的正被占据的物理寄存器对应 busy 位矢量)，4 个 Snapshots 中各有一份 Free List；重命名之后将对应数据（操作数对应立即数或寄存器，valid 位）送入 RS。
*   **输入**：来自 Decoder 的 `{Data, IsBranch}` 请求；来自 ROB 的 `{CommitPreRd, CommitPhyRd, GlobalFlush}`；来自 BRU 的 `{BranchFlush, SnapshotID, BranchMask}`。
*   **逻辑简述**：
    *   **重命名**：接受 Decoder 请求时执行：
        *   **分配新物理寄存器**：如果 rd != x0，则从 Free List 分配`PhyRd` 并将该寄存器状态置为 busy。
        *   **更新 Frontend RAT**：将架构寄存器 rd 映射更新为 `PhyRd`。
    *   **分支快照**：若 `IsBranch === true.B`，保存当前 Frontend RAT 与 Free List 到 Snapshots 中。4 份 Snapshots 对应 4 位位矢量 `BranchMask`，以当次使用快照对应独热码为 `SnapshotID`。
    *   **恢复与冲刷**：
        *   **Global Flush**：直接将 Frontend RAT 覆盖为 Retirement RAT，回收所有 Snapshots。
        *   **Branch Mispredict**：通过 `SnapshotID` 瞬间恢复 Frontend RAT，同时根据 `branch_mask` 回收不再需要的 Snapshots。
    *   **正常回收**：将 ROB 提交阶段发回的 `PreRd` 回收进 Free List；将 BRU 传来的预测成功分支（SnapshotID 非 0，但 BranchFlush 为 0）对应的 Snapshot 回收。
        > 以上内容中回收的含义为将 busy 位置 0，表示该物理资源可再分配使用。
*   **输出**：
    *   向 ROB 发送 `{LogicRd, PhyRd, PreRd, BranchMask}`。
    *   向 RS 发送 `{Data, BranchMask}`。

## 2. Backend

### 2.1 RS (Reservation Stations)
*   **组成**：分布式分布，由 ALURS 与 BRURS 组成。
*   **职责**：接收 Decoder 送入的控制方面信息与来自 RAT 的数据相关信息，dispatch 到各个执行单元。维护因数据冒险与结构冒险暂时无法执行的指令队列，其中ALU 与 BRU 在对应 RS 中维护而 LSU 自主维护，RS 模块只负责分派。
*   **输入**：
    *   来自 Decoder 的 `{MinOps, Exceptions, RobID, Prediction, PC, Imm, privMode}`。
    *   来自 RAT 的 `{Data, BranchMask}`。
    *   来自 CBD 的 `{ResultReg, data}`。
    *   来自 ROB 的 `GlobalFlush`。
    *   来自 BRU 的 `BranchFlush` 与 `BranchMask`。
*   **逻辑简述**：
    *   **接收并分派**：从 Decoder 接收 `{MinOps, Exceptions, RobID, Prediction}`，从 RAT 接收 `{Data, BranchMask}`，利用 `MinOps` 中指令 opcode 进行分派，到 4 个 EU 中的对应 RS 或 Queue。
    *   **监听**：在对应 RS 中存储指令控制信息，数据合法与否。时刻比对 CBD 上的 `ResultReg`。若匹配则将对应数据置为 valid。
    *   **发射**：RS 使用 FIFO，当一条指令的所有操作数都 valid ，即该指令valid 且对应 EU ready 时将最早的指令需要的源寄存器值从 PRF 中取出，发送到 EU 中求取结果。
    *   **冲刷**：如果 ROB 将 `GlobalFlush` 信号拉高，则立刻通过 busy 位置 0 方式冲刷所有指令。如果 BRU 拉高 `BranchFlush` 则根据 `BranchMask` 进行位运算，找出依赖于对应分支指令并清理。
*   **输出**：
    *   向 PRF 发送 `{SourceReg1, SourceReg2}`。
    *   向 EU 发送 `{Opcode, Data, RobID, Prediction}`。   

### 2.2 ALU (Arithmetic Logic Unit)
*   **职责**：执行整数算术与逻辑运算。
*   **输入**：
    *   来自 RS 的 `{ALUOp, Op1Sel, Op2Sel, Imm, PC, RobID}`。
    *   来自 PRF 的 `{ReadData1, ReadData2}`。
    *   来自 ROB/BRU 的冲刷信号。
*   **逻辑简述**：
    *   **操作数选择**：根据 `Op1Sel/Op2Sel` 在 `ReadData`、`PC`、`Imm`、`Zero` 之间进行 Mux 选择。
    *   **运算**：执行加减、移位、逻辑、比较等操作。
    *   **异常传递**：如果指令在前端已经携带了异常（如 Illegal Instruction），ALU 不进行计算，直接将异常信息透传到结果包中。
*   **输出**：先存入结果寄存器，当 CBD 空闲时广播发送 `{RobID, Result, Exception}`。

### 2.3 BRU (Branch Resolution Unit)
*   **职责**：执行分支判定、跳转目标计算，并处理分支预测失败的恢复。
*   **输入**：
    *   来自 RS 的 `{BRUOp, Imm, PC, RobID, Prediction(Taken, Target)}`。
    *   来自 PRF 的 `{ReadData1, ReadData2}`。
*   **逻辑简述**：
    *   **计算**：计算 `Condition = (Src1 op Src2)` 和 `ActualTarget = PC/Src1 + Imm`。
    *   **比对**：将计算结果与 `Prediction` 进行比对。
    *   **分支决议**：
        *   **预测正确**：生成 `BranchResolve` 信号（不冲刷，只清除 Mask）。
        *   **预测错误**：生成 `BranchFlush` 信号，携带 `RedirectPC` 和该指令对应的 `BranchMask`（用于定位）。
*   **输出**：
    *   **全局专线**：完成判定后先存入寄存器，下一个周期向 Fetcher/RAT/ROB/RS 广播 `{BranchFlush, RedirectPC, snapshotId}`。
    *   **CBD 接口**：向 CBD 发送 `{RobID, Result(无意义), Exception}` 以便 ROB 更新状态。

### 2.4 ZICSRU (Zicsr Instructions Unit)
*   **职责**：执行 CSR 读写指令（读旧值、算新值、如 Store 一般一段时间后写入新值）。
*   **输入**：
    *   来自 RS 的 `{CSROp, CSRAddr, Imm, RobID, exception}`。
    *   来自 PRF 的 `{ReadData1}` (作为 RS1)。
    *   来自 CSRsUnit 的 `{CSRReadData, exception}` (读取物理 CSR 当前值) `CSRWriteException` (写入结果)。
    *   来自 ROB 的 `CommitSignal` (表示该 CSR 指令已在队头，可以进行写操作)。
*   **逻辑简述**：
    *   **读操作**：根据 CSR 地址读取 CSRsUnit 的值，作为 `OldValue`。
    *   **算操作**：根据 `CSROp` (RW/RS/RC) 和 `RS1/Imm` 计算 `NewValue`。
    *   **暂停**：由于 CSR 指令在 ROB 头部序列化执行，ZICSRU 的写入上只在 ROB 发出信号时才工作。
*   **输出**：
    *   向 CBD 发送 `{RobID, Result(OldValue), Exception}` (用于写回 `rd`)。

### 2.5 LSU (Load Store Unit)
*   **组成**：LSQ (访存队列), AGU (地址生成), MMU, PMP, Cache(Cache + AXI + MainMemory)。
*   **职责**：处理所有内存访问，维护访存顺序一致性，处理虚实地址转换与权限检查。
*   **输入**：
    *   来自 RS 的 `{LSUOp, Imm, RobID, PrivMode, D-Epoch, BranchMask}`。
    *   来自 PRF 的 `{BaseAddr, StoreData}`。
    *   来自 ROB 的 `CommitStore/CommitIO` 信号。
    *   来自 Memory Access System 的 `MemResponse`。
*   **逻辑简述**：
    *   **AGU**：计算 `VA = Base + Imm`。
    *   **翻译与检查**：请求 TLB 进行 VA->PA 转换，并发进行 PMP/PMA 检查。若异常，标记 `Exception`。
    *   **Load 处理**：
        *   若 PMA 为 RAM：查 Store Queue (Forwarding)，若无冲突则发往 D-Cache。
        *   若 PMA 为 I/O：进入 `Wait_Commit` 状态，等待 ROB 信号。
    *   **Store 处理**：将 `{PA, StoreData}` 写入 Store Queue。**不发送总线请求**，等待 ROB Commit 信号。
    *   **冲刷**：响应 `GlobalFlush` (清空所有) 和 `BranchFlush` (根据 Mask 清空 Load，Store 保留直到退休)。
*   **输出**：
    *   向 CBD 发送 `{RobID, LoadData, Exception}`。
    *   向 Memory Access System 发送 `{Req, D-Epoch}`。
    *   向 ROB 发送 `StoreAck`。

### 2.6 CBD (Common Bus Data)
*   **职责**：多对一仲裁（ALU/BRU/LSU/ZICSRU $\rightarrow$ 总线），将通过仲裁的指令结果广播到 RS、PRF 与 ROB。
*   **输入**：
    *   来自 ALU、BRU、LSU、ZICSRU 的 `{ResultRd, data, RobID, Exception}`。
    *   来自 ROB 的 `GlobalFlush`。
    *   来自 BRU 的 `BranchFlush`。
*   **逻辑简述**：
    *   **仲裁**：当多个 EU 同时请求总线时，按照预设优先级（ZICSRU > LSU > BRU > ALU）进行仲裁，选出一个 EU 的结果进行广播。
    *   **冲刷**：若 ROB 拉高 `GlobalFlush` 或 BRU 拉高 `BranchFlush`，则当前周期的总线不进行广播。
*   **输出**：`{ResultRd, data, RobID, Exception}`。
  
## 3. 状态及其维护

### 3.1 PRF (Physical Register File)
*   **职责**：储存数据的物理寄存器。
*   **写端口**：连接 **CBD**，监听 `ResultRd` 与 `Data` ，若不为 x0 则写入数据。
*   **读端口**：连接 **RS 的发射端**，在 Issue 阶段接受源寄存器编号，读取出对应值并直接将对应值输入 EU 进行计算（e.g. `io.rdata1 := regs[io.rs1]`）。
  > 为避免端口过多，我们为 ALU，BRU，LSU 分别配备一份寄存器，每个 PRF 模块固定一个读端口一个写端口，而内部寄存器堆需要两个读端口一个写端口。

### 3.2 ROB (Reorder Buffer)
*   **职责**：指令生命周期与全局控制信息管理者，异常处理中心，架构状态更新者。
*   **输入**：
    *   Decoder 与 RAT : 。新入队指令的 `{pc, Exception, Prediction, isSpecialInstr}` 与 `{LogicRd, PhyRd, PreRd, BranchMask}`。
    *   `CBD`: 标记指令完成或待机，并携带物理目标寄存器结果的 `{RobID, Exception, phyRd, data}`。
    *   `BRU`: 接收 BRU 计算完成的 `{snapshotId, branchFlush, redirectPC}`。
*   **逻辑简述**：
    *   **队列维护**：维护一个最大长度确定的循环队列，包含队头 `robHead` 与队尾 `robTail`，每个 Entry 包含如下内容：
        * 状态位：`busy`, `completed` 对应是否为空与是否完成。
        * 指令地址：`pc`。
        * 资源管理：`archRd`, `phyRd`, `preRd`。
        * 异常信息：`exception`。
        * 分支预测信息：`branchMask` 代表依赖分支用于冲刷，`prediction` 代表预测结果，用于之后的 BTB 更新。
        * 特殊指令标记：`specialInstr` 用于标记 CSR 指令与 xRET 指令等可能提交时有特殊作用的指令。 
        * 特判：`hasSideEffect` 标记该指令是否有副作用，专门用于处理非幂等区域 Load 该需要被计算出提交后执行的指令。
    *   **全局控制信息维护** ：管理纪元信息 `IEpoch` 与 `DEpoch`，用于内存访问的顺序一致性维护，分别在分支冲刷或全局冲刷，和仅全局冲刷时更新；管理 `CSRPending` 信号，标记 CSR 指令或某些改变 CSR 状态指令正在提交中以阻塞前端取指。
    *   **指令入队**：当 ROB 未满时，将 Decoder 输入接口 `ready` 拉高，输出对应 `RobTail` 位置创建新 Entry，根据 Decoder 与 RAT 输入填写对应信息，置 `busy = 1`，`completed = 0`，如果进入时指令已经出现异常则 `complete = 1`。
    *   **指令完成**：当 CBD 送入 `{RobID, Exception, phyRd, data, hasSideEffect}` 时，找到对应 Entry，将 `completed` 置 1，若 `Exception` 有效则更新 Entry 的 `Exception` 字段。
    *   **分支冲刷**：当 BRU 送入 `{snapshotId, branchFlush, redirectPC}` 时，若 `branchFlush` 有效则根据 `snapshotId` 定位到对应分支指令，清除所有依赖该分支的 Entry（通过 `branchMask` 位运算）并更新 `IEpoch`，若否则单纯移除 `branchMask`。依据预测结果更新 `prediction` 字段以便提交时用于更新 BTB。 
    *   **提交状态机**：
        1.  **Check Head**：若 `!completed`，等待。
        2.  **Handle Exception**：若 `exception.valid`，触发 `globalFlush`，更新 CSR (`mcause/mepc`)，跳转 `mtvec`。
        3.  **Handle Serialization**：
            *   若为 **Store** 或 **hasSideEffect 为 1** ：在局部维护一个状态机，进入 `wait_done` 模式并发信号给 LSU，等待从总线传来的第二次对应 `robId` 信号然后退休。
                > 不排除在过程中出现异常可能，ROB 行为取决于第二次总线信号。
            *   若为 **CSR Write**：在局部维护一个状态机，发信号给 ZICSRU 并等待总线上的第二次对应信号，然后触发 `globalFlush` 并退休。
                > 不排除在过程中出现异常可能，ROB 行为取决于第二次总线信号。
            *   若为 **xRET**：触发 `globalFlush`，向 CSRsUnit 发送对应信息，行为类似于 handle exception。
            *   若为 **WFI**：当做 NOP 处理，直接退休。
            *   若为 **FENCE.I**：直接退休。
            *   若为 **SFENCE.VMA**：
        4.  **Normal Retire**：通知 RAT `Retire(lrd, prd, p_old)`，释放 `p_old` 到 Free List，令 `robHead` 前进一位。
    *   **维护纪元**：若 `globalFlush` 发生，更新 `DEpoch`。
    *   **维护 CSRPending**：当 CSR 指令或 xRET 指令或 `FENCE` `FENCE.I` `SFENCE` 入队时，拉高 `CSRPending` 信号，在这类指令提交时拉低该信号（由于阻塞取值一段时间内只有一条该类指令）。
*   **输出**：
    *   `globalFlush`。
    *   纪元信息：`IEpoch` `DEpoch`。
    *   取值暂停信息：`CSRPending`。
    *   指令 Ack 信息：`storeEnable` `csrEnable`。
    *   异常与 CSR 更新信息：`exception` `mret` `sret`。
    *   内存部分更新信息：`fence.i` `sfence.vma`。
    *   `RobTail`: 给 Decoder 用于分配 ID。
    *   RAT 更新信息：`{commitArchRd, commitPhyRd, commitOldRd}`。

### 3.3 CSRsUnit
*   **职责**：物理 CSR 寄存器堆，实现 CSR 相关特权级逻辑。
*   **输入**：
    *   来自 ZICSRU 的读请求（组合逻辑返回结果）。
    *   来自 ZICSRU 的 写请求（组合逻辑返回是否出现异常）。
    *   来自 ROB 的 Trap 信号（`exception` 与特殊指令信号）。
*   **逻辑**：
    *   维护 `mstatus`, `mie`, `satp` 等关键寄存器。
    *   **原子更新**：只有在收到 ROB 的 Commit 信号时才真正改写寄存器值。
    *   **特权检查**：配合 Decoder/LSU 提供当前的权限位（PrivMode）和地址翻译控制位（TVM, MPRV）。
*   **输出**：
    *   向 ZICSRU 提供 CSR 访问结果。
    *   向 Fetcher 提供当前 privMode。
    *   向 Decoder 暴露当前 privMode。
    *   提供全局 flush 信号：`globalFlush` 与 `globalFlushPC`。  
    *   提供内存相关控制信息：`pmp` 配置，`satp` 配置。

## 4. 内存模块

### 4.1 SimRAM

#### 4.1.1 协议定义：Wide-AXI4 接口

AXI4 协议的设计基于 **通道分离（Channel Independence）** 架构。每个通道都是单向的，并且拥有独立的 `Valid/Ready` 握手。

1.  **Read Address Channel (AR)**: 读请求（地址 + 控制）。
2.  **Read Data Channel (R)**: 读响应（数据 + 状态）。
3.  **Write Address Channel (AW)**: 写请求（地址 + 控制）。
4.  **Write Data Channel (W)**: 写数据（数据 + 掩码）。
5.  **Write Response Channel (B)**: 写响应（握手确认）。

#### 4.1.2 接口 Bundle 定义 (Chisel)

```scala
// 1. 定义元数据 (上下文)
class AXIContext extends Bundle {
  val epoch      = UInt(2.W)
}

// 2. 定义宽总线接口
class WideAXI4Bundle extends Bundle {
  // --- 读路径 ---
  val ar = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id   = new AxiId        // 事务 ID (区分 I/D Cache)
    val len  = UInt(8.W)        // Burst 长度，宽总线通常为 0
    val user = new AXIContext   // 携带元数据
  })
  
  val r = Flipped(Decoupled(new Bundle {
    val data = UInt(512.W)      // 512-bit 宽数据
    val id   = new AxiId
    val last = Bool()           // Burst 结束标志，宽总线返回结果当周期拉高
    val user = new AXIContext   // 回传元数据
  }))

  // --- 写路径 ---
  val aw = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id   = new AxiId
    val len  = UInt(8.W)
    val user = new AXIContext
  })
  
  val w = Decoupled(new Bundle {
    val data = UInt(512.W)
    val strb = UInt(64.W)       // 字节掩码 (64 bytes -> 64 bits)
    val last = Bool()
  })
  
  val b = Flipped(Decoupled(new Bundle {
    val id   = new AxiId
    val user = new AXIContext
  }))
}
```

#### 4.1.3 模块定义与输入方向

**职责**：模拟物理内存行为（高延迟、宽带宽），支持仿真文件的预加载。

##### 输入/输出接口
*   `io.axi`: **`Flipped(new WideAXI4Bundle)`**
    *   **来源**：`AXIArbiter`。
    *   `Flipped` 意味着 SimRAM 是 **Slave**（从设备）：它接收 AR/AW/W 的 `valid`，输出 AR/AW/W 的 `ready`；它输出 R/B 的 `valid`，接收 R/B 的 `ready`。

#### 4.1.4 内部逻辑与时序模型

由于 `SyncReadMem` 是同步读（1 周期延迟），而我们需要模拟更长的物理延迟（例如 10 周期），需要一个明确的 **FSM (有限状态机)**。

##### 4.1.4.1 Chisel SRAM 特性说明
*   **原语**：`SyncReadMem`。
*   **特性**：**Clock-Synchronous Read**。
    *   在 Cycle N 给出地址 `addr` 和 `en=1`。
    *   在 Cycle N+1 才能在输出端口 `data_out` 看到数据。
      > 在 Cycle N+1，如果 `en=0`，`data_out` 的结果未定义。
    *   写操作在同一周期内生效（无延迟），接口为 `mem.write(addr, data, mask)`。
*   **初始化**：使用 `loadMemoryFromFile(mem, "kernel.hex")`。
*   本实现使用 `SyncReadMem(size/64, Vec(64,UInt(8.W)))` 模拟内存，按字节寻址。

##### 4.1.4.2 状态机定义

```scala
object RamState extends ChiselEnum {
  val Idle, Delay, Response = Value
}
```

##### 4.1.4.3 维护状态
*   `state`: 状态寄存器，初始 `Idle`。
*   `counter`: 延迟计数器。
*   `req_reg`: 锁存请求信息（Addr, ID, User）。因为 AXI 握手后信号会消失，必须锁存。

##### 4.1.4.4 详细状态流转

*   1. **IDLE (空闲态)**
*   **逻辑**：同时监听 `io.axi.ar.valid` 和 `io.axi.aw.valid`。
*   **仲裁**：如果两者同时为 1，通常 **读优先 (Read Priority)**（因为 Load 阻塞流水线，Store 不阻塞）。
    > 对于该实现两者不会同时为 1。
*   **动作 (若读请求)**：
    *   锁存 `ar` 信息到 `req_reg`。
    *   置计数器 `counter := LATENCY - 1`。
    *   跳转 `sReadDelay`。
*   **动作 (若写请求)**：
    *   锁存 `aw` 与 `w` 信息，如果字节掩码存在且地址未能与 64 为掩码对齐，则通过组合逻辑移位后存入 `req_reg`。
      > 该实现中两者同时到来。
    *   置计数器 `counter := LATENCY - 1`。
    *   跳转 `sWriteDelay`。

*   2. **Delay (延迟模拟)**
*   **状态**：`sReadDelay` 或 `sWriteDelay`。
*   **逻辑**：`counter := counter - 1`。
*   **跳转**：当 `counter === 0` 时：
    *   如果是读：发起读请求 `mem.read(req_reg.addr, enable=true)`，跳转 `sResp`。
    *   如果是写：执行 `mem.write`，跳转 `sResp`。

*   3. **Response (响应握手)**
*   **输出**：
    *   `io.axi.r.valid := true`。
    *   `io.axi.r.bits.data := mem_read_data` (读出的结果)。
    *   `io.axi.r.bits.id := req_reg.id`。
    *   `io.axi.r.bits.user := req_reg.user` (**核心：原样回传 Context**)。
    *   `io.axi.r.bits.last := true` (宽总线一次传完)。
*   **跳转**：当 `io.axi.r.ready === true` 时，握手完成，回到 `sIdle`。

*(写响应 `sWriteResp` 逻辑类似，通过 `b` 通道握手)*

### 4.2 AXIArbiter

#### 4.2.1 职责
*   **请求汇聚 (Request Fan-in)**：将 I-Cache 的读请求与 D-Cache 的读/写请求进行仲裁，按照优先级顺序送往 `Wide-AXI4` 主总线。
*   **响应分发 (Response Demux)**：通过 AXI ID 识别总线返回的数据（R 通道）或写确认（B 通道），将其路由回正确的 Cache 模块。

#### 4.2.2 维护状态
*   **`is_busy` (Reg)**：标记当前总线是否正在处理一个尚未完成的事务。
    *   对于读：从 `AR.fire` 开始，到 `R.fire && R.bits.last` 结束。
    *   对于写：从 `AW.fire` 开始，到 `B.fire` 结束。

#### 4.2.3 接口

##### 4.2.3.1 输入 (Inputs)
| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `i_cache.req` | I-Cache | `WideAXI4.AR` | 指令取指 Miss 请求 |
| `d_cache.req_r`| D-Cache | `WideAXI4.AR` | 数据读取 Miss 请求 |
| `d_cache.req_w`| D-Cache | `WideAXI4.AW/W`| 数据写回 (Write-back) 或 Uncached Store |
| `main_mem.resp`| SimRAM | `WideAXI4.R/B` | 总线回传的数据或写确认 |

##### 4.2.3.2 输出 (Outputs)
| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `i_cache.resp` | I-Cache | `WideAXI4.R` | 返回 512-bit 指令块 |
| `d_cache.resp` | D-Cache | `WideAXI4.R/B` | 返回 512-bit 数据块或写回确认 |
| `main_mem.req` | SimRAM | `WideAXI4.Req` | 发向主存的统一 AXI 请求 |

#### 4.2.4 内部逻辑简述

##### 4.2.4.1 Ready 信号处理 (仲裁逻辑)
仲裁器优先级固定：**D-Cache > I-Cache**。

*   **逻辑公式**：
    *   `can_issue := !is_busy` (总线空闲)。
    *   `d_wins := d_cache.req.valid`。
    *   `i_wins := i_cache.req.valid && !d_cache.req.valid`。
*   **握手输出**：
    *   `d_cache.req.ready := can_issue && d_wins`。
    *   `i_cache.req.ready := can_issue && i_wins`。
*   **状态迁移**：当 `main_mem.ar.fire` 或 `main_mem.aw.fire` 时，`is_busy` 置 1，将对应请求转发到主线，同时 AxiId 规定 I-cache 为 0，D-cache 为 1。

##### 4.2.4.2 结果回传处理 (分发逻辑)

*   **读数据回传 (R Channel)**：
    *   inflight_id 为 main_mem 返回信息中的 id 值。  
    *   `i_cache.resp.valid := main_mem.resp.valid && (inflight_id === 0.U)`
    *   `d_cache.resp.valid := main_mem.resp.valid && (inflight_id === 1.U)`
    *   `main_mem.resp.ready := Mux(inflight_id === 1.U, d_cache.resp.ready, i_cache.resp.ready)`
*   **写确认回传 (B Channel)**：只有 D-Cache 会写
    *   `d_cache.b.valid := main_mem.b.valid`。
    *   `main_mem.b.ready := d_cache.b.ready`。

### 4.3 Cache

*   **职责**：
    *   **物理地址缓存**：提供基于物理地址（PA）的高速数据读取与写入。
    *   **多主设备仲裁**：在同一周期内接收 PTW（页表遍历）与 LSU（数据存取）的请求，并按照 **PTW 绝对优先**的原则进行调度。
    *   **主存访问**：通过 512-bit 宽总线向主存发起请求。
    *   **一致性与冲刷**：执行 FENCE.I 的脏行清空，并配合 Epoch/BranchMask 处理在途指令的逻辑废除。
*   **组成**：**Priority Arbiter (PTW > LSU)**, Tag Array (SRAM), Data Array (SRAM), Status Array (Regs), pendingReq (Reg), In-flight Buffer (Size=2), AXI Master Interface, FENCE.I Controller。
*   **输入**：
    *   **来自 PTW 读请求接口**：`{PA, Width, Ctx(Epoch && branchMask)}`。
    *   **来自 LSU/Fetcher 读接口**：`{PA, Width, Ctx}`。
    *   **来自 LSU/Fetcher 写接口**: `{PA, Width, Ctx, wData}`。
    *   **来自系统控制平面**：`GlobalFlush`, `BranchFlush`, `KillMask`。
    *   **来自 ROB**：`FenceI_Req`。
    *   **来自 AXI 总线返回**：`{Ctx(Epoch), RData, BValid}`。
*   **输出**：
    *   **向请求方返回**：`PTW_Ready`, `LSU_Ready`（或 `Fetcher_Ready`）。
    *   **向请求方回复**：`{ReadData, Ctx}` 或 `{B, Ctx}`。
    *   **向 AXI 总线发送**：`{ARAddr, AWAddr, WData, WStrb, User(Ctx)}`。
    *   **状态信号**：`FenceI_Done`。
*   **逻辑简述**：
    *   **前端优先级仲裁逻辑**：
        *   每个周期检查两个输入端的 `valid`。
        *   **仲裁规则**：`Winner := Mux(PTW.valid, PTW, LSU)`。
        *   **Ready 反压**：
            *   `PTW_Ready := io.axi_buffer.ready && !is_fence_i_active`。
            *   `LSU_Ready := io.axi_buffer.ready && !is_fence_i_active && !PTW.valid`（只有 PTW 不抢占且 Buffer 有空位时才接纳 LSU）。
    *   **内部流水线逻辑 (PIPT)**：
        *   **Stage 1**：锁存仲裁胜出者的 `PA`、`Cmd`、`Id` 和 `Ctx`。发起对 Tag 和 Data SRAM 的同步读取。
        *   **Stage 2**：比对 Tag。由于输入已是物理地址，直接比对 `SRAM_Tag` 与 `Reg_PA_Tag`。
        *   **结果分发**：如果比对成功，则根据锁存的请求信息，将结果送回对应的 PTW 或 LSU 接口；如果 Miss，则开始总线访问主存，将生成的总线事务压入 AXI buffer。
    *   **缺失与回写逻辑**：
        *   若 `Miss` 且该行 `Dirty`，启动 `Write-back`。
        *   执行 `Refill`。
        *   *注意*：上述请求都被压进 AXI buffer 且 buffer 记录 Ctx 信息。
    *   **总线访问**：AXI Buffer 深度为 3，与 AxiAribiter 配合使用，确保总线请求的有序发出与响应的正确分发。当 2 个以上槽位在忙时不接收新请求，即 ready 全部拉低。 
    *   **冲刷逻辑**：
        *   **逻辑撤销**：若 `branch_flush` 命中在途指令寄存器的 `Ctx.mask`，该请求匹配不返回信息，不匹配不进行主存访问。
        *   **总线过滤**：已进入 AXI Buffer 的请求不可撤销。检查所有 AXI Buffer 中项目，修改其 `isKilled` 字段。当数据从 AXI 返回，检查其携带的 `user.epoch` 和 `user.mask`。若纪元过期或被标记 `killed`，则**吃掉数据，放空槽位且不更新 SRAM 阵列**。
    *   **FENCE.I 序列化**：
        *   一旦 `FenceI_Req` 有效，仲裁器强制将 `PTW_Ready` 和 `LSU_Ready` 拉低，进入独占式的扫描回写状态，直至完成。

### 4.4 MMU

#### 4.4.1 职责
*   **虚实地址翻译**：利用 TLB 进行单周期快速翻译（Sv32 协议）。
*   **硬件页表遍历**：当 TLB 缺失时，自动启动 PTW 状态机爬取内存中的页表条目。
*   **多层权限检查**：执行基于 PTE (U/R/W/X 位) 的虚拟权限检查，以及基于 PMP 寄存器的物理访问检查。
*   **冲刷与同步**：处理 `SFENCE.VMA` 导致的 TLB 失效，以及 `Flush` 信号导致的在途翻译任务废除。

#### 4.4.2 接口定义 (IO Bundle)

##### 4.4.2.1 输入
| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| **Request** | **LSU** | | |
| `VA` | LSU | `UInt(32.W)` | 待翻译的虚拟地址 |
| `memOp` | LSU | `Enum` | 访问类型 (Load / Store / Atomic) |
| `memWidth` | LSU | `Enum` | 访问位宽 (B / H / W) |
| `ctx` | LSU | `MemContext` | 包含 `robId`, `epoch`, `branchMask`, `privMode` |
| **Control** | **ROB/CSR** | | |
| `sfence` | ROB | `Valid(SFenceReq)` | TLB 刷新请求 (含 rs1, rs2) |
| `globalFlush` | ROB | `Bool` | 全局冲刷信号 |
| `branchFlush` | BRU | `Bool` | 分支冲刷信号 |
| `killMask` | BRU | `UInt(4.W)` | 需清除的分支掩码 |
| `satp` | CSRsUnit | `UInt(32.W)` | 包含 Mode (Sv32) 与 PPN (根页表基址) |

##### 4.4.2.2 输出
| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| **Response** | **LSU** | | |
| `PA` | LSU | `UInt(34.W)` | 翻译后的物理地址 (Sv32 输出为 34 位) |
| `exception` | LSU | `ExcPacket` | 包含 `valid`, `cause` (Page/Access Fault), `tval` |
| `resp_ctx` | LSU | `MemContext` | 原样带回的上下文信息 |
| **Status** | **ROB** | | |
| `sfence_done`| ROB | `Bool` | `SFENCE.VMA` 操作完成信号 |

#### 4.4.3 内部子模块逻辑

##### 4.4.3.1 TLB (Translation Lookaside Buffer)
*   **输入**：`VA`, `privMode`, `sfence`。
*   **输出**：`hit`, `PA`, `exception`, `pageAttr`（本次访问需要权限，供PMP 检查使用）。
*   **存储结构**：`Reg(Vec(Entries, new TLBEntry))`，采用全相联查找以支持单周期输出。（实现为 16 条目 TLB）
*   **内部逻辑**：
    1.  **查找逻辑**：将 `va[31:12]` 与所有有效 Entry 并行比对。同时根据 `asid` 和 `G` 位判断是否匹配当前进程（该大作业不实现 `asid`，硬连线为 0）。
    2.  **权限校验**：在比对成功的基础上，将 `pte.u`、`pte.r/w/x` 与输入请求的 `privMode` 及 `memOp` 进行组合逻辑比对，计算出所需权限并返回。
    3.  **管理逻辑**：
        *   当 `sfence.valid` 为高时：全量清空 `valid` 位。
        *   当 PTW 返回新 PTE 时：采用随机算法替换旧 Entry。

##### 4.4.3.2 PTW (Page Table Walker)
*   **输入**：TLB Miss 信号, `va`, `satp`, `D-Cache` 返回数据。
*   **输出**：写入 TLB 的数据, `TLBrefresh`, `exception`, `ready` (MMU)。
*   **内部逻辑：状态机维护**：
    *   `sIDLE`：等待 Miss。一旦启动，拉低 MMU 接口的 `ready`，阻塞后续翻译。
    *   `sL1Req`：计算一级页表项地址 `PA = satp.ppn * 4096 + vpn[1] * 4` 并调用 PMP 模块检查该 PTE 地址的合法性，访问 D-cache。
    *   `sL1Wait`：等待 D-Cache 返回。
    *   `sL0Req`：若一级 PTE 指向二级页表，计算二级地址并再次发起 Cache 请求。
    *   `sL0Wait`：等待二级页表数据。
    *   **关键点**：PTW 在每次向 Cache 发起物理请求前，**必须调用 PMP 模块**进行 PTE 地址的合法性检查。若不合法返回异常信息；若合法则向 D-Cache 发起请求，注意请求需要包含 Ctx(Epoch, BranchMask)。
    *   **Flush 处理**：所有请求携带 Ctx，且 PTW 本身维护在途请求的 Ctx，一旦出现 Flush 且与 branchMask 匹配则丢弃该请求并将状态机重置回 `sIDLE`。

##### 4.4.3.3 PMP (Physical Memory Protection)
*   **输入**：物理地址 `PA`, 发起者 `privMode`, 权限需求 (R/W/X)。
*   **输出**：`exception` 异常包。
*   **逻辑**：纯粹组合逻辑，单周期内完成检测。
    1.  遍历所有已配置的 PMP 寄存器条目 (0~63)。
    2.  匹配：自大编号向小编号，逐个匹配，使用匹配的编号最小的 entry 进行判定（e.g. TOR 模式 `if (pa >= pmpaddr[i-1] && pa < pmpaddr[i])`）。
    3.  判定：
        *   如果命中且 `pmpcfg.L == 1`：强制执行权限检查。
        *   如果命中且 `privMode < M`：强制执行权限检查。
        *   如果命中且权限不符：输出 `AccessFault = true`。
        *   如果没有条目命中且 `privMode < M`：默认输出 `AccessFault = true` (默认禁止策略)。

#### 4.4.4 内部逻辑协同

##### 4.4.4.1 TLB 命中
在 TLB 命中情况下，VA -> PA 的翻译与检查单周期即可完成：VA 进入 TLB，TLB 吐出 Tag 结果。Hit 时 PA 进入 PMP 检查并返回 MMU 结果。

##### 4.4.4.2 TLB 缺失
1.  **启动 PTW**：MMU 拉低 `ready`，阻塞 LSU，PTW 状态机进入 `sL1Req`。
2.  **页表爬取**：PTW 计算 PTE 地址，调用 PMP 检查，发起 D-Cache 读请求。
3.  **获取结果**：待 PTW 返回结果后先更新 TLB，经 PMP 检查后返回结果，再拉高 MMU `ready`（此时 PTW 也进入 `sIDLE` 状态）。

##### 4.4.4.3 Flush
如果不在 PTW 爬表过程中，MMU 什么都不做；如果在 PTW 爬表过程中，PTW 检查其在途请求的 Ctx，一旦匹配则丢弃数据并回到 `sIDLE`。如果在爬取的最后一周期则允许 Entry 写入 TLB。 

你的设计构思已经涵盖了乱序执行存储子系统的最核心挑战。将 **LSQ (Load-Store Queue)** 细化为带有**动态依赖追踪（独热码掩码）**和**幂等性分流**的结构，是迈向高性能核心的标志。

关于你的提问："还有什么遗漏？"，作为教授，我发现你在**数据路径的最终闭环**上漏掉了两个关键细节：
1.  **数据裁剪与对齐器 (Data Slicer/Aligner)**：Cache 返回的是 512-bit 宽行。必须有人根据 PA 的低位和 `memWidth` 将其切成 8/16/32 位，并根据 `lsuSign` 执行符号扩展。这通常在 LSU 的输出端。
2.  **Store 的物理写使能**：SQ 条目只有在收到 **ROB Commit** 信号后，才能向 Cache 发出写请求。

下面我为你整理了详实的模块文档。

### 4.5 AGU

#### 4.5.1 职责
*   **物理本质**：一个专用的 32 位加法器。
*   **功能**：接收从 RS/PRF 发射的操作数（Base）和指令自带的立即数（Offset），计算出虚拟地址（VA）。

#### 4.5.2 接口定义
*   **Input**: `base_addr` (32 bits), `offset` (32 bits), `rob_id` (Tag).
*   **Output**: `va` (32 bits), `rob_id`.

### 4.6 PMAChecker (Physical Memory Attributes 检查器)

*   **职责**: 在地址翻译完成后，根据物理地址（PA）判定该内存区域的固有物理属性。
*   **输入**: `pa` (34 bits)。
*   **输出**: `isIO` (Bool)。
*   **内部逻辑**：单周期组合逻辑
    1.  **PMA 表**：预定义一张物理地址到 PMA 属性的映射表（可编程或硬编码）。
    2.  **地址匹配**：根据输入的 PA，查找对应的 PMA 条目。
    3.  **属性输出**：输出该地址对应的 PMA 属性。
    > *注意*: 实现中将 PMA 属性简化为 I/O 或主存——前者不幂等，不可乱序，不可缓存；后者幂等，可乱序，可缓存。

### 4.7 LSU (Load Store Unit)

#### 4.7.1 职责
管理 Load/Store 指令的发射与完成，确保乱序执行环境下的内存一致性与正确性。

#### 4.7.2 维护状态
*   **LQ (Load Queue)**: 8 项，记录在途 Load，每条指令记录如下信息：
    *   `Opcode`, `memWidth`, `rd`, `rob_id`, `branch_mask`, `epoch`, `privMode`。
    *   `VA` (计算后的虚拟地址)。
    *   `hasTranslate`, `PA`, `PMA`, `Exceptions` (翻译完成以及翻译结果)。
    *   `storeMask` (独热码形式，标记依赖的 Store)。
    *   `isIO` (Bool，标记是否为 IO 部分 Load)。
*   **SQ (Store Queue)**: 8 项，记录在途 Store，每条指令记录如下信息：
    *   `Opcode`, `memWidth`, `rd`, `rob_id`, `branch_mask`, `epoch`, `privMode`。
    *   `VA` (计算后的虚拟地址)。
    *   `hasTranslate`, `PA`, `PMA`, `Exceptions` (翻译完成以及翻译结果)。
    *   `data` (Store 数据)。
    *   `dataReady` (Bool，标记数据是否已准备好)。
    *   `isIO` (Bool，标记是否为 IO 部分 Store)。

#### 4.7.2 接口定义
*   **输入**:
    *   `dispatch_bus`: `Opcode`, `memWidth`, `rd`, `rob_id`, `branch_mask`, `epoch`.
    *   来自 AGU 的 `VA`。
    *   来自 MMU 与 PMAChecker 翻译出的 `PA`, `PMA`, `Exceptions` (Page Fault)。
    *   来自 ROB 的 `{robid, robCommit}`: 指令退休信号（通知 Store 真正写内存或IO load 真正读内存）。
    *   监听 CDB，获取操作数。
    *   来自 BRU 的 `global_flush` 与 `branch_kill_mask`。
*   **输出**:
    *   向 MMU 发送 `VA`，向 PMAChecker 发送 `PA`。
    *   向 Cache 发送 `cacheReq`: `PA`, `Data`, `MemContext`。
    *   向 CDB 发送 Load 结果或指令就绪信号。

#### 4.7.3 内部逻辑

*   **依赖追踪**：每当 Load 计算出 PA 且不为 IO Load 时，拷贝当前 SQ 中有效，PA 尚未计算或与 Load 重复的对应位作为 `storeMask`。当 Store 计算出 PA 后，广播 `PA + SQ_Index`，LQ 中所有 `store_mask` 对应位为 1 的 Load 立即进行地址比对。若不冲突，Load 将该位清零；若冲突，Load 保持该位，并标记 `is_forwarding = 1`。当 `store_mask === 0` 且 `PMA.is_io === 0` 时，Load 指令被允许发向 Cache。

*   **Store 数据准备**：Store 指令在发射时仅分配 SQ 条目，等待 CDB 上数据到达后填充 `data` 并标记 `dataReady = 1`。只有当 `dataReady = 1`，`PA` 已计算完成且该指令位于队头后，Store 会向 CDB 发出就绪信号，而 ROB 会在其到达队头后返回信号，该 `ack` 信号到达后 store 才被允许发向 Cache。
  
*   **IO Load 处理**：当 Load 被标记为 IO Load 时，忽略 `store_mask`，直接等待 ROB 队头执行信号后发起 Cache 访问，与 store 的处理方式类似，但需要在向 CDB 广播时给出自身是否为 IO 的信息。


*   **冲刷处理**：
    *   **Global Flush**: 清空 LQ 和 SQ。
    *   **Branch Kill**: 根据指令携带的 `branch_mask` 执行 `valid &= ~kill`。

## 5. 总结：MemorySystem Top

封装后的 `MemorySystem` 对外暴露极其简洁的接口：

```scala
class MemorySystemIO extends Bundle {
  // 1. 指令取指接口
  val if_req = Flipped(Decoupled(new FetchReq))
  val if_resp= Decoupled(new FetchResp) // 含指令数据 + 异常

  // 2. 数据访存接口 (LSU 内部连接，此处展示逻辑)
  // ...

  // 3. 全局控制
  val sfence = Input(new SFenceReq)
  val csr    = Input(new CSRState) // satp, status, pmp
  val flush  = Input(new GlobalFlush)
}
```
