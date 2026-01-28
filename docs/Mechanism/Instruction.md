# RV32I + Privileged 指令集

包含如下指令: RV32I + Zifencei + Zicsr + Machine/Supervisor Mode Privileged Instructions。

## 符号说明
*   **`rs1`, `rs2`**: 源寄存器索引 (Source Register)
*   **`rd`**: 目的寄存器索引 (Destination Register)
*   **`imm`**: 立即数 (Immediate)
*   **`PC`**: 当前指令地址
*   **`NPC`**: 下一条指令地址 (Next PC)
*   **`CSR`**: 控制状态寄存器

---

## 1. RV32 Base Integer

这部分指令在 **ALU**、**LSU** 与 **BRU** 中执行，不涉及特权级切换。

### 1.1 算术与逻辑运算 (Register-Register)
**Opcode**: `0110011`

| 指令 | BitPattern (31-0) | 译码控制信号 | 语义与状态机行为 |
| :--- | :--- | :--- | :--- |
| **ADD** | `0000000_rs2_rs1_000_rd_0110011` | `ALU_ADD` | `rd = rs1 + rs2` |
| **SUB** | `0100000_rs2_rs1_000_rd_0110011` | `ALU_SUB` | `rd = rs1 - rs2` |
| **SLL** | `0000000_rs2_rs1_001_rd_0110011` | `ALU_SLL` | `rd = rs1 << (rs2 & 0x1F)` |
| **SLT** | `0000000_rs2_rs1_010_rd_0110011` | `ALU_SLT` | `rd = (signed(rs1) < signed(rs2)) ? 1 : 0` |
| **SLTU**| `0000000_rs2_rs1_011_rd_0110011` | `ALU_SLTU`| `rd = (unsign(rs1) < unsign(rs2)) ? 1 : 0` |
| **XOR** | `0000000_rs2_rs1_100_rd_0110011` | `ALU_XOR` | `rd = rs1 ^ rs2` |
| **SRL** | `0000000_rs2_rs1_101_rd_0110011` | `ALU_SRL` | `rd = rs1 >> (rs2 & 0x1F)` (逻辑右移) |
| **SRA** | `0100000_rs2_rs1_101_rd_0110011` | `ALU_SRA` | `rd = rs1 >> (rs2 & 0x1F)` (算术右移) |
| **OR** | `0000000_rs2_rs1_110_rd_0110011` | `ALU_OR` | `rd = rs1 \| rs2` |
| **AND** | `0000000_rs2_rs1_111_rd_0110011` | `ALU_AND` | `rd = rs1 & rs2` |

### 1.2 算术与逻辑运算 (Register-Immediate)
**Opcode**: `0010011` (I-Type, 立即数符号扩展)

| 指令 | BitPattern | 译码控制信号 | 语义与状态机行为 |
| :--- | :--- | :--- | :--- |
| **ADDI** | `imm[11:0]_rs1_000_rd_0010011` | `ALU_ADD, IMM` | `rd = rs1 + sext(imm)` |
| **SLTI** | `imm[11:0]_rs1_010_rd_0010011` | `ALU_SLT, IMM` | `rd = (rs1 < sext(imm)) ? 1 : 0` |
| **SLTIU**| `imm[11:0]_rs1_011_rd_0010011` | `ALU_SLTU, IMM`| `rd = (rs1 < sext(imm)) ? 1 : 0` (无符号比较) |
| **XORI** | `imm[11:0]_rs1_100_rd_0010011` | `ALU_XOR, IMM` | `rd = rs1 ^ sext(imm)` |
| **ORI** | `imm[11:0]_rs1_110_rd_0010011` | `ALU_OR, IMM` | `rd = rs1 \| sext(imm)` |
| **ANDI** | `imm[11:0]_rs1_111_rd_0010011` | `ALU_AND, IMM` | `rd = rs1 & sext(imm)` |
| **SLLI** | `0000000_shamt_rs1_001_rd_0010011` | `ALU_SLL, IMM` | `rd = rs1 << shamt` |
| **SRLI** | `0000000_shamt_rs1_101_rd_0010011` | `ALU_SRL, IMM` | `rd = rs1 >> shamt` |
| **SRAI** | `0100000_shamt_rs1_101_rd_0010011` | `ALU_SRA, IMM` | `rd = rs1 >> shamt` (算术) |

### 1.3 大立即数与 PC 相关
| 指令 | BitPattern | 译码控制信号 | 语义与状态机行为 |
| :--- | :--- | :--- | :--- |
| **LUI** | `imm[31:12]_rd_0110111` | `ALU_ADD, OP1_0, OP2_IMM` | **RS优化**: `op1=0, op2=imm<<12`。无需读 RS1。 |
| **AUIPC**| `imm[31:12]_rd_0010111` | `ALU_ADD, OP1_PC, OP2_IMM`| `rd = PC + (imm<<12)`。在 Dispatch 阶段取 PC。 |

### 1.4 控制流 (Branch & Jump)
**Opcode**: `1100011` (Branch), `1101111` (JAL), `1100111` (JALR)

| 指令 | BitPattern | 译码控制信号 | 语义与状态机行为 |
| :--- | :--- | :--- | :--- |
| **JAL** | `imm[20|10:1|11|19:12]_rd_1101111` | `BRU, JMP` | `rd = PC+4`; `Target = PC + sext(offset)`. 无条件跳转。 |
| **JALR** | `imm[11:0]_rs1_000_rd_1100111` | `BRU, JMP_REG` | `rd = PC+4`; `Target = (rs1 + sext(offset)) & ~1`. **注意最低位清零**。 |
| **BEQ** | `imm..._rs2_rs1_000_..._1100011` | `BRU, OP_EQ` | 若 `rs1 == rs2`, 跳转到 `PC + offset`。 |
| **BNE** | `imm..._rs2_rs1_001_..._1100011` | `BRU, OP_NE` | 若 `rs1 != rs2`, 跳转。 |
| **BLT** | `imm..._rs2_rs1_100_..._1100011` | `BRU, OP_LT` | 若 `rs1 < rs2` (有符号), 跳转。 |
| **BGE** | `imm..._rs2_rs1_101_..._1100011` | `BRU, OP_GE` | 若 `rs1 >= rs2` (有符号), 跳转。 |
| **BLTU** | `imm..._rs2_rs1_110_..._1100011` | `BRU, OP_LTU` | 若 `rs1 < rs2` (无符号), 跳转。 |
| **BGEU** | `imm..._rs2_rs1_111_..._1100011` | `BRU, OP_GEU` | 若 `rs1 >= rs2` (无符号), 跳转。 |

*   **状态机交互**: BRU 计算结果与预测不符 -> 触发 `Branch_Flush` -> 翻转 `I-Epoch` -> 恢复 RAT Snapshot。

### 1.5 访存指令 (Load / Store)
**Opcode**: `0000011` (Load), `0100011` (Store)

| 指令 | BitPattern | 译码控制信号 | 语义与状态机行为 |
| :--- | :--- | :--- | :--- |
| **LB/LH/LW** | `imm_rs1_func3_rd_0000011` | `LSU, LOAD, Size` | 计算 EA，查 TLB。携带 `D-Epoch` 和 `BranchMask` 进入 LSQ。 |
| **LBU/LHU** | `imm_rs1_func3_rd_0000011` | `LSU, LOAD, Unsigned` | 同上，结果进行无符号扩展。 |
| **SB/SH/SW** | `imm_rs2_rs1_func3_..._0100011`| `LSU, STORE, Size` | 计算 EA。**不写内存**，存入 Store Buffer。ROB Commit 时触发写。 |
| **FENCE** | `0000_pred_succ_00000_000_00000_0001111` | `LSU, FENCE` | **阻塞流水线**。等待 Store Buffer 排空 (Drain) 后才允许后续指令 Dispatch。 |

---

## 2. Zifencei (指令流同步)

这是处理自修改代码的关键，必须实现。

| 指令 | BitPattern | 译码控制信号 | 语义与状态机行为 |
| :--- | :--- | :--- | :--- |
| **FENCE.I** | `000000000000_00000_001_00000_0001111` | `SYS, FLUSH_ICACHE` | **序列化**。ROB 头部触发 -> 排空 Store Buffer -> **Invalidate I-Cache** -> **Global Flush Pipeline** -> 从 `PC+4` 重新取指。 |

---

## 3. Zicsr (CSR 访问指令)

处理特权级状态的核心。所有 CSR 访问均引发**流水线序列化**。

**Opcode**: `1110011` (System)

| 指令 | BitPattern | 译码控制信号 | 语义与状态机行为 |
| :--- | :--- | :--- | :--- |
| **CSRRW** | `csr_rs1_001_rd_1110011` | `CSR, WR, RD` | `t = CSR; CSR = rs1; rd = t`。原子读写。 |
| **CSRRS** | `csr_rs1_010_rd_1110011` | `CSR, SET, RD` | `t = CSR; CSR \|= rs1; rd = t`。读并置位。 |
| **CSRRC** | `csr_rs1_011_rd_1110011` | `CSR, CLR, RD` | `t = CSR; CSR &= ~rs1; rd = t`。读并清除。 |
| **CSRRWI**| `csr_uimm_101_rd_1110011` | `CSR, WR, IMM` | 使用立即数 (rs1位置) 更新 CSR。 |
| **CSRRSI**| `csr_uimm_110_rd_1110011` | `CSR, SET, IMM` | 使用立即数置位。 |
| **CSRRCI**| `csr_uimm_111_rd_1110011` | `CSR, CLR, IMM` | 使用立即数清除。 |

*   **交互**: 译码发现 CSR -> `Stall Fetch` -> 等待 ROB 空 -> 执行 -> `Global Flush`。

---

## 4. Privileged Instructions (特权与陷阱)

这些指令在 `System` Opcode (`1110011`) 下，通过 `funct12` 区分。它们触发 CPU 状态的重大变更。

| 指令 | BitPattern (31-20 ... 6-0) | 行为与状态机迁移 (ROB Head 触发) |
| :--- | :--- | :--- |
| **ECALL** | `000000000000_00000_000_00000_1110011` | **Environment Call**。<br>根据当前 Mode (M/S/U) 触发对应的 Exception (Code 8/9/11)。<br>写入 `cause`, `epc` -> 跳转 `xtvec` -> `Global Flush`。 |
| **EBREAK**| `000000000001_00000_000_00000_1110011` | **Breakpoint**。<br>触发 Exception Code 3。<br>行为同上。 |
| **MRET** | `001100000010_00000_000_00000_1110011` | **Return from M-mode**。<br>1. 恢复 PC = `mepc`。<br>2. 恢复 Priv = `mstatus.MPP`。<br>3. `MIE` = `MPIE`。<br>4. 触发 `Global Flush`。 |
| **SRET** | `000100000010_00000_000_00000_1110011` | **Return from S-mode**。<br>1. 恢复 PC = `sepc`。<br>2. 恢复 Priv = `sstatus.SPP`。<br>3. `SIE` = `SPIE`。<br>4. 触发 `Global Flush`。 |
| **WFI** | `000100000101_00000_000_00000_1110011` | **Wait For Interrupt**。<br>暂停流水线取指，直到 `mip` 有效中断位拉高。<br>大作业中可实现为 `NOP` 或简单的 Stall 状态机。 |
| **SFENCE.VMA** | `0001001_rs2_rs1_000_00000_1110011` | **Supervisor Memory Fence**。<br>1. 序列化执行。<br>2. 通知 MMU **清空 TLB** (根据 rs1/rs2 过滤)。<br>3. 触发 `Global Flush`。 |