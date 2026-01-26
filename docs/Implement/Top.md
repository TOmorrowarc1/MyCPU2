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

**2. Decoder**
*   **职责**：将指令解析到微指令；进行寄存器重命名请求与快照请求；异常检测。
*   **输入**：来自 Icache 的 `{Instruction, PC, PrivMode, InsEpoch, Prediction, Exception}`；来自 ROB 的 `FreeRobID`， `GlobalFlush` `CSRDone` 与来自 BRU 的 `BranchFlush`。
*   **逻辑简述**：
    *   **指令解析**：检测 `InsEpoch` 是否过期，若是则丢弃，否则继续解析。将 `Instruction` 解码为 `MinOps`（EU 使能信号）、`Data`（rd, rs1, rs2）、`Exceptions`（IF + Decode 可能的异常）。
    *   **寄存器重命名请求**：将 `Data` 送入 RAT，获取对应的物理寄存器号 `PhyRd` 和旧物理寄存器号 `PreRd` 以及依赖的数据。
    *   **快照请求**：若指令解析为分支指令，向 RAT 请求创建分支快照，即拉高 `IsBranch` 信号。
    *   **异常处理**：将 `Exceptions` 向 Dispatch 单元透传。
    *   **阻塞逻辑**：若 ROB, RS, LSQ, 或 RAT Free List 满，拉低对 Icache 的 `ready`；如果解析出指令属于 Ziscr 扩展或是 Privileged ISA，拉高 `IFStall` 信号暂停取指，直到 CSR 指令完成（`CSRDone`）。
*   **输出**：
    *   向 RAT 发送 `{Data, IsBranch}` 请求。
    *   向 ROB 发送 `{RobID, Exception, Prediction}`。
    *   向 RS 发送 `{MinOps, Exceptions, RobID, Prediction}`。
    *   向 Fetcher 发送 `IFStall` 信号。

**3. RAT (Register Alias Table)**
*   **职责**：维护如下**架构寄存器->物理寄存器**映射 -- Frontend RAT (推断状态), Retirement RAT (提交状态), 最多 4 个 SnapShots ；维护 Free List (物理寄存器池，使用位矢量表示 busy 部分) 以及 Retirement Free List(ROB 提交的正被占据的物理寄存器对应 busy 位矢量)；重命名之后将对应数据（操作数对应立即数或寄存器，valid 位）送入 RS。
*   **输入**：来自 Decoder 的 `{Data, IsBranch}` 请求；来自 ROB 的 `{CommitPreRd, CommitPhyRd, GlobalFlush}`；来自 BRU 的 `{BranchFlush, SnapshotID, BranchMask}`。
*   **逻辑简述**：
    *   **重命名**：接受 Decoder 请求时执行：
        *   **分配新物理寄存器**：如果 rd != x0，则从 Free List 分配`PhyRd` 并将该寄存器状态置为 busy。
        *   **更新 Frontend RAT**：将架构寄存器 rd 映射更新为 `PhyRd`。
    *   **分支快照**：若 `IsBranch === true.B`，保存当前 Frontend RAT 到 SnapShots 中。4 份 Snapshots 对应 4 位位矢量 `BranchMask`，以当次使用快照对应独热码为 `SnapshotID`。
    *   **恢复与冲刷**：
        *   **Global Flush**：直接将 Frontend RAT 覆盖为 Retirement RAT，回收所有 Snapshots。
        *   **Branch Mispredict**：通过 `SnapshotID` 瞬间恢复 Frontend RAT，同时根据 `branch_mask` 回收不再需要的 Snapshots。
    *   **正常回收**：将 ROB 提交阶段发回的 `PreRd` 回收进 Free List；将 BRU 传来的预测成功分支（SnapshotID 非 0，但 BranchFlush 为 0）对应的 Snapshot 回收。
    > 以上内容中回收的含义为将 busy 位置 0，表示该物理资源可再分配使用。
*   **输出**：
    *   向 ROB 发送 `{LogicRd, PhyRd, PreRd, BranchMask}`。
    *   向 RS 发送 `{Data, BranchMask}`。

## II. Backend

**4. RS (Reservation Stations)**
*   **组成**：分布式分布，由 ALURS 与 BRURS 组成。
*   **职责**：接收 Decoder 送入的控制方面信息与来自 RAT 的数据相关信息，dispatch 到各个执行单元。维护因数据冒险与结构冒险暂时无法执行的指令队列，其中ALU 与 BRU 在对应 RS 中维护而 LSU 自主维护，RS 模块只负责分派。
*   **输入**：
    *   来自 Decoder 的 `{MinOps, Exceptions, RobID, Prediction}`。
    *   来自 RAT 的 `{Data, BranchMask}`。
    *   来自 CBD 的 `{ResultReg, data}`。
    *   来自 ROB 的 `GlobalFlush`。
    *   来自 BRU 的 `BranchFlush` 与 `BranchMask`。
    *   来自 PRF 的 `ReadData`。
*   **逻辑简述**：
    *   **接收并分派**：从 Decoder 接收 `{MinOps, Exceptions, RobID, Prediction}`，从 RAT 接收 `{Data, BranchMask}`，利用 `MinOps` 中指令 opcode 进行分派。
    *   **监听**：在对应 RS 中存储指令控制信息，数据合法与否。时刻比对 CDB 上的 `ResultReg`。若匹配则将对应数据置为 valid。
    *   **发射**：RS 使用 FIFO，当一条指令的所有操作数都 valid ，即该指令valid 且对应 EU ready 时将最早的指令需要的源寄存器值从 PRF 中取出，发送到 EU 中求取结果。
    *   **冲刷**：如果 ROB 将 `GlobalFlush` 信号拉高，则立刻通过 busy 位置 0 方式冲刷所有指令。如果 BRU 拉高 `BranchFlush` 则根据 `BranchMask` 进行位运算，找出依赖于对应分支指令并清理。

**5. ALU**：处理普通计算。

**6. BRU**：计算跳转结果并与 `Prediction` 比较。若与预测不符，**立即**拉高 `BranchFlush`，命 `InsEpoch` 进行状态迁移。

**7. LSU (Load Store Unit)**：先写成最基本的 FIFO，之后慢慢处理。

**8. CBD (Common Bus Data)**
*   **职责**：多对一仲裁（ALU/BRU/LSU $\rightarrow$ 总线），将通过仲裁的指令结果广播到 RS、PRF 与 ROB。
*   **输入**：
    *   来自 ALU、BRU、LSU 的 `{ResultRd, data, RobID, Exception}`。
    *   来自 ROB 的 `GlobalFlush`。
    *   来自 BRU 的 `BranchFlush`。
*   **逻辑简述**：
    *   **仲裁**：当多个 EU 同时请求总线时，按照预设优先级（BRU > LSU > ALU）进行仲裁，选出一个 EU 的结果进行广播。
    *   **冲刷**：若 ROB拉高 `GlobalFlush` 或 BRU 拉高 `BranchFlush`，则当前周期的总线不进行广播。
*   **输出**：`{ResultRd, data, RobID, Exception}`。
  
### 3. 状态及其维护

**9. PRF (Physical Register File)**
*   **职责**：储存数据的物理寄存器。
*   **写端口**：连接 **CDB**，监听 `ResultRd` 与 `Data` ，若不为 x0 则写入数据。
*   **读端口**：连接 **RS 的发射端**，在 Issue 阶段接受源寄存器编号，读取出对应值并返回。

**10. ROB (Reorder Buffer)**
*   **职责**：按 PO 维护指令状态：完成情况、异常情况、分支依赖、目标架构寄存器、目标物理寄存器、原映射指向物理寄存器、用于快捷冲刷的分支掩码。同时维护全局的纪元信号，处理异常、提交。为部分特殊指令提供服务：CSR 访问指令在此执行，Store 指令需要访存许可，MRET 等特权指令在此与 CSRsUnit 交互。
*   **提交 (Commit)**：
    *   **普通指令**：更新 Retirement RAT，回收 `prd_old`。
    *   **Store/AMO**：发送 `Commit_Write` 信号给 LSU。
    *   **Trap 处理**：若 Head 条目有 `exception_valid`，发出 `global_flush`，更新 `mcause/mepc`，重定向 PC 到 `mtvec`。
    *   **CSR/System 指令**：执行序列化更新。

**11. CSRsUnit**
*   **职责**：维护 Control and Status Registers (CSRs)，处理 CSR 读写请求，处理特权指令（MRET 等），提供特权级别信息。

### 4. 内存子系统 (Memory Access System)

**12. 三层逻辑架构**
*   **Layer 1 (Translation)**：ITLB, DTLB。共享一个硬件 **Page Table Walker (PTW)**。
*   **Layer 2 (Protection)**：**PMP & PMA 检查器**。对所有翻译后的 PA 进行物理法则校验。
*   **Layer 3 (Controller)**：AXI4-Wide (512-bit) 接口。管理与物理 DRAM 的 Burst 交互。
