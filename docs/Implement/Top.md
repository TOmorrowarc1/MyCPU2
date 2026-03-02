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
*   **职责**：维护如下**架构寄存器->物理寄存器**映射 -- Frontend RAT (推断状态), Retirement RAT (提交状态), 最多 4 个 SnapShots；维护 Free List (物理寄存器池，使用位矢量表示 busy 部分) 以及 Retirement Free List (ROB 提交的正被占据的物理寄存器对应 busy 位矢量)，4 个 Snapshots 中各有一份 Free List；维护 Ready List，与 Free lists 类似但是表示数据可用的寄存器 (Retirement Ready List 恒为全 1)。重命名之后将对应数据（操作数对应立即数或寄存器，valid 位）送入 RS。
*   **输入**：来自 Decoder 的 `{Data, IsBranch}` 请求；来自 ROB 的 `{CommitPreRd, CommitPhyRd, GlobalFlush}`；来自 BRU 的 `{BranchFlush, SnapshotID, BranchMask}`。
*   **逻辑简述**：
    *   **重命名**：接受 Decoder 请求时执行：
        *   **分配新物理寄存器**：如果 rd != x0，则从 Free List 分配`PhyRd` 并将该寄存器状态置为 busy。
        *   **更新 Frontend RAT**：将架构寄存器 rd 映射更新为 `PhyRd`。
    *   **分支快照**：若 `IsBranch === true.B`，保存当前 Frontend RAT 与 Free List 到 Snapshots 中。4 份 Snapshots 对应 4 位位矢量 `BranchMask`，以当次使用快照对应独热码为 `SnapshotID`。
    *   **恢复与冲刷**：
        *   **Global Flush**：直接将 Frontend RAT 覆盖为 Retirement RAT，回收所有 Snapshots。
        *   **Branch Mispredict**：通过 `SnapshotID` 瞬间恢复 Frontend RAT，同时根据 `branch_mask` 回收不再需要的 Snapshots。
    *   **正常回收**：将 ROB 提交阶段发回的 `PreRd` 回收进 Free List；将 BRU 传来的预测成功分支（SnapshotID 非 0，但 BranchFlush 为 0）对应的 Snapshot 回收。同时将 CDB 广播发回的 `Rd` 回收进 Ready List。
        > 以上内容中回收的含义为将 busy 位置 0，表示该物理资源可再分配使用。
*   **输出**：
    *   向 ROB 发送 `{LogicRd, PhyRd, PreRd, BranchMask}`。
    *   向 RS 发送 `{Data, BranchMask}`。

## 2. Backend

### 2.1 RS (Reservation Stations)
*   **组成**：由 Dispatcher、ALURS 与 BRURS 组成。
*   **职责**：接收 Decoder 送入的控制方面信息与来自 RAT 的数据相关信息，dispatch 到各个执行单元。维护因数据冒险与结构冒险暂时无法执行的指令队列，其中 ALU 与 BRU 在对应 RS 中维护；Load/Store 指令由 LSU 自主维护，Zicsr 与其他特权级相关指令由 ZicsrU 维护，RS 模块只负责分派。
*   **输入**：
    *   来自 Decoder 的 `{MinOps, Exceptions, RobID, Prediction, PC, Imm, privMode}`。
    *   来自 RAT 的 `{Data, BranchMask}`。
    *   来自 CBD 的 `{ResultReg, data}`。
    *   来自 ROB 的 `GlobalFlush`。
    *   来自 BRU 的 `BranchFlush` 与 `BranchMask`。
*   **逻辑简述**：
    *   **接收并分派**：从 Decoder 接收 `{MinOps, Exceptions, RobID, Prediction}`，从 RAT 接收 `{Data, BranchMask}`，Dispatcher 利用 `MinOps` 中指令 opcode 进行分派，将信息发送到 4 个 EU 中的对应 RS 或 Queue。
    *   **监听**：在对应 RS 中存储指令控制与数据有关信息。时刻比对 CBD 上的 `ResultReg`。若匹配则将对应数据置为 valid。
    *   **发射**：RS 使用 Waiting Pool，当一条指令的所有操作数都 valid ，即该指令 valid 且对应 EU ready 时将最早的指令需要的源寄存器值从 PRF 中取出，发送到 EU 中求取结果。
    *   **冲刷**：如果 ROB 将 `GlobalFlush` 信号拉高，则立刻通过将 busy 位置 0 方式冲刷所有指令。如果 BRU 拉高 `BranchFlush` 则根据 `BranchMask` 进行位运算，找出依赖于对应分支指令并清理。
*   **输出**：
    *   向 PRF 发送 `{SourceReg1, SourceReg2}`。
    *   向 EU 发送对应 `{Opcode, Data, RobID, Prediction}`。   

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
*   **组成**：LSQ (访存队列), AGU & PMP (地址生成与权限检查), Cache(Cache + AXI + MainMemory)。
*   **职责**：处理所有内存访问，维护访存顺序一致性，处理地址生成与权限检查。
*   **输入**：
    *   来自 RS 的 `{LSUOp, Imm, RobID, PrivMode, D-Epoch, BranchMask}`。
    *   来自 PRF 的 `{BaseAddr, StoreData}`。
    *   来自 ROB 的 `CommitStore/CommitIO` 信号。
    *   来自 Memory Access System 的 `MemResponse`。
*   **逻辑简述**：
    *   **AGU**：计算 `PA = Base + Imm`（物理地址直接计算）。
    *   **权限检查**：进行 PMP 检查。若异常，标记 `Exception`。
    *   **Load 处理**：
        *   查 Store Queue (Forwarding)，若无冲突则发往 D-Cache。
        *   若为 I/O：进入 `Wait_Commit` 状态，等待 ROB 信号。
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
    *   `globalFlush` 信号：来自 CSRsUnit 的全局冲刷信号，代表异常或中断处理。
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
        2.  **Handle Exception**：若提交，则向 CSRsUnit 输出该指令 exception 与 pc 信息，以便 CSRsUnit 做出 Trap 处理。
        3.  **Handle Serialization**：
            *   若为 **Store** 或 **hasSideEffect 为 1** ：在局部维护一个状态机，进入 `wait_done` 模式并发信号给 LSU，等待从总线传来的第二次对应 `robId` 信号然后退休。
                > 不排除在过程中出现异常可能，ROB 行为取决于第二次总线信号。
            *   若为 **CSR Write**：在局部维护一个状态机，发信号给 ZICSRU 并等待总线上的第二次对应信号，然后触发 `globalFlush` 并退休。
                > 不排除在过程中出现异常可能，ROB 行为取决于第二次总线信号。
            *   若为 **xRET**：向 CSRsUnit 发送对应信息，行为类似于 handle exception。
            *   若为 **WFI**：当做 NOP 处理，直接退休。
            *   若为 **FENCE.I**：拉高对应 `fence.i` 信号，等待 CBD 上传回的 Ack 信号，收到信号后提交。
            *   若为 **SFENCE.VMA**：同上，对应 `sfence.v` 信号。
        4.  **Normal Retire**：通知 RAT `Retire(lrd, prd, p_old)`，释放 `p_old` 到 Free List，令 `robHead` 前进一位。
    *   **维护纪元**：若 `globalFlush` 发生，更新 `DEpoch`。
    *   **维护 CSRPending**：当 CSR 指令或 xRET 指令或 `FENCE` `FENCE.I` `SFENCE` 入队时，拉高 `CSRPending` 信号，在这类指令提交时拉低该信号（由于阻塞取值一段时间内只有一条该类指令）。
*   **输出**：
    *   纪元信息：`IEpoch` `DEpoch`。
    *   取值暂停信息：`CSRPending`。
    *   指令 Ack 信息：`storeEnable` `csrEnable`。
    *   异常与 CSR 更新信息：`exception` `mret` `sret`。
    *   内存部分更新信息：`fence.i` `sfence.vma`。
    *   `RobTail`: 给 Decoder 用于分配 ID。
    *   RAT 更新信息：`{commitArchRd, commitPhyRd, commitOldRd}`。

### 3.3 CSRsUnit
*   **职责**：物理 CSR 寄存器堆，实现 CSR 相关特权级逻辑，是 CPU 特权级管理的核心模块。
    *   **CSR 读写检查**：对 CSR 访问进行权限检查、字段规则验证和副作用处理
    *   **Trap 处理**：实现 M-mode 和 S-mode 的 Trap 进入与返回机制（包括 mret、sret 指令处理）
    *   **集中式状态管理**：统一管理所有 CSR 寄存器的更新，避免在多个模块中分散更新
    *   **特权级控制**：向 Fetcher 和 Decoder 提供当前特权级信息
    *   **内存访问控制**：提供 PMP 配置和 satp 配置给 LSU 进行权限检查和地址翻译
    *   **全局控制**：产生全局冲刷信号，管理异常和中断处理
*   **输入**：
    *   **来自 ZICSRU 的读请求**：`{csrAddr, privMode}`。
    *   **来自 ZICSRU 的写请求**：`{csrAddr, privMode, data}`。
    *   **来自 ROB 的 Commit & Trap 信号**：`{exception(valid, cause, tval), pc, isCSR, mret, sret}`，用于异常处理，返回指令处理和 Zicsr 指令处理（触发全局更新）。
*   **逻辑简述**：
    *   **CSR 读写检查逻辑**：
        *   **地址权限判定**：在 Decoder 中完成，CSR 剩余检查集中于地址对应寄存器存在与否以及 PMPRegs L 位类似的检查。
        *   **字段读写规则**：实现 WPRI（Write Preserve, Read Ignore）、WLRL（Write/Read Only Legal Values）、WARL（Write Any Values, Read Legal Values）、Read-Only 四种字段类型的读写规则。
        *   **CSR Modulation**：处理 CSR 之间的同步映射关系，如 sstatus 与 mstatus 的同步、sie 与 mie 的同步。
        > Modulation 不具备递归的二级副作用，需确保每次 Modulation 只影响一级 CSR。
    *   **Trap-handler 状态转移逻辑**：
        *   **异常与中断判定**：根据 ROB 输入的 `exception` 信号判定是否发生异常，提取异常原因（cause）和相关值（tval）。同时根据外部中断输入和当前状态判定是否发生中断，注意异常优先级高于中断。
        *   **M-mode Trap 进入**：保存 PC（异常时为当前指令，中断时为下一条指令）到 mepc，更新 mcause 和 mtval，保存 mstatus 状态（MPIE=MIE, MPP=当前特权级），修改 mstatus（MIE=0），切换特权级到 M-mode，跳转到 mtvec。
        *   **MRET 返回**：还原 mstatus（MIE=MPIE），还原特权级（当前特权级=MPP），清理 mstatus（MPIE=1, MPP=U），跳转到 mepc。
        *   **S-mode Trap 进入**：检查委托条件（当前特权级 < M 且 medeleg/mideleg[cause]==1），保存 PC 到 sepc，更新 scause 和 stval，保存 sstatus 状态（SPIE=SIE, SPP=当前特权级），修改 sstatus（SIE=0），切换特权级到 S-mode，跳转到 stvec。
        *   **SRET 返回**：还原 sstatus（SIE=SPIE），还原特权级（当前特权级=SPP），清理 sstatus（SPIE=1, SPP=U），跳转到 sepc。
    *   **集中式 CSR 更新逻辑**：
        *   **CSR 寄存器维护**：维护 mstatus, mie, mepc, mcause, mtval, mtvec, mscratch, mideleg, medeleg, mip（M-mode）和 sstatus, sie, sepc, scause, stval, stvec, sscratch, satp（S-mode）等关键寄存器
        *   **避免分散式更新**：使用 MuxLookup 集中处理多个更新源，避免在多个 when 块中分散更新 CSR。更新优先级为 异常 > 中断 > CSR 指令。
    *   **特权检查**：配合 Decoder/LSU 提供当前的权限位（PrivMode），PMP 相关控制寄存器，地址翻译控制位（TVM, MPRV, stap）。
*   **输出**：
    *   **向 ZICSRU 提供 CSR 访问结果**：读取为 `{data, exception}`，包括读取的 CSR 数据和异常信息；写入为 `{exception}`，表示写入结果的异常信息。
    *   **向 Fetcher 提供当前 privMode**：用于指令取指时的特权级检查
    *   **向 Decoder 暴露当前 privMode**：用于指令解码时的特权级检查
    *   **提供全局 flush 信号**：`{globalFlush(valid, pc)}`，用于异常，中断处理和 CSR/mret/sret 指令提交时的流水线冲刷。
    *   **提供内存相关控制信息**：`{pmp 配置}`，用于内存系统的权限检查。
*   **实现要点**：
    *   **集中式状态管理**：整个 Unit 分为三个阶段，读取输入，计算，抉择 + 更新 + 输出，避免在多个 when 语句中分散处理 CSR 更新逻辑；分别计算由异常产生的结果，由中断产生的结果与 CSR 指令读写的结果，再**使用 MuxLookup** 选择更新源。

## 4. 内存模块

### 4.1 MainMemory

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

// 2. 定义各通道的独立 Bundle

// 读地址通道 (AR - Read Address)
class AXIARBundle extends Bundle {
  val addr = UInt(32.W)
  val id   = new AXIID        // 事务 ID (区分 I/D Cache)
  val len  = UInt(8.W)        // Burst 长度，宽总线通常为 0
  val user = new AXIContext   // 携带元数据
}

// 读数据通道 (R - Read Data)
class AXIRBundle extends Bundle {
  val data = UInt(512.W)      // 512-bit 宽数据
  val id   = new AXIID
  val last = Bool()           // Burst 结束标志，宽总线返回结果当周期拉高
  val user = new AXIContext   // 回传元数据
}

// 写地址通道 (AW - Write Address)
class AXIAWBundle extends Bundle {
  val addr = UInt(32.W)
  val id   = new AXIID
  val len  = UInt(8.W)
  val user = new AXIContext
}

// 写数据通道 (W - Write Data)
class AXIWBundle extends Bundle {
  val data = UInt(512.W)
  val strb = UInt(64.W)       // 字节掩码 (64 bytes -> 64 bits)
  val last = Bool()
}

// 写响应通道 (B - Write Response)
class AXIBBundle extends Bundle {
  val id   = new AXIID
  val user = new AXIContext
}

// 3. 定义宽总线接口
class WideAXI4Bundle extends Bundle {
  // --- 读路径 ---
  val ar = Decoupled(new AXIARBundle)
  val r = Flipped(Decoupled(new AXIRBundle))

  // --- 写路径 ---
  val aw = Decoupled(new AXIAWBundle)
  val w = Decoupled(new AXIWBundle)
  val b = Flipped(Decoupled(new AXIBBundle))
}
```

#### 4.1.3 模块定义与输入方向

**职责**：模拟物理内存行为（高延迟、宽带宽），支持仿真文件的预加载。

##### 输入/输出接口
*   `io.axi`: **`Flipped(new WideAXI4Bundle)`**
    *   **来源**：`AXIArbiter`。
    *   `Flipped` 意味着 MainMemory 是 **Slave**（从设备）：它接收 AR/AW/W 的 `valid`，输出 AR/AW/W 的 `ready`；它输出 R/B 的 `valid`，接收 R/B 的 `ready`。

#### 4.1.4 内部逻辑与时序模型

由于 `SyncReadMem` 是同步读（1 周期延迟），而我们需要模拟更长的物理延迟（例如 10 周期），因此需要内嵌一个的 **有限状态机**。

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
*   **仲裁**：对于该实现两者不会同时为 1。
*   **动作 (若读请求)**：
    *   锁存 `ar` 信息到 `req_reg`。
    *   置计数器 `counter := LATENCY - 1`。
    *   跳转 `sReadDelay`。
*   **动作 (若写请求)**：
    *   锁存 `aw` 与 `w` 信息，如果字节掩码存在且地址未能与 64 位掩码对齐，则通过组合逻辑移位后存入 `req_reg`。
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
*   **请求汇聚**：将 I-Cache 的读请求与 D-Cache 的读/写请求进行仲裁，按照优先级顺序送往 `Wide-AXI4` 主总线。
*   **响应分发**：通过 AXI ID 识别总线返回的数据（R 通道）或写确认（B 通道），将其路由回正确的 Cache 模块。

#### 4.2.2 维护状态
*   **`isBusy` (Reg)**：标记当前总线是否正在处理一个尚未完成的事务。
    *   对于读：从 `AR.fire` 开始，到 `R.fire && R.bits.last` 结束。
    *   对于写：从 `AW.fire` 开始，到 `B.fire` 结束。

#### 4.2.3 接口

##### 4.2.3.1 输入 (Inputs)
| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `IcacheReq` | I-Cache | `Decoupled(new AXIARBundle)` | 指令取指 Miss 请求 |
| `DcacheRReq`| D-Cache | `Decoupled(new AXIARBundle)` | 数据读取 Miss 请求 |
| `DcacheWReq`| D-Cache | `Decoupled(new AXIAWBundle)` + `Decoupled(new AXIWBundle)` | 数据写回 |
| `mainMemResp`| MainMemory | `Flipped(Decoupled(new AXIRBundle))` + `Flipped(Decoupled(new AXIBBundle))` | 总线回传的数据与写确认 |

##### 4.2.3.2 输出 (Outputs)
| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| `IcacheResp` | I-Cache | `Flipped(Decoupled(new AXIRBundle))` | 返回 512-bit 指令块 |
| `DcacheResp` | D-Cache | `Flipped(Decoupled(new AXIRBundle))` + `Flipped(Decoupled(new AXIBBundle))` | 返回 512-bit 数据块或写回确认 |
| `mainMemReq` | MainMemory | `WideAXI4Bundle` | 发向主存的统一 AXI 请求 |

#### 4.2.4 内部逻辑简述

##### 4.2.4.1 Ready 信号处理 (仲裁逻辑)
仲裁器优先级固定：**D-Cache > I-Cache**。

*   **逻辑公式**：
    *   `canIssue := !isBusy` (总线空闲)。
    *   `dWins := dCacheRReq.valid || dCacheWReq.valid`。
    *   `iWins := iCacheReq.valid && !dWins`。
*   **握手输出**：
    *   `dCacheRReq.ready := canIssue && dWins && dCacheRReq.valid`。
    *   `dCacheWReq.ready := canIssue && dWins && dCacheWReq.valid`。
    *   `iCacheReq.ready := canIssue && iWins`。
*   **状态迁移**：当 `mainMemReq.fire` 或 `mainMem.aw.fire` 时，`isBusy` 置 1，将对应请求转发到主线。
   > 规定 AXIID 规定 I-cache 为 0，D-cache 为 1。

##### 4.2.4.2 结果回传处理 (分发逻辑)

*   **读数据回传 (R Channel)**：
    *   inflightId 为 mainMem 返回信息中的 id 值。
    *   `iCacheResp.valid := mainMem.r.valid && (inflightId === 0.U)`
    *   `dCacheRResp.valid := mainMem.r.valid && (inflightId === 1.U)`
    *   `mainMem.r.ready := Mux(inflightId === 1.U, dCache.r.ready, iCache.r.ready)`
*   **写确认回传 (B Channel)**：只有 D-Cache 会写
    *   `dCacheWResp.valid := mainMem.b.valid`。
    *   `mainMem.b.ready := dCache.b.ready`。

### 4.3 Cache

*   **职责**：
    *   **物理地址缓存**：提供基于物理地址（PA）的高速数据读取与写入。
    *   **主存访问**：通过 512-bit 宽总线向主存发起请求。
    *   **一致性与冲刷**：执行 FENCE.I 的脏行清空，并配合 Epoch/BranchMask 处理在途指令的逻辑废除。
*   **组成（维护状态）**：Status(Pipeline, AXIPending, FENCEIActive), Tag Array (SRAM), Data Array (SRAM), Status Array (Regs), AXIReqBuffer (Reg, Size=2), FENCE.I Controller(记录当前扫描到行数与 Done).
*   **输入**：
    *   **来自 LSU 读接口**：`{PA, Width, Ctx(branchMask, robId)}`。
    *   **来自 LSU 写接口**: `{PA, Width, Ctx(branchMask, robId), wData}`。
    *   **来自系统控制平面**：`globalFlush`, `branchFlush`, `branchOH`。
    *   **来自 ROB**：`fenceIReq`。
    *   **来自 AXI 总线返回**：`{Ctx(Epoch), RData, BValid}`。
*   **输出**：
    *   **向请求方回复**：`{ReadData, Ctx}` 或 `{B, Ctx}`。
    *   **向 AXI 总线发送**：`{arAddr, awAddr, wData, wStrb, User(Ctx)}`。
    *   **状态信号**：`fenceIDone`。
*   **逻辑简述**：
    *   **Ready 反压**：只有 `AXIReq` 为空且 `!isFenceIActive` 时才从 LSU 或 Fetecher 部分获取 Request。
    *   **内部流水线逻辑**：使用两级流水线锁存请求信息，有两条流水线，分别对应读与写。
        *   **Stage 1**：锁存进入请求的 `PA`、`Width`、`Ctx` 和可能的 `wdata`。发起对 Tag 和 Data SRAM 的同步读取。
        *   **Stage 2**：请求信息前进一位。由于输入已是物理地址，直接比对 `SRAMTag` 与 `ReqPATag`。
        *   **结果分发**：如果比对成功，则根据锁存的请求信息（因此锁存的请求也是流水线式寄存器），将读结果与 Ctx 送回对应的接口或将写结果写入对应 Cache Line 并置脏位，返回写响应与 Ctx ；如果 Miss，则开始总线访问主存。
    *   **缺失与回写逻辑**：
        *   若 `Miss` 且该行 `Dirty`，将状态由 `Pipeline` 切换到 `AXIReqPending`，生成的总线事务压入 AXIReqBuffer，该周期不接收新的指令进入 Cache。
           > Buffer 实际只用于记录当周期在途事务信息，也就是最多读与写两条。
        *   通过 AXIAribiter 发起总线请求，携带 `Epoch` 以便后续冲刷逻辑使用。
           > 先发起写请求，再发起读请求。
        *   两个请求结果都返回后，清空 AXIReqBuffer，如正常执行一般返回结果，状态切回 `Pipeline`。
    *   **冲刷逻辑**：
        *   **逻辑撤销**：若 `branchFlush` 命中在途指令寄存器的 `Ctx.mask`，该请求匹配不返回信息，不匹配不进行主存访问。
        *   **总线过滤**：已进入 AXI Buffer 的请求不可撤销。在 `branchFlush` 拉高时，检查所有 AXI Buffer 中 Entry `branchMask` 并修改其 `aborted` 字段，若只拉高 `branchOH` 则移除对应分支依赖。在 `globalFlush` 拉高时移除所有 buffer 内请求。当数据从 AXI 返回，先后检查其携带的 `user.epoch` 并与对应 Entry 匹配。若纪元过期或被标记 `killed`，则**吃掉数据，放空槽位且不更新 SRAM 阵列**。
    *   **FENCE.I 序列化**：
        *   一旦 `fenceIReq` 有效，检查当前 Cache 状态，若为 `AXIReqPending` 则先等待主线事务完成并返回结果；若为 `Pipeline` 则将状态切换至 `FENCEIActive` ，从当周期开始拉低输入接口 ready ，进入独占式的扫描回写状态，直至完成。
        *   扫描回写：每周期检查一行 status ，清除所有 Cache Line 的有效位和脏位，将脏位为 1 的行写回主存，每次主线请求写回一行并暂停检查。
        *   完成条件：当所有行的有效位和脏位都为 0 时，拉高 `fenceIDone` 信号，将 Controller 记录行数重置为 0 ，同时 Controller.Done 置为 1，若 Done 为 1 则 fenceIReq 高位不触发任何逻辑，也不进行扫描回写。当 fenceIReq 信号拉低时，Controller.Done 置为 0，状态切回 `Pipeline`。

### 4.4 AGU & PMP (Address Generation Unit & Physical Memory Protection)

#### 4.4.1 职责
*   **计算物理地址**：接收从 LSU 分派的操作数（Base）和指令自带的立即数（Offset），计算出物理地址（PA）。
*   **地址对齐检查**：检测 PA 是否根据访存位宽（Byte/Half/Word）正确对齐，若未对齐则输出对齐异常。
*   **PMP 权限检查**：检测物理地址 PA 与请求权限是否符合 PMP 规则，若不符合则输出访问异常。
   > 上述逻辑均在一周期之内完成。

#### 4.4.2 接口定义
*   **输入**：
    *   `baseAddr` (32 bits)：基地址寄存器值
    *   `offset` (32 bits)：立即数偏移量
    *   `memWidth`：访存位宽（Byte/Half/Word）
    *   `memOp`：访存类型（Load/Store）
    *   `privMode`：当前特权级（U/S/M）
    *   `robId`：对应指令的 ID，返回结果后要赋值给对应 LSQ Entry
*   **输出**：
    *   `pa` (32 bits)：计算出的物理地址
    *   `exception`：异常包（对齐异常或访问异常）
    *   `robId`：原样带回

#### 4.4.3 内部逻辑

##### 4.4.3.1 地址生成逻辑
*   **物理地址计算**：`pa = baseAddr + offset`
*   **对齐检查**：
    *   Byte：无需对齐
    *   Half：最低 1 位必须为 0
    *   Word：最低 2 位必须为 0
    *   若未对齐，生成 `Load/Store_Address_Misaligned` 异常

##### 4.4.3.2 PMP 权限检查逻辑（组合逻辑）
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

### 4.5 LSQ (Load Store Queue)

#### 4.5.1 职责
管理 Load/Store 指令的发射与完成，确保乱序执行环境下的内存一致性与正确性。

#### 4.5.2 维护状态
*   **LQ (Load Queue)**: 8 项，记录在途 Load 指令，每个 Entry 记录如下信息：
    *   `memWidth`, `rd`, `robId`, `branchMask`, `epoch`, `privMode`, `rs1Tag`, `rs1Ready`。
    *   `PAReady` `PA` (计算并检验后返回的物理地址)。
    *   `Exceptions` (同时可能返回的异常)。
    *   `storeMask` (独热码形式，标记依赖的 Store)。
*   **SQ (Store Queue)**: 8 项，记录在途 Store，每条指令记录如下信息：
    *   `memWidth`, `rd`, `robId`, `branchMask`, `epoch`, `privMode`, `rs1Tag`, `rs1Ready`, `rs2Tag`, `rs2Ready`。
    *   `PAReady` `PA` (计算并检验后返回的物理地址)。
    *   `Exceptions` (同时可能返回的异常)。

#### 4.5.3 接口定义
*   **输入**:
    *   `LSUdispatch`: `Opcode`, `memWidth`, `rd`, `robId`, `branchMask`, `epoch`.
    *   来自 AGU & PMP 的 `PA` 和 `Exception`。
    *   来自 ROB 的 `{robId, robCommit}`: 指令退休信号（通知 Store 真正写内存或IO load 真正读内存）。
    *   监听 CDB，获取操作数。
    *   来自 BRU 的 `globalFlush` 与 `branchKillMask`。
*   **输出**:
    *   向 AGU & PMP 发送地址生成请求。
    *   向 Cache 发送 `cacheReq`: `PA`, `Data`, `MemContext`。
    *   向 CDB 发送 Load 结果或指令就绪信号。

#### 4.5.4 内部逻辑

*   **依赖追踪**：每当 Load 计算出 PA 且不为 IO Load 时，拷贝当前 SQ 中有效，PA 尚未计算或与 Load 重复的对应位作为 `storeMask`。当 Store 计算出 PA 后，广播 `PA + sqIndex`，LQ 中所有 `storeMask` 对应位为 1 的 Load 立即进行地址比对。若不冲突，Load 将该位清零；若冲突，Load 保持该位，并标记 `isForwarding = 1`。当 `storeMask === 0` 且 `isIO === 0` 时，Load 指令被允许发向 Cache。

*   **Store 数据准备**：Store 指令在发射时仅分配 SQ 条目，等待 CDB 上数据到达后填充 `data` 并标记 `dataReady = 1`。只有当 `dataReady = 1`，`PA` 已计算完成且该指令位于队头后，Store 会向 CDB 发出就绪信号，而 ROB 会在其到达队头后返回信号，该 `ack` 信号到达后 store 才被允许发向 Cache。

*   **IO Load 处理**：当 Load 被标记为 IO Load 时，忽略 `storeMask`，直接等待 ROB 队头执行信号后发起 Cache 访问，与 store 的处理方式类似，但需要在向 CDB 广播时给出自身是否为 IO 的信息。

*   **冲刷处理**：
    *   **Global Flush**: 清空 LQ 和 SQ。
    *   **Branch Kill**: 根据指令携带的 `branchMask` 执行 `valid &= ~kill`。

### 4.6 MemorySystem Top

#### 4.6.1 职责
封装整个内存系统，对外提供简洁的接口。内部包含 I-Cache、D-Cache、AXIArbiter、MainMemory、LSU（包含 LQ、SQ、AGU & PMP）等子模块。

#### 4.6.2 接口定义
封装后的 `MemorySystem` 对外暴露极其简洁的接口：

```scala
class MemorySystemIO extends Bundle {
  // 1. 指令取指接口
  val if_req = Flipped(Decoupled(new FetchReq))
  val if_resp = Decoupled(new FetchResp) // 含指令数据 + 异常

  // 2. LSU 接口（使用 LSUDispatch）
  val lsu_dispatch = Flipped(Decoupled(new LSUDispatch))
  val lsu_agu_resp = Decoupled(new Bundle {
    val pa = UInt(32.W) // 物理地址
    val exception = new Exception // 异常包
    val ctx = new MemContext // 内存上下文
  })
  val lsu_cache_req = Flipped(Decoupled(new LSUCacheReq))
  val lsu_cache_resp = Decoupled(new LSUCacheResp)
  val lsu_commit = Input(new LSUCommit)
  val lsu_cdb = Output(new LSUCDBMessage)

  // 3. 全局控制
  val sfence = Input(new SFenceReq)
  val csr = Input(new Bundle {
    val pmp = UInt(128.W) // PMP 配置 (简化版)
  })
  val flush = Input(new Bundle {
    val global = Bool() // 全局冲刷信号
    val branchKill = UInt(4.W) // 分支冲刷掩码
  })
}
```

#### 4.6.4 数据流

##### 4.6.4.1 指令取指流程
1. **Fetcher → I-Cache**：发送 `FetchReq`（包含 PC、上下文信息）
2. **I-Cache**：
   - 检查 Cache，若命中直接返回
   - 若 Miss，通过 AXIArbiter 请求 MainMemory
3. **I-Cache → Fetcher**：返回 `FetchResp`（包含指令数据、上下文、异常）

##### 4.6.4.2 数据访存流程
1. **Dispatch → LSU**：发送 `LSUDispatch`（包含操作码、位宽、数据包、robId、分支掩码、特权级）
2. **LSU → AGU&PMP**：
   - 发送地址生成请求（baseAddr、offset、memWidth、memOp、privMode、ctx）
   - 接收物理地址、异常、上下文
3. **LSU → D-Cache**：
   - 发送 Cache 请求（PA、isWrite、data、strb、ctx）
   - 接收 Cache 响应（data、ctx、exception）
4. **LSU → CDB**：发送 `LSUCDBMessage`（robId、phyRd、data、isIO、exception）

##### 4.6.4.3 全局控制流程
1. **SFENCE.VMA**：ROB 发送 `SFenceReq`，清空相关缓存
2. **FENCE.I**：ROB 发送信号，I-Cache 执行脏行清空
3. **Global Flush**：CSRsUnit 发送信号，清空所有 Cache 和队列
4. **Branch Flush**：BRU 发送信号，根据分支掩码清除对应指令

#### 4.6.5 时序说明
*   **I-Cache**：2 周期流水线（Tag 查找 + 数据返回）
*   **D-Cache**：2 周期流水线（Tag 查找 + 数据返回）
*   **AGU&PMP**：1 周期组合逻辑
*   **MainMemory**：可配置延迟（默认 10 周期）
*   **LSU**：Load 指令 2-4 周期（AGU + Cache），Store 指令等待提交后执行
