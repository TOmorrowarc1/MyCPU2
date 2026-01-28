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
    *   **全局控制信息维护** ：管理纪元信息 `IEpoch` 与 `DEpoch`，用于内存访问的顺序一致性维护，分别在分支冲刷或全局冲刷，和仅全局冲刷时更新；管理 `CSRPending` 信号，标记 CSR 指令或某些改变 CSR 状态指令正在提交中以阻塞前端取指。
    *   **指令入队**：当 ROB 未满时，将 Decoder 输入接口 `ready` 拉高，输出对应 `RobTail` 位置创建新 Entry，根据 Decoder 与 RAT 输入填写对应信息，置 `busy = 1`，`completed = 0`，如果进入时指令已经出现异常则 `complete = 1`。
    *   **指令完成**：当 CBD 送入 `{RobID, Exception, phyRd, data}` 时，找到对应 Entry，将 `completed` 置 1，若 `Exception` 有效则更新 Entry 的 `Exception` 字段。
    *   **分支冲刷**：当 BRU 送入 `{snapshotId, branchFlush, redirectPC}` 时，若 `branchFlush` 有效则根据 `snapshotId` 定位到对应分支指令，清除所有依赖该分支的 Entry（通过 `branchMask` 位运算）并更新 `IEpoch`，若否则单纯移除 `branchMask`。依据预测结果更新 `prediction` 字段以便提交时用于更新 BTB。 
    *   **提交状态机**：
        1.  **Check Head**：若 `!completed`，等待。
        2.  **Handle Exception**：若 `exception.valid`，触发 `globalFlush`，更新 CSR (`mcause/mepc`)，跳转 `mtvec`。
        3.  **Handle Serialization**：
            *   若为 **Store**：在局部维护一个状态机，进入 `wait_store` 模式并发信号给 LSU，等待从总线传来的第二次对应 `robId` 信号然后退休。
                > 不排除在过程中出现异常可能，ROB 行为取决于第二次总线信号
            *   若为 **CSR Write**：在局部维护一个状态机，发信号给 ZICSRU 并等待总线上的第二次对应信号，然后触发 `globalFlush` 并退休。
                > 不排除在过程中出现异常可能，ROB 行为取决于第二次总线信号
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
