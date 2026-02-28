# CSRsUnit 设计文档

## 1. 接口定义

### 1.1 输入接口

| 信号名 | 来源 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| **CSR 指令访问** | | | |
| `csrReadReq` | ZICSRU | `Flipped(Decoupled(new CsrReadReq))` | CSR 读请求，包含 CSR 地址和当前特权级 |
| `csrWriteReq` | ZICSRU | `Flipped(Decoupled(new CsrWriteReq))` | CSR 写请求，包含 CSR 地址、写入数据和当前特权级 |
| **ROB 提交信号** | | | |
| `exception` | ROB | `Input(new Exception)` | 异常信息（valid, cause, tval），用于 Trap 处理 |
| `pc` | ROB | `Input(UInt(32.W))` | 异常指令的 PC（用于 mepc/sepc） |
| `isCSR` | ROB | `Input(Bool())` | 当前提交指令是否为 CSR 指令，用于判断是否触发全局冲刷 |
| `mret` | ROB | `Input(Bool())` | MRET 指令提交信号 |
| `sret` | ROB | `Input(Bool())` | SRET 指令提交信号 |

### 1.2 输出接口

| 信号名 | 目标 | 类型 | 描述 |
| :--- | :--- | :--- | :--- |
| **CSR 访问响应** | | | |
| `csrReadResp` | ZICSRU | `Decoupled(new CsrReadResp)` | CSR 读响应，包含读取的数据和异常信息（如非法指令） |
| `csrWriteResp` | ZICSRU | `Decoupled(new CsrWriteResp)` | CSR 写响应，包含写入结果的异常信息 |
| **特权级控制** | | | |
| `privMode` | Fetcher & Decoder | `Output(UInt(2.W))` | 当前特权级（U=0, S=1, M=3），用于指令取指与译码时的特权级检查 |
| **全局控制** | | | |
| `globalFlush` | ROB/Fetcher | `Output(Bool)` | 全局冲刷信号（valid），用于异常、中断处理和 CSR/mret/sret 指令提交时的流水线冲刷 |
| `globalFlushPC` | ROB/Fetcher | `Output(UInt(32.W))` | 全局冲刷信号（pc），用于异常、中断处理和 CSR/mret/sret 指令提交时的流水线冲刷 |
| **内存控制** | | | |
| `pmpConfig` | PMP/MMU | `Output(Vec(16, UInt(32.W)))` | PMP 配置寄存器（pmpcfg0-pmpcfg15），用于内存访问权限检查 |
| `pmpAddr` | PMP/MMU | `Output(Vec(64, UInt(32.W)))` | PMP 地址寄存器（pmpaddr0-pmpaddr63），用于内存访问权限检查 |

---

## 2. 物理资源声明

### 2.1 CSR 寄存器声明

#### 2.1.1 M-mode CSR 寄存器

```scala
// M-mode CSR 寄存器集合
class MModeCSRs extends Bundle with CPUConfig {
  val misa = UInt(32.W)         // 已实现 ISA 标志寄存器

  // 机器状态寄存器
  val mstatus = UInt(32.W)      // 机器状态寄存器
  val mie = UInt(32.W)          // 机器中断使能寄存器
  val mip = UInt(32.W)          // 机器中断挂起寄存器
  
  // 机器 Trap 处理寄存器
  val mepc = UInt(32.W)         // 机器异常程序计数器
  val mcause = UInt(32.W)       // 机器异常原因
  val mtval = UInt(32.W)       // 机器陷阱值
  val mtvec = UInt(32.W)       // 机器陷阱向量基地址
  val mscratch = UInt(32.W)     // 机器临时寄存器
  
  // 机器委托寄存器
  val medeleg = UInt(32.W)      // 机器异常委托寄存器
  val mideleg = UInt(32.W)      // 机器中断委托寄存器
  
  // 计数器
  val mcycle = UInt(64.W)       // 机器周期计数器
  val minstret = UInt(64.W)     // 机器指令计数器
}
```

#### 2.1.2 S-mode CSR 寄存器

```scala
// S-mode CSR 寄存器集合
class SModeCSRs extends Bundle with CPUConfig {
  // 监督者状态寄存器
  // val sstatus = UInt(32.W)      // 监督者状态寄存器（mstatus 子集）
  // val sie = UInt(32.W)          // 监督者中断使能寄存器（mie 子集）
  
  // 监督者 Trap 处理寄存器
  val sepc = UInt(32.W)         // 监督者异常程序计数器
  val scause = UInt(32.W)       // 监督者异常原因
  val stval = UInt(32.W)       // 监督者陷阱值
  val stvec = UInt(32.W)       // 监督者陷阱向量基地址
  val sscratch = UInt(32.W)     // 监督者临时寄存器
}
```

### 2.2 PMP 寄存器声明

#### 2.2.1 PMP 配置寄存器

```scala
class PMPCSRs extends Bundle with CPUConfig {
  // PMP 配置寄存器集合（pmpcfg0-pmpcfg15）

  // 每个 pmpcfg 寄存器包含 4 个 8 位的 PMP 配置
  // pmpcfg0: entries 0-3
  // pmpcfg1: entries 4-7
  // ...
  // pmpcfg15: entries 60-63

  // PMP 配置字段定义，每个配置项 8 位：
  // bits[7]   - L: Locked bit
  // bits[6:4] - A: Address matching mode (000=OFF, 001=TOR, 010=NA4, 011=NAPOT)
  // bits[3:2] - X: Execute permission
  // bits[1]   - W: Write permission
  // bits[0]   - R: Read permission
  val pmpcfg = Vec(16, UInt(32.W))
  
  // 每个 pmpaddr 寄存器 32 位
  // 表示物理地址[33:2]位，从而支持 34 位物理地址空间。
  val pmpaddr = Vec(64, UInt(32.W))
}
```

### 2.3 完整物理资源集合

```scala
class CSRCoreState extends Bundle with CPUConfig {
  val mMode = new MModeCSRs
  val sMode = new SModeCSRs
  val pmp = new PMPRegisters
  // 当前特权级
  val privMode = UInt(2.W)
}

val physCSRs = RegInit(0.U.asTypeOf(new CSRCoreState))
physCSRs.privMode := PrivMode.M.asUInt(2.W)
physCSRs.mMode.misa := 0x40001104.U // RV32I + M + S

// 全局冲刷信号寄存器
val globalFlush = RegInit(false.B)
val globalFlushPC = RegInit(0.U(32.W))
```

---

## 3. 读写处理

定义一系列读写访问规则，包含其物理目标（处理 CSR 重名）、字段约束（WPRI、WARL等）和动态访问检查来，而这些访问规则将被统一处理函数使用，以生成并行的读写逻辑。

### 3.1 注册表类型定义

```scala
// 字段类型
abstract class FieldType
case object RW extends FieldType
case object RO extends FieldType
case object WPRI extends FieldType
// WARL 允许自定义函数来使写入尝试合法化。
// 接收：(字段旧值, 尝试写入值) => 返回合法化值
case class WARL(legalize: (UInt, UInt) => UInt) extends FieldType 

// CSR 字段对应掩码生成器
case class CSRField(name: String, msb: Int, lsb: Int, fType: FieldType) {
  def mask: BigInt = ((BigInt(1) << (msb - lsb + 1)) - 1) << lsb
}

// 完整的 CSR 描述符
case class CSRDesc(
  addr: Int,
  name: String,
  physReg: UInt,             // 指向 CSRCoreState 中物理寄存器
  fields: Seq[CSRField],     // 位字段列表
)
```

### 3.2 完整的 CSR 注册表

该函数将物理资源绑定到 CSR 注册表并返回绑定结果，该表决定所有的路由、别名和验证逻辑。

```scala
def buildCSRRegistry(phys: CSRCoreState): Seq[CSRDesc] = {
  
  val standardCSRs = Seq(
    // ==========================================
    // M-Mode 状态与陷阱设置
    // ==========================================
    CSRDesc(0x300, "mstatus", phys.mMode.mstatus, Seq(
      CSRField("SD", 31, 31, RO),
      CSRField("TSR", 22, 22, RW),
      CSRField("TW", 21, 21, RW),
      CSRField("TVM", 20, 20, RW),
      CSRField("MXR", 19, 19, RW),
      CSRField("SUM", 18, 18, RW),
      CSRField("MPRV", 17, 17, RW),
      CSRField("XS", 16, 15, RO), // 假设没有自定义扩展
      CSRField("FS", 14, 13, RW),
      // MPP: 合法化 '10'（保留）-> 映射到 '00'（U 模式）或保持旧值
      CSRField("MPP", 12, 11, WARL((old, raw) => Mux(raw === 2.U, old, raw))),
      CSRField("SPP", 8, 8, RW),
      CSRField("MPIE", 7, 7, RW),
      CSRField("SPIE", 5, 5, RW),
      CSRField("MIE", 3, 3, RW),
      CSRField("SIE", 1, 1, RW)
    )),

    CSRDesc(0x304, "mie", phys.mMode.mie, Seq(
      CSRField("MEIE", 11, 11, RW), CSRField("SEIE", 9, 9, RW),
      CSRField("MTIE",  7,  7, RW), CSRField("STIE", 5, 5, RW),
      CSRField("MSIE",  3,  3, RW), CSRField("SSIE", 1, 1, RW)
    )),

    CSRDesc(0x344, "mip", phys.mMode.mip, Seq(
      // M 模式的外部/定时器/软件中断由硬件驱动（对软件来说是只读）
      CSRField("MEIP", 11, 11, RO), CSRField("SEIP", 9, 9, RW), // SEIP 可以被注入
      CSRField("MTIP",  7,  7, RO), CSRField("STIP", 5, 5, RW),
      CSRField("MSIP",  3,  3, RO), CSRField("SSIP", 1, 1, RW)
    )),

    CSRDesc(0x302, "medeleg", phys.mMode.medeleg, Seq(CSRField("val", 31, 0, RW))),
    CSRDesc(0x303, "mideleg", phys.mMode.mideleg, Seq(CSRField("val", 31, 0, RW))),
    CSRDesc(0x305, "mtvec", phys.mMode.mtvec, Seq(
      CSRField("BASE", 31, 2, RW), 
      CSRField("MODE", 1, 0, WARL((old, raw) => Mux(raw > 1.U, 0.U, raw)))
    )),
    
    // M-Mode 陷阱处理
    CSRDesc(0x340, "mscratch", phys.mMode.mscratch, Seq(CSRField("val", 31, 0, RW))),
    CSRDesc(0x341, "mepc", phys.mMode.mepc, Seq(CSRField("val", 31, 0, RW))),
    CSRDesc(0x342, "mcause", phys.mMode.mcause, Seq(
      CSRField("Interrupt", 31, 31, RW), CSRField("Code", 30, 0, RW)
    )),
    CSRDesc(0x343, "mtval", phys.mMode.mtval, Seq(CSRField("val", 31, 0, RW))),

    // ==========================================
    // S-Mode CSR（包括别名）
    // ==========================================
    // sstatus 是 phys.mMode.mstatus 的别名。
    // 它限制对特定字段的 RW 访问。其他位会被自然屏蔽。
    CSRDesc(0x100, "sstatus", phys.mMode.mstatus, Seq(
      CSRField("SD", 31, 31, RO),
      CSRField("MXR", 19, 19, RW), CSRField("SUM", 18, 18, RW),
      CSRField("FS", 14, 13, RW),  CSRField("SPP", 8, 8, RW),
      CSRField("SPIE", 5, 5, RW),  CSRField("SIE", 1, 1, RW)
    )),

    // sie 是 phys.mMode.mie 的别名。
    // 写入操作是动态调制的：只能写入 mideleg == 1 的位。
    CSRDesc(0x104, "sie", phys.mMode.mie, Seq(
      CSRField("sie_mod", 31, 0, WARL((oldVal, rawVal) => {
        val delegMask = phys.mMode.mideleg
        (oldVal & ~delegMask) | (rawVal & delegMask)
      }))
    )),

    // sip 是 phys.mMode.mip 的别名
    CSRDesc(0x144, "sip", phys.mMode.mip, Seq(
      CSRField("sip_mod", 31, 0, WARL((oldVal, rawVal) => {
        val delegMask = phys.mMode.mideleg
        (oldVal & ~delegMask) | (rawVal & delegMask)
      }))
    )),

    CSRDesc(0x180, "satp", phys.sMode.satp, Seq(
      CSRField("MODE", 31, 31, WARL((old, raw) => Mux(raw === 1.U, 1.U, 0.U))), // Bare 或 Sv32
      CSRField("ASID", 30, 22, RW),
      CSRField("PPN", 21, 0, RW)
    ),
    ),

    CSRDesc(0x105, "stvec", phys.sMode.stvec, Seq(
      CSRField("BASE", 31, 2, RW), CSRField("MODE", 1, 0, WARL((old, raw) => Mux(raw > 1.U, 0.U, raw)))
    )),
    CSRDesc(0x140, "sscratch", phys.sMode.sscratch, Seq(CSRField("val", 31, 0, RW))),
    CSRDesc(0x141, "sepc", phys.sMode.sepc, Seq(CSRField("val", 31, 0, RW))),
    CSRDesc(0x142, "scause", phys.sMode.scause, Seq(CSRField("Interrupt", 31, 31, RW), CSRField("Code", 30, 0, RW))),
    CSRDesc(0x143, "stval", phys.sMode.stval, Seq(CSRField("val", 31, 0, RW))),

    // 硬件计数器（通过 CSR 指令只读）
    CSRDesc(0xB00, "mcycle", phys.mMode.mcycle(31,0), Seq(CSRField("val", 31, 0, RO))),
    CSRDesc(0xB02, "minstret", phys.mMode.minstret(31,0), Seq(CSRField("val", 31, 0, RO)))
  )

  // ==========================================
  // PMP 配置与地址寄存器
  // ==========================================
  // pmpcfg0-15
  val pmpConfigs = (0 until 16).map { i =>
    CSRDesc(0x3A0 + i, s"pmpcfg$i", phys.pmp.pmpcfg(i), Seq(
      // 为每个寄存器创建 4 个不同的 8 位条目字段。
      // WARL 合法化器确保：如果锁定位（字节的第 7 位）为 1，则保持旧字节值。
      CSRField(s"cfg3", 31, 24, WARL((old, raw) => Mux(old(7), old, raw))),
      CSRField(s"cfg2", 23, 16, WARL((old, raw) => Mux(old(7), old, raw))),
      CSRField(s"cfg1", 15,  8, WARL((old, raw) => Mux(old(7), old, raw))),
      CSRField(s"cfg0",  7,  0, WARL((old, raw) => Mux(old(7), old, raw)))
    ))
  }

  // pmpaddr0-63
  val pmpAddrs = (0 until 64).map { i =>
    CSRDesc(0x3B0 + i, s"pmpaddr$i", phys.pmp.pmpaddr(i), Seq(
      // 合法化器逻辑查看对应的 pmpcfg 锁定位。
      CSRField(s"addr", 31, 0, WARL((old, raw) => {
        val cfgRegIdx = i / 4
        val cfgByteOffset = (i % 4) * 8
        val lockBit = phys.pmp.pmpcfg(cfgRegIdx)(cfgByteOffset + 7)
        Mux(lockBit, old, raw) // 如果被锁定则静默忽略写入
      }))
    ))
  }

  standardCSRs ++ pmpConfigs ++ pmpAddrs
}
```

### 3.3 读写逻辑生成器

该函数动态生成并行查找和写入掩码逻辑。它将"提交逻辑"（CSR 指令尝试）与物理状态分离。

```scala
def generateCSRRWLogic(
  cmdAddr: UInt,
  wdata: UInt,
  isWrite: Bool,
  phys: CSRCoreState,
  registry: Seq[CSRDesc]
): (UInt, Exception, CSRCoreState) = {
  
  val rdata = WireDefault(0.U(32.W))
  val exception = WireDefault(0.U.asTypeOf(new Exception))
  val nextPhysRegs = WireDefault(phys)
 
  // 计算结果列表：每个 CSR 描述符对应一个结果元组，包含匹配，读取，写入结果
  val evalResults = registry.map { desc =>
    val isMatch = (cmdAddr === desc.addr.U)
    
    // 1. 读取逻辑：计算掩码（处理 alias 与 WPRI 字段）
    val readMaskBigInt = desc.fields.foldLeft(BigInt(0)) { (acc, f) => 
      f.fType match {
        case RW | RO | WARL(_) => acc | f.mask // 添加到掩码中
        case WPRI              => acc          // 永远返回 0
      }
    }
    val readMask = readMaskBigInt.U(32.W)
    val readData = desc.physReg & readMask
    
    // 2. 写入逻辑：分字段应用掩码，在 WARL 上使用合法化函数。
    val nextData = desc.fields.foldLeft(desc.physReg) { (accWire, field) =>
      field.fType match {
        case RW =>
          val fMask = field.mask.U(32.W)
          (accWire & ~fMask) | (wdata & fMask)
        case WARL(legalizeFunc) =>
          val oldSlice = desc.physReg(field.msb, field.lsb)
          val rawSlice = wdata(field.msb, field.lsb)
          val legalSlice = legalizeFunc(oldSlice, rawSlice)
          val fMask = field.mask.U(32.W)
          (accWire & ~fMask) | (legalSlice << field.lsb)
        case _ => 
          accWire // RO 和 WPRI 字段忽略显式写入
      }
    }
    
    (isMatch, readData, nextData, desc)
  }

  // 检查是否有地址匹配
  val addressMatched  = evalResults.map(_._1).reduce(_ || _)
  // 若无匹配则报错
  exception.valid := !addressMatched
  exception.cause := ExceptionCause.IllegalInstruction
  // TODO: CSRsUnit need the pc for exception reporting.
  exception.tval := cmdAddr
  
  // 计算读取结果：从所有结果中选择匹配的那一个
  // 若有多个则选择首个匹配成功的结果；若无则返回全 0
  rdata := Mux1H(evalResults.map(x => x._1 -> x._3))

  // 将写入合并到下一周期物理寄存器状态包中
  when (isWrite && addressMatched) {
    evalResults.foreach { case (isMatch, _, _, nextData, desc) =>
      when (isMatch) {
        // 反射式映射回 nextPhysRegs 中的特定字段
        desc.name match {
          case "mstatus" => nextPhysRegs.mMode.mstatus := nextData
          case "sstatus" => nextPhysRegs.mMode.mstatus := nextData
          case "mie"     => nextPhysRegs.mMode.mie := nextData
          case "sie"     => nextPhysRegs.mMode.mie := nextData
          case "satp"    => nextPhysRegs.sMode.satp := nextData
          // TODO: 剩余的 CSR 寄存器。
          case name if name.startsWith("pmpcfg") => 
            val idx = name.replace("pmpcfg", "").toInt
            nextPhysRegs.pmp.pmpcfg(idx) := nextData
          case name if name.startsWith("pmpaddr") => 
            val idx = name.replace("pmpaddr", "").toInt
            nextPhysRegs.pmp.pmpaddr(idx) := nextData
        }
      }
    }
  }

  (rdata, exception, nextPhysRegs)
}
```

## 4. Trap & Return 处理

当与控制流切换有关指令从 ROB 提交到 CSRsUnit 时，该模块依照如下函数定义电路进行下一周期 CSR 值计算。

### 4.1 辅助函数

处理 mstatus 与 sstatus 更新的辅助函数，以避免 alias 带来问题。
> 其余涉及别名的 CSR 在控制流切换时无需更新

```scala
// Save MIE -> MPIE, Save Priv -> MPP, Disable MIE, Set Priv -> M
def updateMstatusOnTrapEntry(
  currentMstatus: UInt, 
  prevPriv: UInt,
): UInt = {
  // 提取修改 Fields
  val mpie = currentMstatus(3) // Old MIE becomes MPIE
  val mpp  = prevPriv          // Old Priv Mode
  val mie  = false.B           // Interrupts disabled
  
  // 构造下一周期值
  val mask = "h_FFFF_E777".U(32.W)
  (currentMstatus & mask) | (mpp << 11) | (mpie.asUInt << 7) | (mie.asUInt << 3)
}

// Save SIE -> SPIE, Save Priv -> SPP, Disable SIE, Set Priv -> S
def updateSstatusOnTrapEntry(
  currentMstatus: UInt,
  prevPriv: UInt
): UInt = {
  val spie = currentMstatus(1) // Old SIE becomes SPIE
  val spp  = prevPriv(0)       // Old Priv (0=U, 1=S)
  val sie  = false.B           // Disable interrupts
  
  val mask = "h_FFFF_FEDD".U(32.W)
  (currentMstatus & mask) | (spp << 8) | (spie.asUInt << 5) | (sie.asUInt << 1)
}

// Restore MPIE -> MIE, Set MPIE -> 1, Set Priv -> MPP, Set MPP -> U
def updateMstatusOnMret(currentMstatus: UInt): (UInt, UInt) = {
  val mpp  = currentMstatus(12, 11) // Restore Privilege
  val mie  = currentMstatus(7)      // Restore MIE from MPIE
  val mpie = true.B                 // Set MPIE to 1 (Standard)
  val newMpp = 0.U(2.W)             // Set MPP to U
  
  val mask = "h_FFFF_E777".U(32.W)
  val nextMstatus = (currentMstatus & mask) | (newMpp << 11) | (mpie.asUInt << 7) | (mie.asUInt << 3)
 
  (nextMstatus, mpp)
}

def updateSstatusOnSret(currentMstatus: UInt): (UInt, UInt) = {
  val spp  = currentMstatus(8)      // Restore Privilege (0=U, 1=S)
  val sie  = currentMstatus(5)      // Restore SIE from SPIE
  val spie = true.B                 // Set SPIE to 1
  val newSpp = 0.U(1.W)             // Set SPP to U
  
  val mask = "h_FFFF_FEDD".U(32.W)
  val nextMstatus = (currentMstatus & mask) | (newSpp << 8) | (spie.asUInt << 5) | (sie.asUInt << 1)
  
  // Return TargetPrivilege as 2 bits (00 or 01)
  (nextMstatus, Cat(0.U(1.W), spp)) 
}
```

### 4.2 异常与中断处理

此部分函数计算 Trap Handle 时 CSR 下一周期值以及跳转到的 PC 位置。

```scala
def handleTrap(
  pc: UInt,
  cause: UInt,
  tval: UInt,
  isInterrupt: Bool,
  currentPriv: UInt,
  phys: CSRCoreState
): (CSRCoreState, UInt) = {
  
  val nextPhys = WireDefault(phys) // 克隆当前状态
  val targetPC = Wire(UInt(32.W))

  val causeCode = cause(30, 0)
  
  // 计算是否代理到 S-mode 处理 Trap
  // Delegate to S-mode if: (Current != M) AND (Delegation Bit is Set)
  val delegateToS = (currentPriv =/= PrivMode.M.asUInt) && 
    Mux(isInterrupt, phys.mMode.mideleg(causeCode), phys.mMode.medeleg(causeCode))

  when (delegateToS) {
    // === S-Mode Trap Entry ===
    // Update mstatus using S-mode rules
    nextPhys.mMode.mstatus := updateSstatusOnTrapEntry(phys.mMode.mstatus, currentPriv)
    
    // Update S-mode Trap Registers
    nextPhys.sMode.sepc    := pc
    nextPhys.sMode.scause  := Cat(isInterrupt, causeCode)
    nextPhys.sMode.stval   := tval
    nextPhys.privMode      := PrivMode.S.asUInt
    
    // Calculate Vector Jump
    val base = Cat(phys.sMode.stvec(31, 2), 0.U(2.W))
    val isVectored = phys.sMode.stvec(0)
    targetPC := Mux(isInterrupt && isVectored, base + (causeCode << 2), base)

  } .otherwise {
    // === M-Mode Trap Entry ===
    // Update mstatus (Root) using M-mode rules
    nextPhys.mMode.mstatus := updateMstatusOnTrapEntry(phys.mMode.mstatus, currentPriv, isInterrupt)
    
    // Update M-mode Trap Registers
    nextPhys.mMode.mepc    := pc
    nextPhys.mMode.mcause  := Cat(isInterrupt, causeCode)
    nextPhys.mMode.mtval   := tval
    nextPhys.privMode      := PrivMode.M.asUInt
    
    // Calculate Vector Jump
    val base = Cat(phys.mMode.mtvec(31, 2), 0.U(2.W))
    val isVectored = phys.mMode.mtvec(0)
    targetPC := Mux(isInterrupt && isVectored, base + (causeCode << 2), base)
  }
  
  (nextPhys, targetPC)
  // what about the global flush?
}
```

### 4.3 xRET 指令处理

```scala
class InsType extends ChiselEnum {
  val MRET, SRET = Value
}
def handleReturn(
  instType: InsType,
  phys: CSRCoreState
): (CSRCoreState, UInt) = {
  
  val nextPhys = WireDefault(phys)
  val targetPC = WireDefault(0.U(32.W))

  switch (instType) {
    is (Instype.MRET) {
      val (newMstatus, targetPriv) = updateMstatusOnMret(phys.mMode.mstatus)
      nextPhys.mMode.mstatus := newMstatus
      nextPhys.privMode      := targetPriv
      targetPC               := phys.mMode.mepc
    }
    is (Instype.SRET) {
      val (newMstatus, targetPriv) = updateSstatusOnSret(phys.mMode.mstatus)
      nextPhys.mMode.mstatus := newMstatus
      nextPhys.privMode      := targetPriv
      targetPC               := phys.sMode.sepc
    }
  }
  
  (nextPhys, targetPC)
}
```

---

## 5. CSR 更新

将 Section 3 与 Section 4 的结果进行优先级排序和合并，生成最终的 CSR 更新逻辑。

### 5.1 来源定义与判定

```scala
object UpdateSource extends ChiselEnum {
  val None, CsrRW, Trap, Return = Value
}

// 异常条件判断
val isException = io.trap.exception.valid
// 中断条件判断：全局中断使能 + 挂起且使能的中断存在
val mstatusMIE = regs.mMode.mstatus(3)
val pendingInterrupts = regs.mMode.mie & regs.mMode.mip
val hasInterrupt = (pendingInterrupts =/= 0.U) && mstatusMIE
val isInterrupt = !isException && hasInterrupt
// 返回指令条件判断
val isReturn    = io.isMret || io.isSret
// Zicsr 指令条件判断
val isCsrRW     = io.isCsr

val updateSource = MuxCase(UpdateSource.None, Seq(
  (isException || isInterrupt) -> UpdateSource.Trap,
  isReturn                     -> UpdateSource.Return,
  isCsrRW                      -> UpdateSource.CsrRW
))
```

### 5.2 结果合并逻辑

```scala
val currentState = WireDefault(physCSRs) // 当前物理寄存器状态

// 1. 计算 CSR Read/Write 结果
val (rdata, RWException, CsrRWResult) = generateCSRRWLogic(
  io.csrCmd.addr, 
  io.csrCmd.wdata, 
  io.csrCmd.isWrite, 
  currentState, 
  registry,
)

// 2. 计算 Trap Handling 结果
val (CsrTrapResult, globalTrapPC) = handleTrap(
  io.trap.pc,
  io.trap.exception.cause,
  io.trap.exception.tval, 
  isInterrupt, 
  physCSRs.privMode, 
  currentState,
)

// 3. 计算 xRet 指令结果
val retType = Mux(io.isMret, InstType.MRET, InstType.SRET)
val (CsrRetState, globalRetPC) = handleReturn(
  retType, 
  currentState,
)

// 4. Mux 确定结果
val nextPhysCSRs = Wire(new CSRCoreState)
val flushFlag = Wire(Bool())
val flushPC = Wire(UInt(32.W))

switch (updateSource) {
  is (UpdateSource.Trap) {
    nextPhysCSRs := CsrTrapResult
    flushFlag := true.B
    flushPC := globalTrapPC
  }
  is (UpdateSource.Return) {
    nextPhysCSRs := CsrRetResult
    flushFlag := true.B
    flushPC := globalRetPC
  }
  is (UpdateSource.CsrRW) {
    // If the CSR Instruction itself raised an Exception, we do NOT commit the write and the ZicsrU will see io.csrResp.exception.
    nextPhysCSRs := Mux(RWException.valid, currentState, CsrRWResult)
    flushFlag := false.B 
    flushPC := 0.U
  }
  is (UpdateSource.None) {
    nextPhysCSRs  := currentState // 不变
    flushPipeline := false.B
    flushTargetPC := 0.U
  }
}

// 5. 更新
physCSRs := nextPhysCSRs
```

## 6. 输出

输出结果，包括 CSR 访问响应、特权级控制、全局控制信号以及内存控制信号。

### 6.1 CSR 访问响应

```scala
// CSR 读响应
io.csrReadResp.valid := io.csrReadReq.valid
io.csrReadResp.bits.rdata := rdata
io.csrReadResp.bits.exception := RWException

// CSR 写响应
io.csrWriteResp.valid := io.csrWriteReq.valid
io.csrWriteResp.bits.exception := RWException
```

### 6.2 特权级控制

```scala
// 输出当前特权级
io.privMode := physCSRs.privMode
```

### 6.3 全局控制

```scala
// 输出全局冲刷信号给 ROB/Fetcher
io.globalFlush := globalFlush
io.globalFlushPC := globalFlushPC
```

### 6.4 内存控制

```scala
// 输出 PMP 配置寄存器给 PMP/MMU
io.pmpConfig := physCSRs.pmp.pmpcfg

// 输出 PMP 地址寄存器给 PMP/MMU
io.pmpAddr := physCSRs.pmp.pmpaddr
```