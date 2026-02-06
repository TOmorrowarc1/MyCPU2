## 0. 基础配置与枚举定义 (Configuration & Types)

```scala
import chisel3._
import chisel3.util._

// 架构参数配置特质
trait CPUConfig {
  val XLEN = 32
  val AddrWidth = 32
  val InstWidth = 32
  val DataWidth = 32
  val CsrAddrWidth = 12
  
  // 规模参数 (建议参数化，这里先给定具体位宽)
  val RobIdWidth = 5       // 支持 32 条指令乱序
  val PhyRegIdWidth = 7    // 128 个物理寄存器
  val ArchRegIdWidth = 5   // 32 个架构寄存器
  val SnapshotIdWidth = 2  // 4 个 Snapshots
  val EpochWidth = 2       // 4 个 Epochs
  
  def InstW = UInt(InstWidth.W)
  def AddrW = UInt(AddrWidth.W)
  def DataW = UInt(DataWidth.W)
  def CsrAddrW = UInt(CsrAddrWidth.W)
  def RobTag = UInt(RobIdWidth.W)
  def PhyTag = UInt(PhyRegIdWidth.W)
  def PhyRegMask = UInt((1 << PhyRegIdWidth).W)
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
  val INSTRUCTION_ACCESS_FAULT       = 1.U(4.W)
  val ILLEGAL_INSTRUCTION           = 2.U(4.W)
  val BREAKPOINT                    = 3.U(4.W)
  val LOAD_ADDRESS_MISALIGNED       = 4.U(4.W)
  val LOAD_ACCESS_FAULT             = 5.U(4.W)
  val STORE_ADDRESS_MISALIGNED      = 6.U(4.W)
  val STORE_ACCESS_FAULT            = 7.U(4.W)
  val ECALL_FROM_U_MODE             = 8.U(4.W)
  val ECALL_FROM_S_MODE             = 9.U(4.W)
  val ECALL_FROM_M_MODE             = 11.U(4.W)
  val INSTRUCTION_PAGE_FAULT        = 12.U(4.W)
  val LOAD_PAGE_FAULT               = 13.U(4.W)
  val STORE_PAGE_FAULT              = 15.U(4.W)
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
  val BRANCH, STORE, FENCE, FENCEI, CSR, MRET, SRET, SFENCE, ECALL, EBREAK, WFI, NONE = Value
}
```

## 1. 通用元数据

在流水线的多个阶段透传的数据。

```scala
class Exception extends Bundle {
  val valid = Bool()
  val cause = UInt(4.W)
  val tval  = UInt(32.W)
}

class Prediction extends Bundle {
  val taken = Bool()
  val targetPC = UInt(32.W)
}
```

## 2. 前端接口 (Frontend Interfaces)

```scala
// Fetcher -> Icache
class IFetchPacket extends Bundle with CPUConfig {
  val pc          = AddrW
  val insEpoch    = EpochW
  val prediction  = new Prediction
  val exception   = new Exception       
  val privMode    = PrivMode()          
}

// Icache -> Decoder
class IDecodePacket extends Bundle with CPUConfig {
  val inst      = InstW
  val instMetadata = new IFetchPacket
}  

// Decoder -> ROB (ROB 占位)
class ROBInitControlPacket extends Bundle with CPUConfig {
  val pc         = AddrW
  val prediction = new Prediction
  val exception  = new Exception
  val specialInstr = SpecialInstr()
}

// Decoder -> RS (分派信息)
class MicroOp extends Bundle {
  val op1Src    = Src1Sel()
  val op2Src    = Src2Sel()
  val aluOp     = ALUOp()
  val lsuOp     = LSUOp()
  val lsuWidth  = LSUWidth()
  val lsuSign   = LSUsign()
  val bruOp     = BRUOp()
  val zicsrOp   = ZicsrOp()
}

class DispatchPacket extends Bundle with CPUConfig {
  val robId     = RobTag
  val microOp   = new MicroOp
  val pc        = AddrW
  val imm       = DataW
  val csrAddr   = CsrAddrW
  val privMode  = PrivMode()
  val prediction = new Prediction
  val exception  = new Exception
}

// Decoder -> RAT (请求重命名)
class RenameReq extends Bundle with CPUConfig {
  val rs1 = ArchTag
  val rs2 = ArchTag
  val rd  = ArchTag
  val isBranch = Bool() // 告诉 RAT 是否需要分配 Snapshot
}

// RAT -> ROB (ROB 占位：数据部分)
class ROBinitData extends Bundle with CPUConfig {
  val archRd    = ArchTag
  val phyRd     = PhyTag
  val phyOld    = PhyTag
  val branchMask = SnapshotMask
}

// RAT -> Dispatch (重命名结果)
class RenameRes extends Bundle with CPUConfig {
  val phyRs1      = PhyTag // 源寄存器1 物理号
  val rs1Ready    = Bool() // 源寄存器1 数据是否 ready (用于 CDB 旁路)
  val phyRs2      = PhyTag
  val rs2Ready    = Bool() // 源寄存器2 数据是否 ready (用于 CDB 旁路)
  val phyRd       = PhyTag // 目标寄存器 物理号
  val snapshotOH  = SnapshotMask // 分配的快照 ID (分支专用)
  val branchMask  = SnapshotMask            // 当前依赖的分支掩码
}
```

## 3. 分派 (Dispatch)

分派阶段与后端乱序执行模块之间的信息包定义如下：

```scala

// Dispatch -> RS
class DataReq extends Bundle with CPUConfig {
  val src1Sel    = Src1Sel()
  val src1Tag    = PhyTag
  val src1Busy   = Bool()
  val src2Sel    = Src2Sel() 
  val src2Tag    = PhyTag
  val src2Busy   = Bool()
  val imm        = DataW
  val pc         = AddrW
}

// Dispatch -> ALU RS
class AluRSDispatch extends Bundle with CPUConfig {
  val aluOp       = ALUOp()
  val data        = new DataReq
  val robId       = RobTag
  val phyRd       = PhyTag
  val branchMask  = SnapshotMask
  val exception   = new Exception
}

// Dispatch -> BRU RS
class BruRSDispatch extends Bundle with CPUConfig {
  val bruOp       = BRUOp()
  val data        = new DataReq
  val robId       = RobTag
  val phyRd       = PhyTag
  val snapshotOH  = SnapshotMask
  val branchMask  = SnapshotMask
  val prediction  = new Prediction
  val exception   = new Exception
}
```

## 4. 后端执行 (Backend: Issue, PRF, EU)

注意：发射后读 PRF。

```scala
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
  val robId    = RobTag
  val phyRd    = PhyTag
  val exception = new Exception
}

class IssueDataPacket extends Bundle with CPUConfig {
  val src1Sel = Src1Sel()
  val src2Sel = Src2Sel()
  val imm     = DataW
  val pc      = AddrW
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
class AluDrivenPacket extends Bundle with CPUConfig {
  val aluReq = new AluReq
  val prfData = new PrfReadData
}

class BruDrivenPacket extends Bundle with CPUConfig {
  val bruReq = new BruReq
  val prfData = new PrfReadData
}

```

## 5. 写回与广播 (Writeback & CDB)

CDB (Common Data Bus) 是所有 EU 产出结果汇聚的地方。

```scala
// EU -> CDB -> ROB
class CDBMessage extends Bundle with CPUConfig {
  val robId    = RobTag   // 用于 ROB 标记完成
  val phyRd    = PhyTag   // 用于 RS 唤醒依赖指令 & PRF 写入
  val data     = DataW    // 写入 PRF 的数据
  val hasSideEffect = Bits(1.W) // 是否有副作用（非幂等 Load 指令专用）
  val exception = new Exception // 执行阶段产生的异常
}
```

## 6. 内存系统接口 (Memory System Interfaces)

### 6.1 AXI4 总线接口

```scala
// AXI 事务 ID (区分 I/D Cache)
object AxiId extends ChiselEnum {
  val I_CACHE = Value(0.U)  // 指令 Cache
  val D_CACHE = Value(1.U)  // 数据 Cache
}

// AXI 元数据 (上下文)
class AXIContext extends Bundle {
  val epoch = UInt(2.W)  // 用于回传上下文信息
}

// 宽总线 AXI4 接口 (512-bit 数据总线)
class WideAXI4Bundle extends Bundle {
  // --- 读路径 ---
  val ar = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id   = AxiId()        // 事务 ID (区分 I/D Cache)
    val len  = UInt(8.W)      // Burst 长度，宽总线通常为 0
    val user = new AXIContext // 携带元数据
  })

  val r = Flipped(Decoupled(new Bundle {
    val data = UInt(512.W)    // 512-bit 宽数据
    val id   = AxiId()
    val last = Bool()         // Burst 结束标志，宽总线返回结果当周期拉高
    val user = new AXIContext // 回传元数据
  }))

  // --- 写路径 ---
  val aw = Decoupled(new Bundle {
    val addr = UInt(32.W)
    val id   = AxiId()
    val len  = UInt(8.W)
    val user = new AXIContext
  })

  val w = Decoupled(new Bundle {
    val data = UInt(512.W)
    val strb = UInt(64.W)     // 字节掩码 (64 bytes -> 64 bits)
    val last = Bool()
  })

  val b = Flipped(Decoupled(new Bundle {
    val id   = AxiId()
    val user = new AXIContext
  }))
}
```

### 6.2 内存上下文与取指接口

```scala
// 内存操作上下文 (包含元数据)
class MemContext extends Bundle with CPUConfig {
  val epoch      = EpochW           // 纪元标记 (用于逻辑撤销)
  val branchMask = SnapshotMask     // 分支掩码 (用于分支冲刷)
  val robId      = RobTag           // ROB 条目 ID
  val privMode   = PrivMode()      // 特权级模式
}

// 取指请求 (Fetcher -> I-Cache)
class FetchReq extends Bundle with CPUConfig {
  val pc        = AddrW            // 取指地址
  val ctx       = new MemContext   // 上下文信息
}

// 取指响应 (I-Cache -> Fetcher)
class FetchResp extends Bundle with CPUConfig {
  val data      = UInt(512.W)      // 512-bit 指令块
  val ctx       = new MemContext   // 回传的上下文
  val exception = new Exception    // 访问异常
}
```

### 6.3 MMU 接口 (Memory Management Unit)

```scala
// MMU 请求 (LSU -> MMU)
class MMUReq extends Bundle with CPUConfig {
  val va       = UInt(32.W)        // 待翻译的虚拟地址
  val memOp    = LSUOp()           // 访问类型 (Load / Store)
  val memWidth = LSUWidth()        // 访问位宽 (B / H / W)
  val ctx      = new MemContext    // 上下文信息
}

// MMU 响应 (MMU -> LSU)
class MMUResp extends Bundle with CPUConfig {
  val pa        = UInt(34.W)       // 翻译后的物理地址 (Sv32 输出为 34 位)
  val exception = new Exception    // 翻译异常 (Page Fault / Access Fault)
  val ctx       = new MemContext   // 原样带回的上下文
}

// SFENCE.VMA 请求 (ROB -> MMU)
class SFenceReq extends Bundle {
  val rs1 = UInt(5.W)  // rs1 寄存器号 (用于选择性刷新)
  val rs2 = UInt(5.W)  // rs2 寄存器号 (用于 ASID 匹配)
  val valid = Bool()   // 请求有效信号
}
```

### 6.4 Cache 接口

```scala
// Cache 请求 (PTW/LSU/Fetcher -> Cache)
class CacheReq extends Bundle with CPUConfig {
  val addr     = UInt(32.W)        // 物理地址
  val isWrite  = Bool()           // 写操作标志
  val data     = UInt(512.W)      // 写数据 (仅写操作有效)
  val strb     = UInt(64.W)       // 字节掩码 (仅写操作有效)
  val ctx      = new MemContext   // 上下文信息
}

// Cache 响应 (Cache -> PTW/LSU/Fetcher)
class CacheResp extends Bundle with CPUConfig {
  val data      = UInt(512.W)     // 读数据
  val ctx       = new MemContext  // 回传的上下文
  val exception = new Exception    // 访问异常
}

// Cache 写回请求 (Cache -> 主存)
class CacheWriteback extends Bundle with CPUConfig {
  val addr = UInt(32.W)           // 脏行地址
  val data = UInt(512.W)          // 脏行数据
  val ctx  = new MemContext       // 上下文信息
}
```

### 6.5 AGU 接口 (Address Generation Unit)

```scala
// AGU 请求 (RS -> AGU)
class AGUReq extends Bundle with CPUConfig {
  val baseAddr = DataW            // 基地址
  val offset   = DataW            // 偏移量 (立即数)
  val robId    = RobTag           // ROB 条目 ID
}

// AGU 响应 (AGU -> LSU)
class AGUResp extends Bundle with CPUConfig {
  val va    = UInt(32.W)          // 计算出的虚拟地址
  val robId = RobTag              // ROB 条目 ID
}
```

### 6.6 PMA 检查器接口 (Physical Memory Attributes Checker)

```scala
// PMA 检查请求
class PMACheckReq extends Bundle {
  val pa = UInt(34.W)             // 物理地址
}

// PMA 检查响应
class PMACheckResp extends Bundle {
  val isIO = Bool()               // 是否为 I/O 区域 (非幂等，不可乱序，不可缓存)
}
```

### 6.7 LSU 接口 (Load Store Unit)

```scala
// LSU 分派请求 (Dispatch -> LSU)
class LSUDispatch extends Bundle with CPUConfig {
  val opcode    = LSUOp()         // Load / Store
  val memWidth  = LSUWidth()      // 访问位宽
  val memSign   = LSUsign()       // 符号扩展标志
  val rd        = ArchTag         // 目标寄存器 (Load 指令)
  val robId     = RobTag          // ROB 条目 ID
  val branchMask = SnapshotMask   // 分支掩码
  val epoch     = EpochW          // 纪元标记
  val privMode  = PrivMode()      // 特权级
}

// LSU Cache 请求 (LSU -> Cache)
class LSUCacheReq extends Bundle with CPUConfig {
  val addr    = UInt(32.W)        // 物理地址
  val isWrite = Bool()            // 写操作标志
  val data    = UInt(512.W)       // 写数据
  val strb    = UInt(64.W)        // 字节掩码
  val ctx     = new MemContext    // 上下文信息
}

// LSU Cache 响应 (Cache -> LSU)
class LSUCacheResp extends Bundle with CPUConfig {
  val data      = UInt(512.W)     // 读数据
  val ctx       = new MemContext  // 回传的上下文
  val exception = new Exception   // 访问异常
}

// LSU ROB 退休请求 (ROB -> LSU)
class LSUCommit extends Bundle with CPUConfig {
  val robId = RobTag              // 退休的 ROB 条目 ID
}

// LSU CDB 消息 (LSU -> CDB)
class LSUCDBMessage extends Bundle with CPUConfig {
  val robId    = RobTag           // ROB 条目 ID
  val phyRd    = PhyTag           // 目标物理寄存器 (Load 指令)
  val data     = DataW            // Load 结果数据
  val isIO     = Bool()           // 是否为 IO Load
  val exception = new Exception   // 执行异常
}
```

### 6.8 AXI 仲裁器接口 (AXI Arbiter)

```scala
// AXI 仲裁器输入 (来自 I-Cache 和 D-Cache)
class AXIArbiterIn extends Bundle {
  val iCacheReq  = Flipped(Decoupled(new Bundle {
    val ar = new WideAXI4Bundle.ar.bits  // I-Cache 读请求
  }))
  val dCacheReqR = Flipped(Decoupled(new Bundle {
    val ar = new WideAXI4Bundle.ar.bits  // D-Cache 读请求
  }))
  val dCacheReqW = Flipped(Decoupled(new Bundle {
    val aw = new WideAXI4Bundle.aw.bits  // D-Cache 写地址请求
    val w  = new WideAXI4Bundle.w.bits   // D-Cache 写数据请求
  }))
  val mainMemResp = Flipped(new WideAXI4Bundle)  // 主存响应
}

// AXI 仲裁器输出 (发往 I-Cache、D-Cache 和主存)
class AXIArbiterOut extends Bundle {
  val iCacheResp = Decoupled(new Bundle {
    val r = new WideAXI4Bundle.r.bits  // I-Cache 读响应
  })
  val dCacheResp = Decoupled(new Bundle {
    val r = new WideAXI4Bundle.r.bits  // D-Cache 读响应
    val b = new WideAXI4Bundle.b.bits  // D-Cache 写响应
  })
  val mainMemReq = new WideAXI4Bundle   // 发往主存的统一 AXI 请求
}
```

### 6.9 内存系统顶层接口

```scala
// 内存系统顶层接口 (MemorySystem 对外接口)
class MemorySystemIO extends Bundle with CPUConfig {
  // 1. 指令取指接口
  val if_req  = Flipped(Decoupled(new FetchReq))
  val if_resp = Decoupled(new FetchResp)

  // 2. LSU 接口
  val lsu_dispatch = Flipped(Decoupled(new LSUDispatch))
  val lsu_agu_req  = Flipped(Decoupled(new AGUReq))
  val lsu_agu_resp = Decoupled(new AGUResp)
  val lsu_mmu_req  = Flipped(Decoupled(new MMUReq))
  val lsu_mmu_resp = Decoupled(new MMUResp)
  val lsu_commit   = Input(new LSUCommit)
  val lsu_cdb      = Output(new LSUCDBMessage)

  // 3. 全局控制
  val sfence = Input(new SFenceReq)
  val csr    = Input(new Bundle {
    val satp = UInt(32.W)   // 包含 Mode (Sv32) 与 PPN (根页表基址)
    val pmp  = UInt(128.W)  // PMP 配置 (简化版)
  })
  val flush = Input(new Bundle {
    val global     = Bool()        // 全局冲刷信号
    val branchKill = UInt(4.W)     // 分支冲刷掩码
  })
}
```