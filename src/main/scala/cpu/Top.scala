package cpu

import chisel3._
import chisel3.util._

/**
 * Top - RV32I-Privileged Tomasulo 架构顶层模块
 * 
 * 整合所有子模块，实现完整的乱序执行处理器
 * 
 * 架构组成：
 * - Frontend: Fetcher, Decoder, RAT
 * - Backend: RS (Dispatcher, ALURS, BRURS), ALU, BRU, ZICSRU, LSU, CDB
 * - State: PRF, ROB, CSRsUnit
 * - Memory: MainMemory, AXIArbiter, Cache (I-Cache, D-Cache), AGU, PMPChecker
 */
class Top extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 外部中断输入（可选）
    val extInterrupt = Input(Bool())
  })

  // ============================================================================
  // 1. Frontend 模块实例化
  // ============================================================================

  // Fetcher: 取指模块
  val fetcher = Module(new Fetcher)

  // Decoder: 译码模块
  val decoder = Module(new Decoder)

  // RAT: 寄存器别名表
  val rat = Module(new RAT)

  // ============================================================================
  // 2. Backend 模块实例化
  // ============================================================================

  // RS (Reservation Stations): 保留站
  val dispatcher = Module(new Dispatcher)
  val aluRS = Module(new AluRS)
  val bruRS = Module(new BruRS)

  // ALU: 算术逻辑单元
  val alu = Module(new ALU)

  // BRU: 分支解析单元
  val bru = Module(new BRU)

  // ZICSRU: CSR 指令单元
  val zicsru = Module(new ZicsrU)

  // CDB: 公共数据总线
  val cdb = Module(new CDB)

  // ============================================================================
  // 3. 状态维护模块实例化
  // ============================================================================

  // PRF: 物理寄存器文件（3个实例，分别用于ALU、BRU、LSU）
  val prfALU = Module(new PRF)
  val prfBRU = Module(new PRF)
  val prfLSU = Module(new PRF)

  // ROB: 重排序缓冲区
  val rob = Module(new ROB)

  // CSRsUnit: CSR 单元
  val csrsUnit = Module(new CSRsUnit)

  // ============================================================================
  // 4. 内存系统模块实例化
  // ============================================================================

  // MainMemory: 主存
  val mainMemory = Module(new MainMemory)

  // AXIArbiter: AXI 仲裁器
  val axiArbiter = Module(new AXIArbiter)

  // Cache: 缓存（包含 I-Cache 和 D-Cache）
  val cache = Module(new Cache)

  // AGU: 地址生成单元
  val agu = Module(new AGU)

  // LSU: 加载存储单元
  val lsu = Module(new LSU)

  // ============================================================================
  // 5. Frontend 连接
  // ============================================================================

  // Fetcher 输入
  fetcher.io.insEpoch := rob.io.iEpoch
  fetcher.io.globalFlush := csrsUnit.io.globalFlush
  fetcher.io.globalFlushPC := csrsUnit.io.globalFlushPC
  fetcher.io.branchFlush := bru.io.branchFlush
  fetcher.io.branchFlushPC := bru.io.branchPC
  fetcher.io.ifStall := decoder.io.ifStall
  fetcher.io.privMode := csrsUnit.io.privMode

  // Fetcher -> Cache (I-Cache)
  cache.io.i_req.valid := fetcher.io.icache.valid
  cache.io.i_req.bits.pc := fetcher.io.icache.bits.pc
  cache.io.i_req.bits.ctx.epoch := fetcher.io.icache.bits.instEpoch
  cache.io.i_req.bits.ctx.branchMask := 0.U  // I-Cache 不使用 branchMask
  cache.io.i_req.bits.ctx.robId := 0.U  // I-Cache 不使用 robId
  fetcher.io.icache.ready := !cache.io.fence_i  // FENCE.I 时暂停取指

  // Cache -> Decoder
  decoder.io.in.valid := cache.io.i_resp.valid
  decoder.io.in.bits.inst := cache.io.i_resp.bits.data
  decoder.io.in.bits.instMetadata.pc := fetcher.io.icache.bits.pc  // 使用 Fetcher 的 PC
  decoder.io.in.bits.instMetadata.instEpoch := cache.io.i_resp.bits.ctx.epoch
  decoder.io.in.bits.instMetadata.prediction.taken := false.B
  decoder.io.in.bits.instMetadata.prediction.targetPC := 0.U
  decoder.io.in.bits.instMetadata.exception := cache.io.i_resp.bits.exception
  decoder.io.in.bits.instMetadata.privMode := fetcher.io.privMode

  // Decoder 输入
  decoder.io.freeRobID := rob.io.freeRobID
  decoder.io.globalFlush := csrsUnit.io.globalFlush
  decoder.io.csrPending := rob.io.csrPending
  decoder.io.branchFlush := bru.io.branchFlush

  // Decoder -> RAT
  rat.io.renameReq <> decoder.io.renameReq

  // Decoder -> ROB
  rob.io.controlInit <> decoder.io.robInit

  // ============================================================================
  // 6. Backend 连接
  // ============================================================================

  // Dispatcher 输入
  dispatcher.io.decoder <> decoder.io.dispatch
  dispatcher.io.rat <> rat.io.renameRes
  dispatcher.io.globalFlush := csrsUnit.io.globalFlush
  dispatcher.io.branchFlush := bru.io.branchFlush

  // Dispatcher -> ALU RS
  aluRS.io.req <> dispatcher.io.aluRS

  // Dispatcher -> BRU RS
  bruRS.io.enq <> dispatcher.io.bruRS

  // Dispatcher -> LSU
  val lsuDispatch = Wire(Valid(new LSUDispatch))
  lsuDispatch.valid := dispatcher.io.lsu.valid
  lsuDispatch.bits.opcode := dispatcher.io.lsu.bits.opcode
  lsuDispatch.bits.memWidth := dispatcher.io.lsu.bits.memWidth
  lsuDispatch.bits.memSign := dispatcher.io.lsu.bits.memSign
  lsuDispatch.bits.data := dispatcher.io.lsu.bits.data
  lsuDispatch.bits.phyRd := dispatcher.io.lsu.bits.phyRd
  lsuDispatch.bits.robId := dispatcher.io.lsu.bits.robId
  lsuDispatch.bits.branchMask := dispatcher.io.lsu.bits.branchMask
  lsuDispatch.bits.privMode := dispatcher.io.lsu.bits.privMode

  // Dispatcher -> ZicsrU
  zicsru.io.zicsrReq <> dispatcher.io.zicsr

  // ALU RS -> PRF
  prfALU.io.readReq <> aluRS.io.prfRead

  // ALU RS -> ALU
  val aluDrivenPacket = Wire(new AluDrivenPacket)
  aluDrivenPacket.aluReq <> aluRS.io.aluReq
  aluDrivenPacket.prfData <> prfALU.io.readResp
  alu.io.in <> aluDrivenPacket

  // ALU -> CDB
  cdb.io.alu <> alu.io.out

  // BRU RS -> PRF
  prfBRU.io.readReq <> bruRS.io.prfRead

  // BRU RS -> BRU
  val bruDrivenPacket = Wire(new BruDrivenPacket)
  bruDrivenPacket.bruReq <> bruRS.io.bruReq
  bruDrivenPacket.prfData <> prfBRU.io.readResp
  bru.io.in <> bruDrivenPacket

  // BRU -> CDB
  cdb.io.bru <> bru.io.out

  // BRU -> Fetcher, RAT, ROB, RS
  fetcher.io.branchFlush := bru.io.branchFlush
  fetcher.io.branchFlushPC := bru.io.branchPC
  rat.io.branchFlush := bru.io.branchFlush
  rat.io.snapshotId := bru.io.branchOH
  rob.io.branchFlush := bru.io.branchFlush
  rob.io.branchOH := bru.io.branchOH
  rob.io.branchRobId := bruDrivenPacket.bruReq.meta.robId
  rob.io.redirectPC := bru.io.branchPC
  dispatcher.io.branchFlush := bru.io.branchFlush
  aluRS.io.branchFlush := bru.io.branchFlush
  aluRS.io.branchOH := bru.io.branchOH
  bruRS.io.branchFlush := bru.io.branchFlush
  bruRS.io.branchOH := bru.io.branchOH

  // ZicsrU -> PRF
  prfLSU.io.readReq <> zicsru.io.prfReq
  zicsru.io.prfData <> prfLSU.io.readResp

  // ZicsrU -> CSRsUnit
  zicsru.io.csrReadReq <> csrsUnit.io.csrReadReq
  zicsru.io.csrReadResp := csrsUnit.io.csrReadResp
  zicsru.io.csrWriteReq <> csrsUnit.io.csrWriteReq
  zicsru.io.csrWriteResp := csrsUnit.io.csrWriteResp

  // ZicsrU -> CDB
  cdb.io.zicsru <> zicsru.io.cdb

  // ZicsrU 冲刷信号
  zicsru.io.globalFlush := csrsUnit.io.globalFlush
  zicsru.io.branchFlush := bru.io.branchFlush
  zicsru.io.branchOH := bru.io.branchOH

  // ============================================================================
  // 7. CDB 连接
  // ============================================================================

  // CDB -> ROB
  rob.io.cdb <> cdb.io.boardcast

  // CDB -> RAT
  rat.io.cdb.valid := cdb.io.boardcast.valid
  rat.io.cdb.bits.phyRd := cdb.io.boardcast.bits.phyRd

  // CDB -> ALU RS
  aluRS.io.cdb.valid := cdb.io.boardcast.valid
  aluRS.io.cdb.bits.phyRd := cdb.io.boardcast.bits.phyRd

  // CDB -> BRU RS
  bruRS.io.cdb.valid := cdb.io.boardcast.valid
  bruRS.io.cdb.bits.phyRd := cdb.io.boardcast.bits.phyRd

  // CDB -> PRF (写回)
  prfALU.io.write.valid := cdb.io.boardcast.valid
  prfALU.io.write.bits.rd := cdb.io.boardcast.bits.phyRd
  prfALU.io.write.bits.data := cdb.io.boardcast.bits.data

  prfBRU.io.write.valid := cdb.io.boardcast.valid
  prfBRU.io.write.bits.rd := cdb.io.boardcast.bits.phyRd
  prfBRU.io.write.bits.data := cdb.io.boardcast.bits.data

  prfLSU.io.write.valid := cdb.io.boardcast.valid
  prfLSU.io.write.bits.rd := cdb.io.boardcast.bits.phyRd
  prfLSU.io.write.bits.data := cdb.io.boardcast.bits.data

  // ============================================================================
  // 8. ROB 连接
  // ============================================================================

  // ROB -> RAT
  rob.io.dataInit <> rat.io.robData
  rob.io.commitRAT <> rat.io.commit

  // ROB -> CSRsUnit
  csrsUnit.io.exception := rob.io.exception
  csrsUnit.io.pc := rob.io.exception.tval  // 使用 exception.tval 作为 PC（简化）
  csrsUnit.io.isCSR := rob.io.isCSR
  csrsUnit.io.mret := rob.io.mret
  csrsUnit.io.sret := rob.io.sret

  // ROB -> Fetcher
  fetcher.io.globalFlush := csrsUnit.io.globalFlush
  fetcher.io.globalFlushPC := csrsUnit.io.globalFlushPC

  // ROB -> RAT, RS, ALU, BRU, ZicsrU
  rat.io.globalFlush := csrsUnit.io.globalFlush
  dispatcher.io.globalFlush := csrsUnit.io.globalFlush
  aluRS.io.globalFlush := csrsUnit.io.globalFlush
  bruRS.io.globalFlush := csrsUnit.io.globalFlush
  zicsru.io.globalFlush := csrsUnit.io.globalFlush
  alu.io.globalFlush := csrsUnit.io.globalFlush
  bru.io.globalFlush := csrsUnit.io.globalFlush

  // ROB -> ZicsrU (提交信号)
  zicsru.io.commitReady := rob.io.csrEnable

  // ============================================================================
  // 9. LSU 连接
  // ============================================================================

  // LSU 输入：来自 RS 的分派
  lsu.io.dispatch := lsuDispatch

  // LSU 输入：来自 PRF 的源寄存器值
  // LSU 需要从 PRF 读取源寄存器值，但是 LSU 没有直接的 PRF 读取接口
  // 这里暂时使用 0，实际实现需要根据 LSU 的需求进行连接
  lsu.io.rs1_data := 0.U
  lsu.io.rs2_data := 0.U

  // LSU -> AGU 连接
  agu.io.req := lsu.io.agu_req
  agu.io.pmpcfg := csrsUnit.io.pmpConfig
  agu.io.pmpaddr := csrsUnit.io.pmpAddr
  lsu.io.agu_resp := agu.io.resp

  // LSU -> Cache (D-Cache) 连接
  cache.io.d_req.valid := lsu.io.d_req.valid
  cache.io.d_req.bits.addr := lsu.io.d_req.bits.addr
  cache.io.d_req.bits.isWrite := lsu.io.d_req.bits.isWrite
  cache.io.d_req.bits.data := lsu.io.d_req.bits.data
  cache.io.d_req.bits.strb := lsu.io.d_req.bits.strb
  cache.io.d_req.bits.ctx := lsu.io.d_req.bits.ctx
  lsu.io.d_resp.valid := cache.io.d_resp.valid
  lsu.io.d_resp.bits.data := cache.io.d_resp.bits.data
  lsu.io.d_resp.bits.ctx := cache.io.d_resp.bits.ctx
  lsu.io.d_resp.bits.exception := cache.io.d_resp.bits.exception

  // LSU -> CDB 连接
  cdb.io.lsu.valid := lsu.io.cdb.valid
  cdb.io.lsu.bits := lsu.io.cdb.bits

  // LSU 输入：来自 ROB 的提交
  // 注意：这里使用 freeRobID 是一个简化版本，实际实现需要获取当前正在提交的指令的 ROB ID
  lsu.io.commit.valid := rob.io.storeEnable
  lsu.io.commit.bits.robId := 0.U  // TODO: 需要从 ROB 获取当前正在提交的指令的 ROB ID
  lsu.io.commit.bits.isStore := true.B

  // LSU 输入：来自 ROB 的冲刷
  lsu.io.flush.global := csrsUnit.io.globalFlush
  lsu.io.flush.branchMask := bru.io.branchOH

  // LSU 输入：来自 CSRsUnit 的 PMP 配置
  lsu.io.pmpcfg := csrsUnit.io.pmpConfig
  // LSU 只需要前 16 个 PMP 地址寄存器
  for (i <- 0 until 16) {
    lsu.io.pmpaddr(i) := csrsUnit.io.pmpAddr(i)
  }

  // LSU 输入：CDB 监听
  lsu.io.cdb_in.valid := cdb.io.boardcast.valid
  lsu.io.cdb_in.bits.robId := cdb.io.boardcast.bits.robId
  lsu.io.cdb_in.bits.phyRd := cdb.io.boardcast.bits.phyRd
  lsu.io.cdb_in.bits.data := cdb.io.boardcast.bits.data
  lsu.io.cdb_in.bits.hasSideEffect := cdb.io.boardcast.bits.hasSideEffect
  lsu.io.cdb_in.bits.exception := cdb.io.boardcast.bits.exception

  // LSU 输出：到 ROB 的异常
  // rob.io.exception := lsu.io.exception  // ROB 的 exception 已经连接到其他地方

  // ============================================================================
  // 10. 内存系统连接
  // ============================================================================

  // Cache -> AXIArbiter
  axiArbiter.io.i_cache <> cache.io.axi

  // AXIArbiter -> MainMemory
  axiArbiter.io.memory <> mainMemory.io.axi

  // Cache FENCE.I 信号
  cache.io.fence_i := rob.io.fenceI

  // ============================================================================
  // 11. PRF 冲刷信号
  // ============================================================================

  alu.io.globalFlush := csrsUnit.io.globalFlush
  alu.io.branchFlush := bru.io.branchFlush
  alu.io.branchOH := bru.io.branchOH

  bru.io.globalFlush := csrsUnit.io.globalFlush

  // ============================================================================
  // 12. 调试输出
  // ============================================================================

  // 输出当前特权级
  printf(p"Current PrivMode: ${csrsUnit.io.privMode}\n")

  // 输出全局冲刷信号
  when(csrsUnit.io.globalFlush) {
    printf(p"Global Flush! PC = 0x${Hexadecimal(csrsUnit.io.globalFlushPC)}\n")
  }

  // 输出分支冲刷信号
  when(bru.io.branchFlush) {
    printf(p"Branch Flush! PC = 0x${Hexadecimal(bru.io.branchPC)}, OH = ${bru.io.branchOH}\n")
  }
}
