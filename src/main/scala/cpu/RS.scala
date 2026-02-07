package cpu

import chisel3._
import chisel3.util._

class Dispatcher extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 Decoder 的分派信息
    val decoder = Flipped(Decoupled(new DispatchPacket))
    // 来自 RAT 的重命名结果
    val rat = Flipped(Decoupled(new RenameRes))
    // 来自 ROB 的全局冲刷信号
    val globalFlush = Input(Bool())
    // 来自 BRU 的分支冲刷信号
    val branchFlush = Input(Bool())

    // 向 ALU 保留站发送 ALU 指令
    val aluRS = Decoupled(new AluRSDispatch)
    // 向 BRU 保留站发送分支指令
    val bruRS = Decoupled(new BruRSDispatch)
    // 向 LSU 发送 Load/Store 指令
    val lsu = Decoupled(new LSUDispatch)
    // 向 ZicsrU 发送 CSR 指令
    val zicsr = Decoupled(new ZicsrDispatch)
  })

  // 数据包整合
  val dataReq = Wire(new DataReq)
  dataReq.src1Sel := io.decoder.bits.microOp.op1Src
  dataReq.src1Tag := io.rat.bits.phyRs1
  dataReq.src1Ready := io.rat.bits.rs1Ready
  dataReq.src2Sel := io.decoder.bits.microOp.op2Src
  dataReq.src2Tag := io.rat.bits.phyRs2
  dataReq.src2Ready := io.rat.bits.rs2Ready
  dataReq.imm := io.decoder.bits.imm
  dataReq.pc := io.decoder.bits.pc

  // 指令类型判断
  val isALU = io.decoder.bits.microOp.aluOp =/= ALUOp.NOP
  val isBRU = io.decoder.bits.microOp.bruOp =/= BRUOp.NOP
  val isLSU = io.decoder.bits.microOp.lsuOp =/= LSUOp.NOP
  val isZicsr = io.decoder.bits.microOp.zicsrOp =/= ZicsrOp.NOP

  // 握手控制
  val needFlush = io.globalFlush || io.branchFlush
  val aluReady = io.aluRS.ready || !isALU
  val bruReady = io.bruRS.ready || !isBRU
  val lsuReady = io.lsu.ready || !isLSU
  val zicsrReady = io.zicsr.ready || !isZicsr
  val downStreamReady = aluReady && bruReady && lsuReady && zicsrReady
  val allReady = downStreamReady && !needFlush

  io.decoder.ready := allReady
  io.rat.ready := allReady

  // 分派逻辑
  // ALU 指令分派
  io.aluRS.valid := io.decoder.valid && isALU && !needFlush
  io.aluRS.bits.aluOp := io.decoder.bits.microOp.aluOp
  io.aluRS.bits.data := dataReq
  io.aluRS.bits.robId := io.decoder.bits.robId
  io.aluRS.bits.phyRd := io.rat.bits.phyRd
  io.aluRS.bits.branchMask := io.rat.bits.branchMask
  io.aluRS.bits.exception := io.decoder.bits.exception

  // BRU 指令分派
  io.bruRS.valid := io.decoder.valid && isBRU && !needFlush
  io.bruRS.bits.bruOp := io.decoder.bits.microOp.bruOp
  io.bruRS.bits.data := dataReq
  io.bruRS.bits.robId := io.decoder.bits.robId
  io.bruRS.bits.phyRd := io.rat.bits.phyRd
  io.bruRS.bits.snapshotOH := io.rat.bits.snapshotOH
  io.bruRS.bits.branchMask := io.rat.bits.branchMask
  io.bruRS.bits.prediction := io.decoder.bits.prediction
  io.bruRS.bits.exception := io.decoder.bits.exception

  // LSU 指令分派
  io.lsu.valid := io.decoder.valid && isLSU && !needFlush
  io.lsu.bits.opcode := io.decoder.bits.microOp.lsuOp
  io.lsu.bits.memWidth := io.decoder.bits.microOp.lsuWidth
  io.lsu.bits.memSign := io.decoder.bits.microOp.lsuSign
  io.lsu.bits.data := dataReq
  io.lsu.bits.phyRd := io.rat.bits.phyRd
  io.lsu.bits.robId := io.decoder.bits.robId
  io.lsu.bits.branchMask := io.rat.bits.branchMask
  io.lsu.bits.privMode := io.decoder.bits.privMode

  // Zicsr 指令分派
  io.zicsr.valid := io.decoder.valid && isZicsr && !needFlush
  io.zicsr.bits.zicsrOp := io.decoder.bits.microOp.zicsrOp
  io.zicsr.bits.data := dataReq
  io.zicsr.bits.csrAddr := io.decoder.bits.csrAddr
  io.zicsr.bits.robId := io.decoder.bits.robId
  io.zicsr.bits.phyRd := io.rat.bits.phyRd
  io.zicsr.bits.branchMask := io.rat.bits.branchMask
  io.zicsr.bits.privMode := io.decoder.bits.privMode
  io.zicsr.bits.exception := io.decoder.bits.exception
}

class AluRS extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 Dispatcher 的 ALU 指令入队请求
    val req = Flipped(Decoupled(new AluRSDispatch))
    // 来自 CDB 的结果广播
    val cdb = Flipped(Decoupled(new Bundle {
      val phyRd = PhyTag
    }))
    // 来自 ROB 的全局冲刷信号
    val globalFlush = Input(Bool())
    // 来自 BRU 的分支冲刷信号
    val branchFlush = Input(Bool())
    // 来自 BRU 的计算完成的分支掩码
    val branchOH = Input(SnapshotMask)

    // 向 PRF 发送源寄存器读取请求
    val prfRead = Decoupled(new PrfReadPacket)
    // 向 ALU 发送执行请求
    val aluReq = Decoupled(new AluReq)
  })

  // Entry 结构体定义
  class AluRSEntry extends Bundle with CPUConfig {
    val busy = Bool()
    val aluOp = ALUOp()
    val data = new DataReq
    val robId = RobTag
    val phyRd = PhyTag
    val branchMask = SnapshotMask
    val exception = new Exception
  }

  // Waiting Pool 定义
  val RS_SIZE = 8
  val rsEntries = RegInit(
    VecInit(Seq.fill(RS_SIZE)(0.U.asTypeOf(new AluRSEntry)))
  )

  // 统计空闲槽位
  val freeEntries = rsEntries.map(!_.busy)
  val hasFree = freeEntries.reduce(_ || _)
  val freeIdx = PriorityEncoder(freeEntries)
  val needFlush = io.globalFlush || io.branchFlush

  // 入队逻辑
  io.req.ready := hasFree && !needFlush

  when(io.req.fire) {
    val newEntry = Wire(new AluRSEntry)
    newEntry.busy := true.B
    newEntry.aluOp := io.req.bits.aluOp
    newEntry.data := io.req.bits.data
    newEntry.robId := io.req.bits.robId
    newEntry.phyRd := io.req.bits.phyRd
    newEntry.branchMask := io.req.bits.branchMask
    newEntry.exception := io.req.bits.exception
    rsEntries(freeIdx) := newEntry
  }

  // CDB 监听与唤醒
  // 遍历所有 RS 条目，更新操作数状态
  for (i <- 0 until RS_SIZE) {
    val entry = rsEntries(i)

    // 检查 src1
    when(io.cdb.valid && io.cdb.bits.phyRd === entry.data.src1Tag) {
      entry.data.src1Ready := true.B
    }

    // 检查 src2
    when(io.cdb.valid && io.cdb.bits.phyRd === entry.data.src2Tag) {
      entry.data.src2Ready := true.B
    }
  }

  // 指令就绪判断
  // 计算每个条目的就绪状态
  val readyEntries = VecInit(rsEntries.map { entry =>
    val src1Ready =
      (entry.data.src1Sel =/= Src1Sel.REG) || entry.data.src1Ready
    val src2Ready =
      (entry.data.src2Sel =/= Src2Sel.REG) || entry.data.src2Ready
    entry.busy && src1Ready && src2Ready
  })

  val hasReady = readyEntries.reduce(_ || _)
  val readyIdx = PriorityEncoder(readyEntries)
  val canIssue = hasReady && !needFlush

  // 发射逻辑
  val selectedEntry = rsEntries(readyIdx)

  // 向 PRF 发送读取请求
  io.prfRead.valid := canIssue
  io.prfRead.bits.raddr1 := selectedEntry.data.src1Tag
  io.prfRead.bits.raddr2 := selectedEntry.data.src2Tag

  // 向 ALU 发送执行请求
  io.aluReq.valid := canIssue
  io.aluReq.bits.aluOp := selectedEntry.aluOp
  io.aluReq.bits.meta.robId := selectedEntry.robId
  io.aluReq.bits.meta.phyRd := selectedEntry.phyRd
  io.aluReq.bits.meta.exception := selectedEntry.exception
  io.aluReq.bits.data.src1Sel := selectedEntry.data.src1Sel
  io.aluReq.bits.data.src2Sel := selectedEntry.data.src2Sel
  io.aluReq.bits.data.imm := selectedEntry.data.imm
  io.aluReq.bits.data.pc := selectedEntry.data.pc

  // 发射成功后，清除对应条目的 busy 位
  when(io.aluReq.fire) {
    rsEntries(readyIdx).busy := false.B
  }

  // 冲刷处理
  // 全局冲刷：清空所有指令
  when(io.globalFlush) {
    for (i <- 0 until RS_SIZE) {
      rsEntries(i).busy := false.B
    }
  }

  // 分支冲刷：根据 branchMask 清除依赖该分支的指令
  for (i <- 0 until RS_SIZE) {
    when(rsEntries(i).busy && (rsEntries(i).branchMask & io.branchOH) =/= 0.U) {
      rsEntries(i).branchMask := rsEntries(i).branchMask & ~io.branchOH
      when(io.branchFlush) {
        rsEntries(i).busy := false.B
      }
    }
  }

  io.cdb.ready := true.B
}

class BruRS extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 Dispatcher 的 BRU 指令入队请求
    val enq = Flipped(Decoupled(new BruRSDispatch))
    // 来自 CDB 的结果广播
    val cdb = Flipped(Decoupled(new Bundle {
      val phyRd = PhyTag
    }))
    // 来自 ROB 的全局冲刷信号
    val globalFlush = Input(Bool())
    // 来自 BRU 的分支冲刷信号
    val branchFlush = Input(Bool())
    // 来自 BRU 的计算完成的分支掩码
    val branchOH = Input(SnapshotMask)

    // 向 PRF 发送源寄存器读取请求
    val prfRead = Decoupled(new PrfReadPacket)
    // 向 BRU 发送执行请求
    val bruReq = Decoupled(new BruReq)
  })

  // Entry 结构体定义
  class BruRSEntry extends Bundle with CPUConfig {
    val busy = Bool()
    val bruOp = BRUOp()
    val data = new DataReq
    val robId = RobTag
    val phyRd = PhyTag
    val snapshotOH = SnapshotMask
    val branchMask = SnapshotMask
    val prediction = new Prediction
    val exception = new Exception
  }

  // Waiting Pool 定义
  val RS_SIZE = 4
  val rsEntries = RegInit(
    VecInit(Seq.fill(RS_SIZE)(0.U.asTypeOf(new BruRSEntry)))
  )

  // 统计空闲槽位
  val freeEntries = rsEntries.map(!_.busy)
  val hasFree = freeEntries.reduce(_ || _)
  val freeIdx = PriorityEncoder(freeEntries)
  val needFlush = io.globalFlush || io.branchFlush

  // 入队逻辑
  io.enq.ready := hasFree && !needFlush

  when(io.enq.fire) {
    val newEntry = Wire(new BruRSEntry)
    newEntry.busy := true.B
    newEntry.bruOp := io.enq.bits.bruOp
    newEntry.data := io.enq.bits.data
    newEntry.robId := io.enq.bits.robId
    newEntry.phyRd := io.enq.bits.phyRd
    newEntry.snapshotOH := io.enq.bits.snapshotOH
    newEntry.branchMask := io.enq.bits.branchMask
    newEntry.prediction := io.enq.bits.prediction
    newEntry.exception := io.enq.bits.exception
    rsEntries(freeIdx) := newEntry
  }

  // CDB 监听与唤醒
  // 遍历所有 RS 条目，更新操作数状态
  for (i <- 0 until RS_SIZE) {
    val entry = rsEntries(i)

    // 检查 src1
    when(io.cdb.valid && io.cdb.bits.phyRd === entry.data.src1Tag) {
      entry.data.src1Ready := true.B
    }

    // 检查 src2
    when(io.cdb.valid && io.cdb.bits.phyRd === entry.data.src2Tag) {
      entry.data.src2Ready := true.B
    }
  }

  // 指令就绪判断
  // 计算每个条目的就绪状态
  val readyEntries = VecInit(rsEntries.map { entry =>
    val src1Ready =
      (entry.data.src1Sel =/= Src1Sel.REG) || entry.data.src1Ready
    val src2Ready =
      (entry.data.src2Sel =/= Src2Sel.REG) || entry.data.src2Ready
    entry.busy && src1Ready && src2Ready
  })

  val hasReady = readyEntries.reduce(_ || _)
  val readyIdx = PriorityEncoder(readyEntries)
  val canIssue = hasReady && !needFlush

  // 发射逻辑
  val selectedEntry = rsEntries(readyIdx)

  // 向 PRF 发送读取请求
  io.prfRead.valid := canIssue
  io.prfRead.bits.raddr1 := selectedEntry.data.src1Tag
  io.prfRead.bits.raddr2 := selectedEntry.data.src2Tag

  // 向 BRU 发送执行请求
  io.bruReq.valid := canIssue
  io.bruReq.bits.bruOp := selectedEntry.bruOp
  io.bruReq.bits.prediction := selectedEntry.prediction
  io.bruReq.bits.meta.robId := selectedEntry.robId
  io.bruReq.bits.meta.phyRd := selectedEntry.phyRd
  io.bruReq.bits.meta.exception := selectedEntry.exception
  io.bruReq.bits.data.src1Sel := selectedEntry.data.src1Sel
  io.bruReq.bits.data.src2Sel := selectedEntry.data.src2Sel
  io.bruReq.bits.data.imm := selectedEntry.data.imm
  io.bruReq.bits.data.pc := selectedEntry.data.pc

  // 发射成功后，清除对应条目的 busy 位
  when(io.bruReq.fire) {
    rsEntries(readyIdx).busy := false.B
  }

  // 冲刷处理
  // 全局冲刷：清空所有指令
  when(io.globalFlush) {
    for (i <- 0 until RS_SIZE) {
      rsEntries(i).busy := false.B
    }
  }

  // 分支冲刷：根据 branchMask 清除依赖该分支的指令
  for (i <- 0 until RS_SIZE) {
    when(rsEntries(i).busy && (rsEntries(i).branchMask & io.branchOH) =/= 0.U) {
      rsEntries(i).branchMask := rsEntries(i).branchMask & ~io.branchOH
      when(io.branchFlush) {
        rsEntries(i).busy := false.B
      }
    }
  }

  io.cdb.ready := true.B
}
