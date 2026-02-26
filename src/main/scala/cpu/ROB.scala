package cpu

import chisel3._
import chisel3.util._

// ==================== ROB Entry 数据结构 ====================

class ROBEntry extends Bundle with CPUConfig {
  // 1. 状态位
  val busy        = Bool()       // 条目是否有效（已入队且未退休）
  val completed   = Bool()       // 指令是否执行完毕（来自 CDB）

  // 2. 指令控制数据（来自 Decoder）
  val pc          = AddrW        // 指令 PC（用于 mepc/sepc）
  val prediction  = new Prediction // 分支预测信息（taken, targetPC）
  val exception   = new Exception // 异常信息（valid, cause, tval）
  val specialInstr = SpecialInstr() // 特殊指令标记

  // 3. 指令资源管理（来自 RAT）
  val archRd      = ArchTag      // 架构目标寄存器
  val phyRd       = PhyTag       // 物理目标寄存器
  val preRd       = PhyTag       // 旧物理寄存器（Commit 时释放）
  val branchMask  = SnapshotMask // 分支掩码，代表依赖的分支（用于冲刷）

  // 4. 特殊标记
  val hasSideEffect = Bool()     // 是否有副作用（非幂等 Load 指令专用）
}

// ==================== 提交状态机 ====================

object CommitState extends ChiselEnum {
  val s_IDLE, s_WAIT = Value
}

// ==================== ROB 模块 ====================

class ROB extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 输入接口
    val controlInit = Flipped(Decoupled(new ROBInitControl))
    val dataInit = Flipped(Decoupled(new ROBinitData))
    val cdb = Flipped(Decoupled(new CDBMessage))
    val branchOH = Input(SnapshotMask)
    val branchFlush = Input(Bool())
    val branchRobId = Input(RobTag)  // 分支指令在 ROB 中的 ID，用于定位和更新预测信息
    val redirectPC = Input(UInt(32.W))
    val globalFlush = Input(Bool())

    // 输出接口
    val freeRobID = Output(RobTag)
    val csrPending = Output(Bool())
    val iEpoch = Output(EpochW)
    val dEpoch = Output(EpochW)
    val commitRAT = Decoupled(new CommitRAT)
    val storeEnable = Output(Bool())
    val csrEnable = Output(Bool())
    val exception = Output(new Exception)
    val isCSR = Output(Bool())
    val mret = Output(Bool())
    val sret = Output(Bool())
    val fenceI = Output(Bool())
    val sfenceVma = Output(new SFenceReq)
  })

  // ==================== 1. 状态定义 ====================
  
  // ROB 环形缓冲区
  val robQueue = Reg(Vec(RobSize, new ROBEntry))
  
  // 队列指针
  val robHead = RegInit(0.U(RobIdWidth.W))
  val robTail = RegInit(0.U(RobIdWidth.W))
  val robCount = RegInit(0.U(RobIdWidth.W))
  
  // 纪元寄存器
  val iEpoch = RegInit(0.U(EpochWidth.W))
  val dEpoch = RegInit(0.U(EpochWidth.W))
  
  // CSR 指令待处理标志
  val csrPending = RegInit(false.B)
  
  // 提交状态机
  val commitState = RegInit(CommitState.s_IDLE)
  
  // ==================== 2. 队列状态判断 ====================
  
  val full = (robHead === robTail) && robQueue(robHead).busy
  val empty = (robHead === robTail) && !robQueue(robHead).busy
  
  // ==================== 3. 入队逻辑 ====================
  
  // 入队条件
  val needFlush = io.globalFlush || io.branchFlush
  val canEnqueue = !full && !needFlush
  
  io.controlInit.ready := canEnqueue
  io.dataInit.ready := canEnqueue
  io.cdb.ready := !needFlush // 当需要冲刷时，CDB 的完成信号也被阻塞
  io.freeRobID := Mux(canEnqueue, robTail, 0.U) // 只有可以入队时才输出有效 ID
  
  // 当 Decoder 和 RAT 都准备好时，创建新 Entry
  when(io.controlInit.fire && io.dataInit.fire) {
    val entry = robQueue(robTail)
    
    // 填写指令控制数据
    entry.pc := io.controlInit.bits.pc
    entry.prediction := io.controlInit.bits.prediction
    entry.exception := io.controlInit.bits.exception
    entry.specialInstr := io.controlInit.bits.specialInstr
    
    // 填写资源管理数据
    entry.archRd := io.dataInit.bits.archRd
    entry.phyRd := io.dataInit.bits.phyRd
    entry.preRd := io.dataInit.bits.phyOld  // 注意：Protocol.scala 中是 phyOld
    entry.branchMask := io.dataInit.bits.branchMask
    
    // 初始化状态
    entry.busy := true.B
    entry.hasSideEffect := false.B
    
    // 如果入队时已有异常，标记为已完成
    entry.completed := io.controlInit.bits.exception.valid
    
    // 更新队尾指针
    robTail := robTail + 1.U
    
    // 更新 CSRPending 信号
    val isCSRInstr = 
      io.controlInit.bits.specialInstr === SpecialInstr.CSR || 
      io.controlInit.bits.specialInstr === SpecialInstr.MRET ||
      io.controlInit.bits.specialInstr === SpecialInstr.SRET ||
      io.controlInit.bits.specialInstr === SpecialInstr.FENCE ||
      io.controlInit.bits.specialInstr === SpecialInstr.FENCEI ||
      io.controlInit.bits.specialInstr === SpecialInstr.SFENCE
    when(isCSRInstr) {
      csrPending := true.B
    }
  }
  
  // ==================== 4. 指令完成逻辑 ====================
  when(io.cdb.fire) {
    val robId = io.cdb.bits.robId
    val entry = robQueue(robId)
    
    // 标记指令完成
    entry.completed := true.B
    
    // 更新异常信息
    when(io.cdb.bits.exception.valid) {
      entry.exception := io.cdb.bits.exception
    }
    
    // 更新副作用标记
    entry.hasSideEffect := io.cdb.bits.hasSideEffect
  }
  
  // ==================== 5. 提交逻辑 ====================
  
  // 默认输出值
  io.storeEnable := false.B
  io.csrEnable := false.B
  io.isCSR := false.B
  io.mret := false.B
  io.sret := false.B
  io.fenceI := false.B
  io.sfenceVma.valid := false.B
  io.sfenceVma.rs1 := 0.U
  io.sfenceVma.rs2 := 0.U
  io.commitRAT.valid := false.B
  io.commitRAT.bits.archRd := 0.U
  io.commitRAT.bits.phyRd := 0.U
  io.commitRAT.bits.preRd := 0.U
  io.exception.valid := false.B
  io.exception.cause := 0.U
  io.exception.tval := 0.U
  
  // 获取队头条目
  val headEntry = robQueue(robHead)
  
  // 状态机逻辑
  switch(commitState) {
    is(CommitState.s_IDLE) {
      // 检查队头
      when(!empty && headEntry.completed && !needFlush) {
        // 根据指令类型处理
        switch(headEntry.specialInstr) {
          // Store 指令
          is(SpecialInstr.STORE) {
            commitState := CommitState.s_WAIT
            io.storeEnable := true.B
          }
          // CSR 写指令
          is(SpecialInstr.CSR) {
            commitState := CommitState.s_WAIT
            io.csrEnable := true.B
          }
          // xRET 指令
          is(SpecialInstr.MRET) {
            io.mret := true.B
            retireHead()
          }
          is(SpecialInstr.SRET) {
            io.sret := true.B
            retireHead()
          }
          // FENCE.I 指令
          is(SpecialInstr.FENCEI) {
            commitState := CommitState.s_WAIT
            io.fenceI := true.B
          }
          // SFENCE.VMA 指令
          is(SpecialInstr.SFENCE) {
            commitState := CommitState.s_WAIT
            io.sfenceVma.valid := true.B
            io.sfenceVma.rs1 := 0.U  // 默认值，实际应从指令中提取
            io.sfenceVma.rs2 := 0.U  // 默认值，实际应从指令中提取
          }
          // WFI 指令（当作 NOP）
          is(SpecialInstr.WFI) {
            retireHead()
          }
          // 普通指令
          is(SpecialInstr.NONE) {
            retireHead()
          }
          // 其他指令类型（BRANCH, FENCE, ECALL, EBREAK）
          is(SpecialInstr.BRANCH) {
            retireHead()
          }
          is(SpecialInstr.FENCE) {
            retireHead()
          }
          is(SpecialInstr.ECALL) {
            retireHead()
          }
          is(SpecialInstr.EBREAK) {
            retireHead()
          }
        }
      }
    }
    
    is(CommitState.s_WAIT) {
      // 等待 CDB 确认信号
      when(io.cdb.fire && io.cdb.bits.robId === robHead) {
        // 根据指令类型处理
        switch(headEntry.specialInstr) {
          // CSR 指令需要触发全局冲刷
          is(SpecialInstr.CSR) {
            io.isCSR := true.B
          }
        }
        // 执行退休
        retireHead()
        commitState := CommitState.s_IDLE
      }
    }
  }
  
  // 退休函数
  def retireHead(): Unit = {
    // 通知 RAT 更新架构映射并释放 preRd
    io.commitRAT.valid := true.B
    io.commitRAT.bits.archRd := headEntry.archRd
    io.commitRAT.bits.phyRd := headEntry.phyRd
    io.commitRAT.bits.preRd := headEntry.preRd
    
    // 将队头置空
    headEntry.busy := false.B
    // 更新队头指针
    robHead := robHead + 1.U
    
    // 更新 CSRPending 信号
    val isCSRInstr = headEntry.specialInstr === SpecialInstr.CSR ||
                     headEntry.specialInstr === SpecialInstr.MRET ||
                     headEntry.specialInstr === SpecialInstr.SRET ||
                     headEntry.specialInstr === SpecialInstr.FENCE ||
                     headEntry.specialInstr === SpecialInstr.FENCEI ||
                     headEntry.specialInstr === SpecialInstr.SFENCE
    when(isCSRInstr) {
      csrPending := false.B
    }
    
    // 处理异常
    when(headEntry.exception.valid) {
      io.exception := headEntry.exception
    }
  }
  
  // ==================== 6. 分支冲刷逻辑 ====================
  
  when(io.branchFlush && !io.globalFlush) {
    // 清除所有依赖该分支的 Entry
    for(i <- 0 until RobSize) {
      val entry = robQueue(i)
      val shouldFlush = (entry.branchMask & io.branchOH).orR
      when(shouldFlush) {
        entry.busy := false.B
      }
    }
    
    // 更新指令纪元
    iEpoch := iEpoch + 1.U
    
    // 更新对应 Entry 的 prediction 字段（使用 branchRobId 定位）
    robQueue(io.branchRobId).prediction.taken := !robQueue(io.branchRobId).prediction.taken
  }.otherwise {
    // 预测正确，移除 branchMask
    for(i <- 0 until RobSize) {
      robQueue(i).branchMask := robQueue(i).branchMask & ~io.branchOH
    }
  }
  
  // ==================== 7. 全局冲刷逻辑 ====================
  
  when(io.globalFlush) {
    // 清空所有 ROB 条目
    for(i <- 0 until RobSize) {
      robQueue(i).busy := false.B
    }
    
    // 重置指针
    robHead := 0.U
    robTail := 0.U
    
    // 更新纪元
    iEpoch := iEpoch + 1.U
    dEpoch := dEpoch + 1.U
    
    // 重置 CSRPending
    csrPending := false.B
    
    // 重置状态机
    commitState := CommitState.s_IDLE
  }
  
  // ==================== 8. 输出信号 ====================
  
  io.csrPending := csrPending
  io.iEpoch := iEpoch
  io.dEpoch := dEpoch
}
