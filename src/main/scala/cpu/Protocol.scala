package cpu

import chisel3._
import chisel3.util._

// ============================================================================
// 0. 基础配置与枚举定义 (Configuration & Types)
// ============================================================================

// 架构参数配置特质
trait CPUConfig {
  val XLEN = 32
  val AddrWidth = 32
  val InstWidth = 32
  val DataWidth = 32

  // 规模参数 (建议参数化，这里先给定具体位宽)
  val RobIdWidth = 5 // 支持 32 条指令乱序
  val PhyRegIdWidth = 7 // 128 个物理寄存器
  val ArchRegIdWidth = 5 // 32 个架构寄存器
  val SnapshotIdWidth = 2 // 4 个 Snapshots
  val EpochWidth = 2 // 4 个 Epochs

  def InstW = UInt(InstWidth.W)
  def AddrW = UInt(AddrWidth.W)
  def DataW = UInt(DataWidth.W)
  def RobTag = UInt(RobIdWidth.W)
  def PhyTag = UInt(PhyRegIdWidth.W)
  def ArchTag = UInt(ArchRegIdWidth.W)
  def SnapshotId = UInt(SnapshotIdWidth.W)
  def SnapshotMask = UInt((1 << SnapshotIdWidth).W)
  def EpochW = UInt(EpochWidth.W)
}

// 枚举类型助记符

// UnPrivileged 部分
object ImmType extends ChiselEnum {
  val I_TYPE, S_TYPE, B_TYPE, U_TYPE, J_TYPE, R_TYPE, Z_TYPE = Value
}

object Src1Sel extends ChiselEnum {
  val REG, PC, ZERO = Value
}

object Src2Sel extends ChiselEnum {
  val REG, IMM, FOUR = Value
}

object ALUOp extends ChiselEnum {
  val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU, NOP = Value
}

object LSUOp extends ChiselEnum {
  val LOAD, STORE, NOP = Value
}

object LSUWidth extends ChiselEnum {
  val BYTE, HALF, WORD = Value
}

object LSUsign extends ChiselEnum {
  val UNSIGNED, SIGNED = Value
}

object BRUOp extends ChiselEnum {
  val BEQ, BNE, BLT, BGE, BLTU, BGEU, JAL, JALR, NOP = Value
}

object WBEnable extends ChiselEnum {
  val WB, NOP = Value
}

// Privileged 部分

// 特权级信息: User, Supervisor, Machine
object PrivMode extends ChiselEnum {
  val U = Value(0.U)
  val S = Value(1.U)
  val M = Value(3.U)
}

// 异常原因编码 (mcause)
object ExceptionCause {
  val INSTRUCTION_ADDRESS_MISALIGNED = 0.U(4.W)
  val INSTRUCTION_ACCESS_FAULT = 1.U(4.W)
  val ILLEGAL_INSTRUCTION = 2.U(4.W)
  val BREAKPOINT = 3.U(4.W)
  val LOAD_ADDRESS_MISALIGNED = 4.U(4.W)
  val LOAD_ACCESS_FAULT = 5.U(4.W)
  val STORE_ADDRESS_MISALIGNED = 6.U(4.W)
  val STORE_ACCESS_FAULT = 7.U(4.W)
  val ECALL_FROM_U_MODE = 8.U(4.W)
  val ECALL_FROM_S_MODE = 9.U(4.W)
  val ECALL_FROM_M_MODE = 11.U(4.W)
  val INSTRUCTION_PAGE_FAULT = 12.U(4.W)
  val LOAD_PAGE_FAULT = 13.U(4.W)
  val STORE_PAGE_FAULT = 15.U(4.W)
}

// 中断原因编码 (mcause)
object InterruptCause {
  val SSI = 1.U(4.W)
  val MSI = 3.U(4.W)
  val STI = 5.U(4.W)
  val MTI = 7.U(4.W)
  val SEI = 9.U(4.W)
  val MEI = 11.U(4.W)
}

object ZicsrOp extends ChiselEnum {
  val RW, RS, RC, NOP = Value
}

// 特殊指令标记
object SpecialInstr extends ChiselEnum {
  val BRANCH, STORE, FENCE, FENCEI, CSR, MRET, SRET, SFENCE, ECALL, EBREAK, WFI,
      NONE = Value
}

// ============================================================================
// 1. 通用元数据
// ============================================================================

// 在流水线的多个阶段透传的数据。
class Exception extends Bundle {
  val valid = Bool()
  val cause = UInt(4.W)
  val tval = UInt(32.W)
}

class Prediction extends Bundle {
  val taken = Bool()
  val targetPC = UInt(32.W)
}

// ============================================================================
// 2. 前端接口 (Frontend Interfaces)
// ============================================================================

// Fetcher -> Icache （反方向为 Icache -> Decoder）
class IFetchPacket extends Bundle with CPUConfig {
  val pc = AddrW
  val instEpoch = EpochW
  val prediction = new Prediction
  val exception = new Exception
  val privMode = PrivMode()
}

// Icache -> Decoder
class IDecodePacket extends Bundle with CPUConfig {
  val inst = InstW
  val instMetadata = new IFetchPacket
}

// Decoder -> ROB (ROB 占位)
class ROBInitControlPacket extends Bundle with CPUConfig {
  val pc = AddrW
  val prediction = new Prediction
  val exception = new Exception
  val isSpecialInstr = SpecialInstr()
}

// Decoder -> RS (分派信息)
class MicroOp extends Bundle {
  val op1Src = Src1Sel()
  val op2Src = Src2Sel()
  val aluOp = ALUOp()
  val lsuOp = LSUOp()
  val lsuWidth = LSUWidth()
  val lsuSign = LSUsign()
  val bruOp = BRUOp()
  val zicsrOp = ZicsrOp()
}

class DispatchPacket extends Bundle with CPUConfig {
  val robId = RobTag
  val microOp = new MicroOp
  val pc = AddrW
  val imm = DataW
  val prediction = new Prediction
  val exception = new Exception
}

// Decoder -> RAT (请求重命名)
class RenameReq extends Bundle with CPUConfig {
  val rs1 = ArchTag
  val rs2 = ArchTag
  val rd = ArchTag
  val isBranch = Bool() // 告诉 RAT 是否需要分配 Snapshot
}

// RAT -> ROB (ROB 占位：数据部分)
class ROBinitDataPacket extends Bundle with CPUConfig {
  val archRd = ArchTag
  val phyRd = PhyTag
  val phyOld = PhyTag
  val branchMask = SnapshotMask
}

// RAT -> Dispatch (重命名结果)
class RenameResPacket extends Bundle with CPUConfig {
  val phyRs1 = PhyTag // 源寄存器1 物理号
  val rs1Busy = Bool() // 源寄存器1 是否 Busy (RS 判断是否需要监听 CDB)
  val phyRs2 = PhyTag
  val rs2Busy = Bool()
  val phyRd = PhyTag // 目标寄存器 物理号
  val snapshotId = SnapshotId // 分配的快照 ID (分支专用)
  val branchMask = SnapshotMask // 当前依赖的分支掩码
}

// ============================================================================
// 3. 分派与重命名 (Dispatch & Rename Interfaces)
// ============================================================================

// 这里体现了乱序执行的核心数据流：RAT 和 ROB 填充信息后，打包发给 Dispatch 单元 再写入 RS。

// Dispatch 接口 (组合来自 Decoder, RAT 的信息)
class DispatchIO extends Bundle with CPUConfig {
  val Decoder = new DispatchPacket
  val RAT = new RenameResPacket
}

// Dispatch -> RS
// 应当在 RS 内部定义如下数据包，但是为了解析复杂数据流放在此处。
class DataReq extends Bundle with CPUConfig {
  val src1Sel = Src1Sel()
  val src1Tag = PhyTag
  val src1Busy = Bool()
  val src2Sel = Src2Sel()
  val src2Tag = PhyTag
  val src2Busy = Bool()
  val imm = DataW
  val pc = AddrW
}

class AluRSEntry extends Bundle with CPUConfig {
  val aluOp = ALUOp()
  val data = new DataReq
  val robId = RobTag
  val phyRd = PhyTag
  val snapshotMask = SnapshotMask
  val exception = new Exception
}

class BruRSEntry extends Bundle with CPUConfig {
  val bruOp = BRUOp()
  val data = new DataReq
  val robId = RobTag
  val phyRd = PhyTag
  val snapshotId = SnapshotId
  val snapshotMask = SnapshotMask
  val prediction = new Prediction
  val exception = new Exception
}

// ============================================================================
// 4. 后端执行 (Backend: Issue, PRF, EU)
// ============================================================================

// 采用了 **Explicit Register Renaming** 风格（发射后读 PRF）。

// RS -> PRF
class PrfReadPacket extends Bundle with CPUConfig {
  val raddr1 = PhyTag
  val raddr2 = PhyTag
}

// PRF -> EU
class PrfReadData extends Bundle with CPUConfig {
  val rdata1 = DataW
  val rdata2 = DataW
}

// RS -> EU
class IssueMetaPacket extends Bundle with CPUConfig {
  val robId = RobTag
  val phyRd = PhyTag
  val exception = new Exception
}

class IssueDataPacket extends Bundle with CPUConfig {
  val src1Sel = Src1Sel()
  val src2Sel = Src2Sel()
  val imm = DataW
  val pc = AddrW
}

// RS -> ALU
class AluReq extends Bundle with CPUConfig {
  val aluOp = ALUOp()
  val meta = new IssueMetaPacket
  val data = new IssueDataPacket
}

// RS -> BRU
class BruReq extends Bundle with CPUConfig {
  val bruOp = BRUOp()
  val prediction = new Prediction
  val meta = new IssueMetaPacket
  val data = new IssueDataPacket
}

// Issue Stage -> EU (组合数据包)
// 包含：控制信号 + 操作数(来自PRF/Imm/PC) + Tag
class AluPacket extends Bundle with CPUConfig {
  val aluReq = new AluReq
  val prfData = new PrfReadData
}

class BruPacket extends Bundle with CPUConfig {
  val bruReq = new BruReq
  val prfData = new PrfReadData
}

// 与 LSU 相关部分尚未定义 (TODO)

// ============================================================================
// 5. 写回与广播 (Writeback & CDB)
// ============================================================================

// CDB (Common Data Bus) 是所有 EU 产出结果汇聚的地方。

// EU -> CDB -> ROB
class CDBMessage extends Bundle with CPUConfig {
  val robId = RobTag // 用于 ROB 标记完成
  val phyRd = PhyTag // 用于 RS 唤醒依赖指令 & PRF 写入
  val data = DataW // 写入 PRF 的数据
  val exception = new Exception // 执行阶段产生的异常
}
