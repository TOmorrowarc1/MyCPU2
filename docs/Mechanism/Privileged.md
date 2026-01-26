# RV32 特权架构总结

## 特权级与 CSRs 抽象

### 1. Privileged ISA 抽象概述

#### 1.1 Hart
* **定义**：Hart 是 RISC-V 中最小的独立硬件执行单元，拥有独立的程序计数器（PC）、通用寄存器组（GPRs）和控制状态寄存器组（CSRs）。
  > 实现中 Hart 与物理核心通常一一对应，因此单核处理器一般只有一个 Hart，但在多线程（SMT）架构中一个物理核心可包含多个 Hart。

#### 1.2 特权级 (Privilege Levels)
*   系统支持三个核心特权级：**M (Machine) > S (Supervisor) > U (User)**。
    > M 模式是所有实现必须具备的唯一特权级。
*   **设计目的**：实现**资源隔离**。高特权级可以访问低特权级的所有硬件资源并负责捕获和处理低特权级无法处理的异常（e.g. 访问 CSRs，执行特权指令），而低权限模式无法感知、使用高权限的 CSRs 与指令，从而保证了系统的安全性与稳定性。
    
#### 1.3 状态机
Privileged ISA 向外抽象为一个复杂的有限状态机：
*   **状态 (States)**：当前 **Privilege Mode** 和所有 **CSRs** 的值。
*   **事件 (Events)**：状态机发生迁移的原因。
    *   **Trap**：引发控制流转移的异常事件。
        *   *Exception (同步)*：由当前执行的指令触发（e.g. 非法指令、地址对齐错误、ecall）。
        *   *Interrupt (异步)*：由外部异步信号触发（e.g. 定时器、外部中断源）。
        *   *Vertical Trap* 与 *Horizontal Trap*：由是否涉及提权区分。
    *   **指令执行**：特定特权指令（e.g. mret, sret）或 Ziscr 扩展。
*   **迁移协议**：状态机迁移方式。
    1.  **静态规则**: CSRs 及其 Fields 固有的读写行为（e.g. Fields 的 WARL、WLRL、WPRI 属性）。
    2.  **动态逻辑**: 事件发生时，硬件自动执行的一系列状态迁移操作（e.g. Trap Entry：保存 PC 到 `mepc`、更新 `mcause`、关闭中断、跳转至 `mtvec` 等）。

> **Why so complicated?**
>
> 之所以 Privileged ISA 的抽象复杂度显著提升，是因为硬件的角色发生了本质转变：从 Unprivileged 阶段单纯执行逻辑运算的 **Turing Complete Machine** 进化为系统错误的 **detector** 与突发状况的 **First Handler**。这种转变要求硬件必须向软件暴露丰富的内部信息并与操作系统软件进行精密的互动。因此，硬件抽象必须从单纯的数据流处理延拓到包含权限管理、上下文切换与异常报告的复杂形态。

### 2. CSRs 架构设计

#### 2.1 地址空间与权限判定
CSRs 拥有独立的地址空间（0x000-0xFFF），其地址编码蕴含了访问权限信息：
*   **[11:10] (只读位)**：如果为 `11`，则该寄存器**只读**（如 `mcycle`, `minstret`）。
*   **[9:8] (最低权限位)**：指示访问该 CSR 所需的最低特权级（00=U, 01=S, 11=M）。
    > 若尝试写只读 CSR 或越权访问 CSR，将触发 **Illegal Instruction Exception**。

#### 2.2 CSR Fields
为了提高硬件的灵活性与兼容性，一个 CSR 内部通常被细分为多个 Field（域）。

每个 Field 根据其功能遵循不同的读写规则：

1.  **WPRI (Write Preserve, Read Ignore)**：
    *   **含义**：保留给未来扩展使用。
    *   **行为**：读取时返回全0；忽略写入操作。
2.  **WLRL (Write/Read Only Legal Values)**：
    *   **含义**：只允许合法值。
    *   **行为**：该字段只支持特定的一组合法值。读取时返回合法值；合法值直接写入，如果写入非法值，则硬件必须**根据CSR当前值与写入值**将其映射到一个**确定的**合法值（e.g. 保持原值/截断到最近的合法值/置0），写入非法值**可以但不必要**触发异常。
3.  **WARL (Write Any Values, Read Legal Values)**：
    *   **含义**：写入任意值，读取合法值。
    *   **行为**：读取时返回合法值；合法值直接写入，不合法值由hart状态与写入值映射到合法值，不报错。
4.  **Read-Only**
    *   **含义**：只读字段。
    *   **行为**：读取时返回内部值；忽略显式写入操作（可能由硬件隐式写入）。

#### 2.3 CSR Modulation
*   修改一个 CSR 的 field 可能会导致其他 CSR 对应位置的变化，这种副作用通常是单向或同步映射的，不具备递归的二级副作用。
*   **典型案例**：
    * `sstatus` 是 `mstatus` 的受限视图，因此修改 `sstatus.SIE` 会修改 `mstatus.SIE`。
    * `sie = mie & mideleg`。

### 3. 指令集 I：Zicsr 标准扩展

Zicsr 扩展定义了 6 条原子操作指令，用于在 GPR 和 CSR 之间交换数据。

#### 3.1 基本指令分类
| 指令类型 | 寄存器源 (rs1) | 立即数源 (uimm) | 语义 |
| :--- | :--- | :--- | :--- |
| **Read/Write** | `csrrw` | `csrrwi` | **交换**：读出旧值到 rd，将新值写入 CSR |
| **Read/Set** | `csrrs` | `csrrsi` | **位置 1**：读出旧值到 rd，CSR = Old \| Mask |
| **Read/Clear** | `csrrc` | `csrrci` | **位清 0**：读出旧值到 rd，CSR = Old & ~Mask |

#### 3.2 立即数版本 (CSRI)
*   `csrrwi`, `csrrsi`, `csrrci` 使用 5 位的立即数（`uimm[4:0]`）代替寄存器 `rs1`。
*   这些立即数在写入前会进行**零扩展**至 32 位。

#### 3.3 副作用规避 (x0 判定)
为了避免读写操作带来的不必要的 CSR 副作用，硬件会对 `rd` 和 `rs1` 是否为 `x0` 进行特殊判定：
1.  **对于 `csrrw`**：如果 `rd == x0`，硬件**不执行读操作**。
2.  **对于 `csrrs(i)` / `csrrc(i)`**：如果 `rs1 == x0`（或立即数为 0），硬件**不执行写操作**。


## Trap-handler 机制

### 1. 流程概述
Trap 是硬件 hart 停止当前指令流，跳转到特定特权级处理程序的机制。
1.  **受理阶段**：硬件根据异常/中断类型，自动更新 CSRs，提升特权级，跳转 PC。
2.  **处理阶段**：M-mode 软件（Trap Handler）保存上下文，执行业务逻辑，恢复上下文。
3.  **退出阶段**：执行 `mret` 指令，硬件根据 CSRs 还原特权级和 PC。

### 2. Trap 种类与触发条件

#### 2.1 Exception
`mcause` 的最高位为 0 时表示异常。常用代码如下：

| 代码 (mcause) | 异常名称 | 触发条件 |
| :--- | :--- | :--- |
| 0 | Instruction Address Misaligned | 指令跳转地址未对齐（e.g. jal目的地址错误的下一个周期）。 |
| 1 | Instruction Access Fault | IFetech 受PMP保护的物理内存或不存在的地址。 |
| 2 | Illegal Instruction | 尝试执行未定义的编码、当前特权级不允许的指令或非法访问 CSR。 |
| 3 | Breakpoint | 执行 `ebreak` 指令。 |
| 4 | Load Address Misaligned | Load 指令目标地址未对齐（e.g. lw 地址非 4 字节对齐且硬件不支持非对齐访问）。 |
| 5 | Load Access Fault | 读取受 PMP 保护或非法的物理内存。 |
| 6 | Store/AMO Address Misaligned | Store 指令目标地址未对齐。 |
| 7 | Store/AMO Access Fault | 写入受 PMP 保护或非法的物理内存。 |
| 8 | Environment Call from U-mode | U-mode 执行 `ecall`。 |
| 9 | Environment Call from S-mode | S-mode 执行 `ecall`。 |
| 11 | Environment Call from M-mode | M-mode 执行 `ecall`。 |
| 12 | Instruction Page Fault | 指令取指时发生虚实映射错误（Sv32 开启时）。 |
| 13 | Load Page Fault | 读取数据时发生虚实映射错误。 |
| 15 | Store/AMO Page Fault | 写入数据时发生虚实映射错误。 |
> 对于没有实现 PMP 的系统，1、5、7号异常不会发生；对于没有实现 S-Mode 的简化系统，9、12、13、15号异常不会发生。

#### 2.2 Interruption
`mcause` 最高位为 1。常用代码（EI, SI, TI）：
| 代码 (mcause) | 中断名称 | 触发条件 |
| :--- | :--- | :--- |
| 1 | SSI (Supervisor Software Interrupt) | 写 `mip.SSIP` 位。 |
| 3 | MSI (Machine Software Interrupt) | 写 `mip.MSIP` 位。 |
| 5 | STI (Supervisor Timer Interrupt) | `mtime >= mtimecmp`，且在 S 模式可访问对应寄存器。 |
| 7 | MTI (Machine Timer Interrupt) | `mtime >= mtimecmp`。 |
| 9 | SEI (Supervisor External Interrupt) | 外部设备触发。 |
| 11 | MEI (Machine External Interrupt) | 外部设备触发。 |

#### 2.3 Trap 优先级协议
当同一个 Hart 在同一个时钟周期检测到多个 Trap 请求时，硬件按以下顺序进行仲裁：

##### 2.3.1 Exception 内部优先级
异常的优先级通常遵循指令的**生命周期**。即：发生流水级在前的先被处理。
1.  **Breakpoint**。
2.  **指令地址检查**：Instruction Page Fault -> Instruction Access Fault。
3.  **译码阶段**：Illegal Instruction。
4.  **执行阶段**：ecall / ebreak。
5.  **访存阶段 (Load/Store)**：Address Misaligned -> Page Fault -> Access Fault。
    > 对齐错误与页错误优先级取决于是否支持不对齐地址访问。

##### 2.3.2 Interrupt 内部优先级
当多个中断同时挂起（Pending）且均已使能（Enabled）时，RISC-V 规范的默认优先级如下：
1.  **MEI (Machine External)**。
2.  **MSI (Machine Software)**。
3.  **MTI (Machine Timer)**。
4.  **SEI (Supervisor External)**。
5.  **SSI (Supervisor Software)**。
6.  **STI (Supervisor Timer)**。

##### 2.3.3 异常 vs 中断
一条指令执行时，如果既发生了异常又有一个异步中断挂起，则依照如下优先级仲裁：

*   **同步异常优先于异步中断**：目的为保证指令流的确定性。同步异常是指令本身的问题，必须立即处理；而中断是异步的，可以在该指令执行完或被撤销后处理。
    > 如果硬件实现了 NMI（通常用于处理硬件故障），NMI 的优先级高于所有同步异常。

### 3. M-U Mode Trap handler 机制

#### 3.1 CSRs II: 相关核心寄存器

##### 3.1.1 mstatus (Machine Status Register)
用于控制 hart 的当前操作状态。
*   **MIE (Bit 3)**: Machine Interrupt Enable.
    *   *特性*: WARL.
    *   *含义*: M-mode 全局中断使能开关。1 为开启，0 为关闭。
*   **MPIE (Bit 7)**: Machine Previous Interrupt Enable.
    *   *特性*: WARL.
    *   *含义*: 用于在 Trap 发生时保存进入 Trap 前的 MIE 值。
*   **MPP (Bits 12:11)**: Machine Previous Privilege.
    *   *特性*: WARL.
    *   *含义*: 记录进入 Trap 前的特权等级。`00`=U, `01`=S, `11`=M。
*   **FS (Bits 14:13)**: Floating-point Status.
    *   *含义*: 维护浮点单元状态（Dirty, Clean, Initial, Off），对上下文切换性能优化至关重要。
    *   > 未实现 F/D 扩展可以忽略。

##### 3.1.2 mie (Machine Interrupt Enable) & mip (Machine Interrupt Pending)
这两者位域完全对应。`mie` 决定是否允许该中断，`mip` 指示该中断是否正在挂起。
*   **SSIP/SSIE (Bit 1)**: Supervisor Software Interrupt Pending/Enable.
*   **MSIP/MSIE (Bit 3)**: Machine Software Interrupt Pending/Enable.
*   **STIP/STIE (Bit 5)**: Supervisor Timer Interrupt Pending/Enable.
*   **MTIP/MTIE (Bit 7)**: Machine Timer Interrupt Pending/Enable.
*   **SEIP/SEIE (Bit 9)**: Supervisor External Interrupt Pending/Enable.
*   **MEIP/MEIE (Bit 11)**: Machine External Interrupt Pending/Enable.
*   *特性*: `mie` 为 **WARL**；`mip` 中 `Bit 3` 与 `Bit 9` 为 WARL，而剩余位对软件**Read-Only**，只由硬件逻辑置位。

##### 3.1.3 mtvec (Machine Trap-Vector Base-Address Register)
*   **BASE (Bits 31:2)**: Trap 向量基地址。
    *   *特性*: **WARL**.
    *   *约束*: 必须 4 字节对齐。
*   **MODE (Bits 1:0)**: 向量模式。
    *   *特性*: **WARL**.
    *   *含义*:
        *   `00`: **Direct**。所有 Trap 均跳转至 `BASE`。
        *   `01`: **Vectored**。同步异常跳转至 `BASE`，异步中断跳转至 `BASE + 4 * cause`。

##### 3.1.4 mcause (Machine Cause Register)
*   **Interrupt (Bit 31)**: 中断标识位。
    *   *含义*: `1` 表示中断（Interrupt），`0` 表示异常（Exception）。
*   **Exception Code (Bits 30:0)**: 具体原因代号(e.g. 0x8: ecall from U-mode)。
    *   *特性*: **WLRL**.

##### 3.1.5 mepc (Machine Exception Program Counter)
*   **Value (Bits 31:0)**: 记录触发 Trap 的指令地址。
    *   *特性*: **WARL**.
    *   *约束*: 若不支持压缩指令扩展（C 扩展），则 Bit 1:0 强制为 00（4字节对齐）；若支持，则 Bit 0 强制为 0（2字节对齐）。

##### 3.1.6 mtval (Machine Trap Value Register)
*   **Value (Bits 31:0)**:
    *   *特性*: **WARL**.
    *   *含义*: 存储特定信息（如 Access Fault 的目标地址，或 Illegal Instruction 的指令机器码）。若无相关信息则清零。

##### 3.1.7 mscratch (Machine Scratch Register)
*   **Value (Bits 31:0)**:
    *   *特性*: **WARL**.
    *   *含义*: 纯软件读写寄存器，无硬件副作用。

#### 3.2 指令集 II: 相关特权指令

1.  **`ecall` (Environment Call)**:
    *   **作用**：主动触发异常，实现提权。
    *   **效果**：产生 `Environment Call from X-mode` 异常，触发 Trap 流程。
2.  **`ebreak` (Breakpoint)**:
    *   **作用**：触发 `Breakpoint` 异常，通常用于调试点。
3.  **`mret` (Machine Return)**:
    *   **作用**：从 M 模式 Trap 返回。
    *   **效果**：PC 恢复为 `mepc` 的值，特权级恢复为 `mstatus.MPP`，中断使能恢复为 `mstatus.MPIE`。

#### 3.3 状态迁移协议：Trap 受理与返回

##### 3.3.1 Trap 进入时的硬件行为
当 hart 决定接受一个 Trap 时，硬件**自动执行**以下动作：
1.  **保存 PC**: 将当前指令（异常）或下一条指令（中断）的地址写入 `mepc`。
2.  **记录原因**: 更新 `mcause`。若有必要，同时更新 `mtval`。
3.  **保存状态**:
    *   `mstatus.MPIE` = `mstatus.MIE`
    *   `mstatus.MPP` = `00` (U-mode)
4.  **修改状态**:
    *   `mstatus.MIE` = `0` (自动关中断，防止在处理程序入口处再次被中断)
    *   **权限切换**: 提升至 Machine Mode。
5.  **跳转**: `PC` = `mtvec` (根据 MODE 字段计算跳转地址)。

##### 3.3.2 MRET 返回时的硬件行为
当执行 `mret` 指令时，硬件**自动执行**以下动作：
1.  **还原状态**:
    *   `mstatus.MIE` = `mstatus.MPIE`
    *   **权限还原**: 当前特权级设为 `mstatus.MPP` 的值。
2.  **栈回收**:
    *   `mstatus.MPIE` = `1`
    *   `mstatus.MPP` = `00` (设为最低权限)
3.  **跳转**: `PC` = `mepc`。
  
> 在受理与返回之间的 M-mode 处理程序中，软件负责保存和恢复寄存器现场，以及根据 `mcause` 执行相应的业务逻辑（e.g. 系统调用处理、异常修复等）。此外，处理程序必须确保在返回前正确更新 `mepc`，以避免重复触发同一异常。
> 
> 但这部分不属于 RV32 抽象。

### 4. S-mode Trap 流程与委托机制概述

#### 4.1 Trap 处理委托机制
*   **委托**：默认情况下，所有的 Trap（无论发生在何种特权级）都会移交给 M-mode 处理，但通过设置 `medeleg` (Exception) 和 `mideleg` (Interrupt)，M-mode 可以将特定的 Trap 处理下放到 S-mode。
*   **S-mode Trap 处理条件**：
    1.  当前特权级 **低于 M-mode** (即 S 或 U)。
    2.  `mxeleg` 对应位为 1。
*   **结果**：Trap 将直接跳转到 `stvec` 定义的地址，状态保存至 `sstatus` / `sepc` / `scause` 等 S-mode CSRs 中，不经过 M-mode。

#### 4.2 CSRs III：相关核心寄存器

#### 4.2.1 sstatus (Supervisor Status Register)
`sstatus` 是 `mstatus` 的子集视图。
*   **地址**: `0x100`
*   **SIE (Bit 1)**: Supervisor Interrupt Enable.
    *   *特性*: **WARL**.
    *   *含义*: S 模式全局中断开关。1 为开启，0 为关闭。
*   **SPIE (Bit 5)**: Supervisor Previous Interrupt Enable.
    *   *特性*: **WARL**.
    *   *含义*: Trap 发生时，硬件自动备份旧的 `SIE` 值。
*   **SPP (Bit 8)**: Supervisor Previous Privilege.
    *   *特性*: **WARL**.
    *   *含义*: 记录进入 S-trap 前的特权级。`0`=U, `1`=S。

#### 4.2.2 sie (Supervisor Interrupt Enable)
*   **地址**: `0x104`
*   **SEIE (Bit 9)**: Supervisor External Interrupt Enable.
*   **STIE (Bit 5)**: Supervisor Timer Interrupt Enable.
*   **SSIE (Bit 1)**: Supervisor Software Interrupt Enable.
*   *特性*: **WARL**. 是 `mie` 中对应位的遮罩视图。

#### 4.2.3 sip (Supervisor Interrupt Pending)
*   **地址**: `0x144`
*   **SEIP (Bit 9)**: Supervisor External Interrupt Pending.
*   **STIP (Bit 5)**: Supervisor Timer Interrupt Pending.
*   **SSIP (Bit 1)**: Supervisor Software Interrupt Pending.
*   *特性*: **WARL** (但存在限制)。
    *   **SSIP** 对 S-mode 是**可读写**的（用于触发/清除软件中断）。
    *   **STIP/SEIP** 对 S-mode 通常**只读**，其值反映了 `mip` 经由 `mideleg` 委托后的状态。

#### 4.2.4 stvec (Supervisor Trap-Vector Base-Address)
*   **地址**: `0x105`
*   **BASE (Bits 31:2)**: S-mode Trap 处理程序基地址。
    *   *特性*: **WARL**. 必须 4 字节对齐。
*   **MODE (Bits 1:0)**: 向量模式（Direct 或 Vectored）。
    *   *特性*: **WARL**. 含义与 `mtvec` 相同。

#### 4.2.5 sepc (Supervisor Exception Program Counter)
*   **地址**: `0x141`
*   **Value (Bits 31:0)**: 记录触发 S-trap 的指令地址。
    *   *特性*: **WARL**.

#### 4.2.6 scause (Supervisor Cause Register)
*   **地址**: `0x142`
*   **Interrupt (Bit 31)**: 中断标识位。
*   **Exception Code (Bits 30:0)**: S-trap 具体原因代号。
    *   *特性*: **WLRL**.

#### 4.2.7 stval (Supervisor Trap Value Register)
*   **地址**: `0x143`
*   **Value (Bits 31:0)**: 记录 Page Fault 的目标虚拟地址或非法指令机器码。
    *   *特性*: **WARL**.

#### 4.2.8 sscratch (Supervisor Scratch Register)
*   **地址**: `0x140`
*   **Value (Bits 31:0)**: S-mode 专用暂存寄存器。
    *   *特性*: **WARL**.

#### 4.2.9 mideleg (Machine Interrupt Delegation Register)
*   **地址**: `0x303`
*   **Value (Bits 31:0)**:
    *   *特性*: **WARL**.
    *   *含义*: 每一位对应一个中断代码（如 Bit 5 对应 STIE）。若置 1，则该中断在 U/S 模式发生时会直接交付给 S-mode 处理。

#### 4.2.10 medeleg (Machine Exception Delegation Register)
*   **地址**: `0x302`
*   **Value (Bits 31:0)**:
    *   *特性*: **WARL**.
    *   *含义*: 每一位对应一个同步异常代码（如 Bit 12 对应 Instruction Page Fault）。置 1 表示委托给 S-mode。
    *   

### 4.3 状态迁移协议：S-mode 的特殊规则

#### 4.3.1 S-中断的来源与触发
在单核 RV32 系统中，S-mode 的中断通常由 M-mode 软件模拟：
1.  **软件中断**：S-mode 可以通过写 `sip.SSIP` 触发自己的软件中断。
2.  **定时器中断**：M-mode 处理原始定时器中断后，若发现应由 S-mode 处理，会设置 `mip.STIP` 并清除 `mie.MTIE`（或类似逻辑），从而在 `sip` 中模拟出一个时钟中断给 S-mode。

注意：如果 hart 当前运行在 M-mode，**所有的 Trap 无论如何配置，都绝对不会被委托给 S-mode**。这保证了底层固件的控制权不会被较低特权级的内核篡改。

#### 4.3.2 S-trap 进入时的硬件行为
当 hart 决定接受一个 S-trap 时，硬件**自动执行**以下动作：
1.  **保存 PC**: 将当前指令（异常）或下一条指令（中断）的地址写入 `sepc`。
2.  **记录原因**: 更新 `scause`。若有必要，同时更新 `stval`。
3.  **保存状态**:
    *   `sstatus.SPIE = sstatus.SIE`。
    *   `sstatus.SPP = Current_Mode`。
4.  **修改状态**:
    *   `sstatus.SIE = 0` (自动关中断，防止在处理程序入口处再次被中断)。
    *   **权限切换**: 提升至 S-mode。
5.  **跳转**: `PC`跳转至 `stvec` 定义的地址。

#### 4.3.3 指令集 III: SRET 指令行为
当执行 `sret` 时：
1.  **还原状态**：`Current_Mode = sstatus.SPP`，`sstatus.SIE = sstatus.SPIE`。
2.  **清理状态**：`sstatus.SPIE = 1`，`sstatus.SPP = U (0)`。
3.  **跳转**：`PC = sepc`。

## 内存保护与虚拟化

### 1. PMAs (Physical Memory Attributes) 

#### 1.1 PMAs 概念
PMAs 是物理地址空间的固有硬件属性。通过 Memory mapping，处理器对外设的访问可以映射到对特定内存地址区域的访问，因此物理地址空间被划分为不同的区域（Regions），每个区域由于硬件特性存在一组映射后的访问规则。
*   **软件交互**：软件通常无法修改 PMA。如果软件尝试以违反 PMA 的方式访问内存（例如对只读区域进行 Store），Hart 必须抛出相应的 **Access Fault**。
*   **抽象层级**：PMA 位于所有软件控制（PMP/MMU）之下，是硬件执行访问的最终物理边界。PMAs 不会在 ISA 中暴露，但是软件可以借助设备树等抽象了解需要信息。

#### 1.2 PMAs 具体属性

##### 1.2.1 访问权限与对齐访问
每个 PMA 区域都定义了一组访问权限（R/W/X），访问宽度和对齐要求。
*   **访问权限**：
    *   **Read (R)**：允许 Load 指令访问该区域。
    *   **Write (W)**：允许 Store 指令访问该区域。
    *   **Execute (X)**：允许从该区域取指执行代码。
*   **宽度与对齐要求**：某些 PMA 区域可能要求访问宽度，或是访问必须对齐（e.g. 某些外设寄存器只允许 4 字节且对齐的访问）。

##### 1.2.2 内存原子性：
规定了该区域对 RV32A 扩展指令（AMO, LR/SC）的支持能力。

*   **单核中的冲突**：在单核系统中，原子性的挑战来自于 **中断** 或 **DMA**。
*   **PMA 约束**：如果 PMA 标记某区域不支持原子操作，当执行 `amoadd.w` 等指令时，Hart 会直接抛出异常。
*   **支持能级**：
    1.  **AMO 级别**：`AMONone` < `AMOSwap` < `AMOLogic` < `AMOArithmetic` 逐级增强，从不支持 `amo` 系列指令一直到支持算术原子操作。
    2.  **LR/SC 级别**：支持预留加载/条件存储，用于实现互斥锁。

##### 1.2.3 Memory Ordering
这是 PMA 区分**主存**与**I/O设备**的关键特性，其决定了处理器是否可以进行乱序访存。

###### RVWMO (RISC-V Weak Memory Ordering)
*   **硬件行为**：对单个 hart 而言，只要内部的数据依赖关系（RAW/WAR/WAW）得到保证，Hart 不必保证不同地址的访存顺序与程序流一致也能向外提供一致的结果。软件必须使用 `FENCE` 指令来强制排序。
*   **适用区域**：主存区域符合该要求。
*   **设计目的**：允许 Hart 对访存指令进行大幅度的重排、折叠和投机执行，以隐藏存储层次结构的延迟。

###### 严格排序 (Strong Ordering)
*   **硬件行为**：PMA 规定该区域不支持乱序。所有发往该区域的写操作（Writes）必须严格按照程序顺序到达外设总线，且通常禁止投机读取（Speculative Reads）。
    > 理论上支持幂等性的部分即使要求严格排序仍可以允许投机读取，但由于幂等性检查太过困难，因此实际设计中通常会一并禁止以简化实现。
*   **适用区域**：应用于 **I/O 设备 (MMIO)** 区域。
*   **设计目的**：外设寄存器的读写往往具有**顺序敏感性**。（e.g. 必须先配置控制寄存器，才能写入数据寄存器。）

##### 1.2.4 Coherence & Cacheability
相干性确保：对于某物理地址的写入，能够最终被系统中所有感兴趣的代理（Agents）所观察到。
可缓存性确保：某物理地址区域的数据可以被缓存以提升访问性能。
*   **主存区域**：通常为硬件相干（Hardware Coherent），且可缓存（Cacheable）。
*   **I/O 区域**：通常为非相干（Non-coherent），且不可缓存（Non-cacheable）。因为访问 I/O 设备时缓存可能导致数据不一致，因此通常禁止对 I/O 区域进行缓存。
  
##### 1.2.5 Idempotency
幂等性决定了访存操作是否无副作用：读取操作不会改变数据值，且多次写入同一值的结果与一次写入相同。
*   **幂等区域 (Idempotent)**：如 RAM，此处允许处理器进行**投机取指、预取数据、合并写操作**。
*   **非幂等区域 (Non-idempotent)**：如串口 FIFO、中断清零寄存器。读取一次会导致数据出栈，此处硬件**严禁任何形式的投机访问**。每一条 Load/Store 指令必须精确触发一次物理总线事务。

#### 1.3 总结：物理地址空间的二元划分
基于上述 PMA 特性，RV32 的地址空间被清晰地划分为两类抽象：

| 特性 | **主存 (Main Memory)** | **I/O 区域 (MMIO)** |
| :--- | :--- | :--- |
| **执行权限** | 允许执行 (X) | 通常禁止执行 |
| **宽度对齐访问** | 通常支持各种宽度，对齐与否取决于设计 | 宽度与对齐取决于设计 |
| **内存序** | **RVWMO** | **Strongly Ordered** |
| **相干性** | 通常相干 | 通常非相干 |
| **可缓存性** | 通常可缓存 | 通常不可缓存 |
| **幂等性** | **Idempotent** | **Non-idempotent**|

### 2.PMP (Physical Memory Protection)

#### 2.1 设计目的
PMP 允许 M-mode 对 S/U-mode 的物理地址访问权限进行 4 bytes 精度地控制，甚至可以作用域 M-mode 指令自身。
*   **权限分级**：PMP 规则默认对 S/U 模式生效。只有当设置了特定的 **Lock (L)** 位时，规则才对 M-mode 强制生效。
*   **检查位置**：在开启虚拟内存的情况下，PMP 的检查发生在 **VA -> PA 之后**，作为数据流向物理总线的最后一关。
*   **异常类型**：如果 PMP 检查失败，产生的异常为：
    *   **Instruction Access Fault** (mcause = 1)
    *   **Load Access Fault** (mcause = 5)
    *   **Store/AMO Access Fault** (mcause = 7)

#### 2.2 CSRs IV: PMP CSRs

PMP 由两组 CSR 构成：配置寄存器 `pmpcfgX` 和地址寄存器 `pmpaddrX`。

##### 2.2.1 pmpcfgX (PMP Configuration Registers)
*   **地址**：`0x3A0` (`pmpcfg0`) 至 `0x3AF` (`pmpcfg15`)。
*   **布局**：RV32 的 XLEN 为 32。每个 `pmpcfgX` 寄存器包含 **4 个** entry 的配置，每个 entry 占用 8 bits。（e.g. `pmpcfg0`: 管理 Entry 0-Bits 7:0, Entry 1-Bits 15:8, Entry 2-Bits 23:16, Entry 3-Bits 31:24）。
*   **8-bit 字段**：
    *   **R (Bit 0)**：读权限。
    *   **W (Bit 1)**：写权限。
    *   **X (Bit 2)**：执行权限。
    *   **A (Bits 4:3) [Address Matching Mode]**：地址匹配模式。
        *   `00` (**OFF**)：禁用该 PMP Entry。
        *   `01` (**TOR**)：Top of Range。匹配范围为 $[pmpaddr_{i-1}, pmpaddr_i)$。若 $i=0$，下限视为 0。
        *   `10` (**NA4**)：Naturally Aligned 4-byte。匹配 $pmpaddr_i$ 指定的 4 字节。
        *   `11` (**NAPOT**)：Naturally Aligned Power-of-Two。匹配 $2^n$ 字节的对齐区域（$n \ge 3$）。
    *   **Bits 6:5**：WPRI。
    *   **L (Bit 7) [Lock]**：锁定状态。
        *   **L=0**：规则仅约束 S/U 模式，M 模式拥有全权。
        *   **L=1**：规则对 **M/S/U** 模式同时生效。且该 entry 的 `pmpcfg` 和 `pmpaddr` 变为只读（TOR模式下 pmpaddr[i-1] 同样只读），除非 Hart 重置。

##### 2.2.2 pmpaddrX (PMP Address Registers)
*   **地址**：`0x3B0` 至 `0x3EF`（共 64 个）。
*   **存储内容**：在 RV32 中，`pmpaddr` 存储的是 **物理地址的第 [33:2] 位**。
    *   这允许 RV32 系统（理论上通过 2 位扩展）支持最高 34 位的物理地址空间。
    *   写入时，`pmpaddr` 通常是 `PhysicalAddress >> 2`。
> 如果不实现，pmpaddr 与 8 bits 连线为全 0 即可。 

#### 2.3 地址匹配机制

##### 2.3.1 TOR (Top of Range)
*   判定逻辑：如果 $pmpaddr_{i-1} \le \text{Address} < pmpaddr_i$，则命中 Entry $i$。
*   特例：Entry 0 的下限默认为地址 `0`。

##### 2.3.2 NA4 (Naturally Aligned 4-byte)
*   匹配 `pmpaddr[i]` 指定的 4 字节。

##### 2.3.3 NAPOT (Naturally Aligned Power-of-Two)
*   **计算方式**：寻找 `pmpaddr[i]` 中从右向左的第一个0，前方一致则匹配成功。
*   **示例**：
    *   `pmpaddr` 尾部为 `...1011` (二进制)：对应 $2^3 = 8$ 字节范围。
    *   `pmpaddr` 尾部为 `...0111` (二进制)：对应 $2^5 = 32$ 字节范围。
    *   公式：若 `pmpaddr` 低 $k$ 位全为 1，则匹配范围大小为 $2^{k+3}$ 字节。

#### 2.4 优先级与冲突判定

*   **最小编号优先原则 (Lowest-index Priority)**：
    当一个地址命中多个 PMP Entry 时，**编号最小**的 Entry 决定最终权限。这允许 M-mode 通过较小编号的 Entry 设置“白名单”或“特例”，而用较编号大的 Entry 设置“通用规则”。

*   **默认拒绝 vs 默认通过**：
    *   **S/U 模式**：如果地址**没有命中**任何已启用的 PMP Entry（至少实现一个 PMP Entry时），硬件将**拒绝**该请求。这要求 S-mode 运行前必须至少配置一个 PMP 条目来放行物理内存。
    *   **M 模式**：如果地址没有命中任何 PMP Entry，该访问**默认通过**。

#### 2.5 PMP 与虚拟内存的关系

##### 2.5.1 受理时机
*   **取指（Instruction Fetch）**：检查 X 权限。
*   **访存（Load/Store）**：检查 R/W 权限。
*   **页表走访（Page Table Walk）**：硬件在查询页表时，也必须经过 PMP 检查。如果页表本身存放在被 PMP 拒绝访问的区域，会触发 **Page Fault** 或 **Access Fault**。

##### 2.5.2 与 Sv32 的协作
1.  **翻译优先**：Hart 首先将 VA 通过 TLB/页表翻译为 PA。
2.  **物理检查**：拿到 PA 后，立即送入 PMP 逻辑进行权限比对。
3.  **最终执行**：只有两层检查（PTE 的权限位 + PMP 的权限位）全部通过，物理总线事务才会发出。

##### 2.5.3 变更同步 (Synchronization)
*  由于 PMP 结果可能被缓存在 Hart 内部（例如 TLB 中可能会缓存 PMP 的判定结果以加速），当软件修改 PMP CSR 后，必须执行 `sfence.vma`（且通常带有 `x0, x0` 参数）来刷新 TLB 和指令流水线，确保后续指令使用最新的 PMP 配置。

### 3. Sv32 虚拟内存

#### 3.1 CSRs V：虚拟化控制与状态

虚拟内存的行为受 `mstatus` 和 `satp` 的共同约束。

##### 3.1.1 mstatus 关键控制位
这些位决定了特权模式下的地址翻译逻辑及权限边界：
*   **MPRV (Modify Privilege, Bit 17)**: 
    *   *特性*：**WARL**.
    *   *含义*：当 `MPRV=1` 时，M-mode 的 Load/Store 按 `mstatus.MPP` 指定的特权级进行地址翻译（即模拟低特权级的访存行为）。对取指（Instruction Fetch）无效。
*   **SUM (permit Supervisor User Memory access, Bit 18)**: 
    *   *特性*：**WARL**.
    *   *含义*：若 `SUM=0`，S-mode 访问 `U=1`（用户页）会触发 Page Fault。这是一种硬件级的安全防护。
*   **MXR (Make Executable Readable, Bit 19)**: 
    *   *特性*：**WARL**.
    *   *含义*：若 `MXR=1`，对标记为“仅执行（R=0, X=1）”的页面也允许 Load 操作。
*   **TVM (Trap Virtual Memory, Bit 20)**: 
    *   *特性*：**WARL**. 
    *   *含义*：若 `TVM=1`，S-mode 尝试读写 `satp` 或执行 `SFENCE.VMA` 指令将触发非法指令异常（Illegal Instruction Exception）。

##### 3.1.2 satp (Supervisor Address Translation and Protection)
*   **地址**：`0x180`
*   **MODE (Bit 31)**: **WARL**. `0`=Bare (VA=PA); `1`=Sv32。
*   **ASID (Bits 30:22)**: **WARL**. 地址空间标识符。**可选实现**，若硬件不支持则硬连线为 0。用于标识不同进程，避免进程切换时必须刷新 TLB。
*   **PPN (Bits 21:0)**: **WARL**. 根页表的物理页帧号，可据之计算其物理地址。

#### 3.2 基数树结构
Sv32 使用二级基数树结构管理 32 位地址空间。

*   **虚拟地址 (VA) 布局 (32 bits)**:
    *   **VPN[1] (Bits 31:22)**: 一级索引（10 bits）。
    *   **VPN[0] (Bits 21:12)**: 二级索引（10 bits）。
    *   **Page Offset (Bits 11:0)**: 页内偏移（12 bits），对应 4 KiB 大小。
*   **物理地址 (PA) 布局**:
    *   取决于具体实现，标准 RV32 支持最高 34 位物理地址。由 **PPN[1], PPN[0], Offset** 构成。

#### 3.3 页表项 (PTE) 
每个页表项占 4 字节（32 bits）。

##### 3.3.1 PTE 字段定义
*   **PPN[1:0] (Bits 31:10)**: 物理页帧号。
*   **RSW (Bits 9:8)**: 软件保留位，硬件忽略。
*   **D (Bit 7)**: Dirty，页是否被写入过。
*   **A (Bit 6)**: Accessed，页是否被访问过。
*   **G (Bit 5)**: Global，全局页（不随 ASID 刷新）。
*   **U (Bit 4)**: User，用户可访问位。
*   **X, W, R (Bits 3:1)**: 执行、写、读权限。
*   **V (Bit 0)**: Valid，有效位。

##### 3.3.2 叶子节点判定规则
*   如果 `R=W=X=0`：此 PTE 是指向**下一级页表**的指针。
*   如果 `R, W, X` 中任一位置 1：此 PTE 是**叶子节点**（映射到物理页）。
    *   *一级叶子*：4 MiB 超级页 (Superpage)。
    *   *二级叶子*：4 KiB 标准页。
  
#### 3.4 虚拟内存访问过程与错误处理

##### 3.4.1 Page Table Walking
1.  **起始**：令 $a = satp.PPN \times 4096$。
2.  **一级查找**：读取地址 $a + VPN[1] \times 4$ 处的 PTE。
3.  **合法性检查**：若 `V=0` 或 `(R=0 且 W=1)`，立即抛出 Page Fault。
4.  **叶子/路径判定**：
    *   若为**叶子节点**：检查超级页对齐（若 `PTE.PPN[0] != 0`，报 Page Fault）。
    *   若为**非叶子节点**：令 $a = PTE.PPN \times 4096$，重复上述过程查找 `VPN[0]`。
5.  **权限校验**：根据 `mstatus` 中的 `SUM/MXR` 及 PTE 中的 `R/W/X/U` 检查当前特权级是否有权访问。
6.  **A/D 位维护**：如果硬件不自动置位 A/D，且该访问违反 A/D 状态，抛出 Page Fault。
7.  **合成 PA**：将 PTE 中的 PPN 与 VA 中的 Offset 拼接。

##### 3.4.2 可能触发的报错
*   **Instruction Page Fault (12)**: 取指失败。
*   **Load Page Fault (13)**: 读数据失败。
*   **Store/AMO Page Fault (15)**: 写数据或原子操作失败。
    > 每次内存访问都可能导致 PMP 报错。

#### 3.5 SFENCE.VMA：内存序与同步机制

*   **指令格式**：`SFENCE.VMA vaddr, asid`
*   **设计目的**：软件更新页表后，硬件 TLB 可能持有旧的映射。该指令强制同步内存访问与页表结构，冲刷 TLB 缓存。
*   **行为**：
    *   如果修改了 `satp` 或任何 PTE，必须执行此指令。
    *   `SFENCE.VMA x0, x0`：冲刷当前 Hart 的所有 TLB 项。
    *   指定 `asid` 可以只冲刷特定线程的映射。
    *   指定 `vaddr` 可以只冲刷特定虚拟地址的映射。
