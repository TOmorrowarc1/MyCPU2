package cpu

import chisel3._
import chisel3.util._

/**
 * LSU (加载存储单元)
 * 
 * 管理 LQ/SQ（各 8 项），通过 AGU 计算地址，通过 PMPChecker 检查权限，
 * 实现 Store-Load 转发，将 Load 结果广播到 CDB。
 * 
 * 功能：
 * - Load Queue (LQ) 管理：跟踪在途的 Load 指令
 * - Store Queue (SQ) 管理：跟踪在途的 Store 指令
 * - Store-Load 转发：实现 Store 到 Load 的数据转发
 * - AGU 交互：通过 AGU 计算物理地址
 * - D-Cache 交互：通过 D-Cache 执行内存访问
 * - CDB 广播：将 Load 结果广播到 CDB
 * - 冲刷处理：处理 Global Flush 和 Branch Flush
 * - 提交处理：处理 Store 指令的提交
 */
class LSU extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 RS 的分派
    val dispatch = Input(Valid(new LSUDispatch))
    
    // 来自 PRF 的源寄存器值
    val rs1_data = Input(UInt(32.W))
    val rs2_data = Input(UInt(32.W))
    
    // AGU 接口
    val agu_req = Output(Valid(new AGUReq))
    val agu_resp = Input(Valid(new AGUResp))
    
    // D-Cache 接口
    val d_req = Output(Valid(new Bundle {
      val addr = UInt(32.W)
      val isWrite = Bool()
      val data = UInt(32.W)
      val strb = UInt(4.W)
      val ctx = new MemContext
    }))
    val d_resp = Input(Valid(new Bundle {
      val data = UInt(32.W)
      val ctx = new MemContext
      val exception = new Exception
    }))
    
    // 来自 ROB 的提交
    val commit = Input(Valid(new Bundle {
      val robId = RobTag
      val isStore = Bool()
    }))
    
    // 来自 ROB 的冲刷
    val flush = Input(new Bundle {
      val global = Bool()
      val branchMask = SnapshotMask
    })
    
    // 来自 CSRsUnit 的 PMP 配置
    val pmpcfg = Input(Vec(16, UInt(8.W)))
    val pmpaddr = Input(Vec(16, UInt(32.W)))
    
    // CDB 监听：更新操作数就绪状态
    val cdb_in = Input(Valid(new CDBMessage))
    
    // 到 CDB 的广播
    val cdb = Output(Valid(new CDBMessage))
    
    // 到 ROB 的异常
    val exception = Output(Valid(new Exception))
  })
  
  // ============================================================================
  // LQ 和 SQ 数据结构定义
  // ============================================================================
  
  // LQ 条目结构
  class LQEntry extends Bundle with CPUConfig {
    val valid = Bool()
    val memWidth = LSUWidth()
    val memSign = LSUsign()
    val phyRd = PhyTag
    val robId = RobTag
    val branchMask = SnapshotMask
    val epoch = EpochW
    val privMode = PrivMode()
    val src1Tag = PhyTag
    val src1Ready = Bool()
    val imm = DataW  // 保存立即数
    val PAReady = Bool()
    val PA = UInt(32.W)
    val exception = new Exception
    val storeMask = UInt(8.W)
    val isForwarding = Bool()
    val forwardingData = DataW
    val cacheReqSent = Bool()  // 是否已发送 Cache 请求
  }
  
  // SQ 条目结构
  class SQEntry extends Bundle with CPUConfig {
    val valid = Bool()
    val memWidth = LSUWidth()
    val robId = RobTag
    val branchMask = SnapshotMask
    val epoch = EpochW
    val privMode = PrivMode()
    val src1Tag = PhyTag
    val src1Ready = Bool()
    val src2Tag = PhyTag
    val src2Ready = Bool()
    val imm = DataW  // 保存立即数
    val PAReady = Bool()
    val PA = UInt(32.W)
    val storeData = DataW
    val exception = new Exception
    val committed = Bool()  // 是否已提交
  }
  
  // ============================================================================
  // LQ 和 SQ 队列
  // ============================================================================
  
  // LQ：8 项
  val lq = RegInit(VecInit(Seq.fill(8)(0.U.asTypeOf(new LQEntry))))
  
  // SQ：8 项
  val sq = RegInit(VecInit(Seq.fill(8)(0.U.asTypeOf(new SQEntry))))
  
  // ============================================================================
  // 辅助函数：地址重叠检测
  // ============================================================================
  
  def isAddressOverlap(addr1: UInt, addr2: UInt, width1: LSUWidth.Type, width2: LSUWidth.Type): Bool = {
    val size1 = Mux(width1 === LSUWidth.BYTE, 1.U, 
              Mux(width1 === LSUWidth.HALF, 2.U, 4.U))
    val size2 = Mux(width2 === LSUWidth.BYTE, 1.U, 
              Mux(width2 === LSUWidth.HALF, 2.U, 4.U))
    val end1 = addr1 + size1 - 1.U
    val end2 = addr2 + size2 - 1.U
    !(end1 < addr2 || end2 < addr1)
  }
  
  // ============================================================================
  // 辅助函数：符号扩展
  // ============================================================================
  
  def signExtend(data: UInt, sign: LSUsign.Type, width: LSUWidth.Type): UInt = {
    val result = MuxCase(data, Seq(
      (width === LSUWidth.BYTE) -> Mux(sign === LSUsign.SIGNED, 
        Cat(Fill(24, data(7)), data(7, 0)), 
        Cat(0.U(24.W), data(7, 0))),
      (width === LSUWidth.HALF) -> Mux(sign === LSUsign.SIGNED, 
        Cat(Fill(16, data(15)), data(15, 0)), 
        Cat(0.U(16.W), data(15, 0))),
      (width === LSUWidth.WORD) -> data
    ))
    result
  }
  
  // ============================================================================
  // 默认输出值
  // ============================================================================
  
  io.agu_req.valid := false.B
  io.agu_req.bits := 0.U.asTypeOf(new AGUReq)
  
  io.d_req.valid := false.B
  io.d_req.bits := DontCare
  
  io.cdb.valid := false.B
  io.cdb.bits := 0.U.asTypeOf(new CDBMessage)
  
  io.exception.valid := false.B
  io.exception.bits := 0.U.asTypeOf(new Exception)
  
  // ============================================================================
  // 1. 指令分派处理
  // ============================================================================
  
  when(io.dispatch.valid) {
    val opcode = io.dispatch.bits.opcode
    val memWidth = io.dispatch.bits.memWidth
    val memSign = io.dispatch.bits.memSign
    val data = io.dispatch.bits.data
    val phyRd = io.dispatch.bits.phyRd
    val robId = io.dispatch.bits.robId
    val branchMask = io.dispatch.bits.branchMask
    val privMode = io.dispatch.bits.privMode
    
    // 处理 Load 指令
    when(opcode === LSUOp.LOAD) {
      val lqValidVec = Cat(lq.map(_.valid).reverse)
      val emptyIdx = PriorityEncoder(~lqValidVec)
      when(emptyIdx =/= 8.U) {
        lq(emptyIdx).valid := true.B
        lq(emptyIdx).memWidth := memWidth
        lq(emptyIdx).memSign := memSign
        lq(emptyIdx).phyRd := phyRd
        lq(emptyIdx).robId := robId
        lq(emptyIdx).branchMask := branchMask
        lq(emptyIdx).epoch := 0.U  // TODO: 从 ROB 获取当前 epoch
        lq(emptyIdx).privMode := privMode
        lq(emptyIdx).src1Tag := data.src1Tag
        lq(emptyIdx).src1Ready := data.src1Ready
        lq(emptyIdx).imm := data.imm
        lq(emptyIdx).PAReady := false.B
        lq(emptyIdx).PA := 0.U
        lq(emptyIdx).exception := 0.U.asTypeOf(new Exception)
        lq(emptyIdx).storeMask := 0.U
        lq(emptyIdx).isForwarding := false.B
        lq(emptyIdx).forwardingData := 0.U
        lq(emptyIdx).cacheReqSent := false.B
      }
    }
    
    // 处理 Store 指令
    when(opcode === LSUOp.STORE) {
      val sqValidVec = Cat(sq.map(_.valid).reverse)
      val emptyIdx = PriorityEncoder(~sqValidVec)
      when(emptyIdx =/= 8.U) {
        sq(emptyIdx).valid := true.B
        sq(emptyIdx).memWidth := memWidth
        sq(emptyIdx).robId := robId
        sq(emptyIdx).branchMask := branchMask
        sq(emptyIdx).epoch := 0.U  // TODO: 从 ROB 获取当前 epoch
        sq(emptyIdx).privMode := privMode
        sq(emptyIdx).src1Tag := data.src1Tag
        sq(emptyIdx).src1Ready := data.src1Ready
        sq(emptyIdx).src2Tag := data.src2Tag
        sq(emptyIdx).src2Ready := data.src2Ready
        sq(emptyIdx).imm := data.imm
        sq(emptyIdx).PAReady := false.B
        sq(emptyIdx).PA := 0.U
        sq(emptyIdx).storeData := 0.U
        sq(emptyIdx).exception := 0.U.asTypeOf(new Exception)
        sq(emptyIdx).committed := false.B
      }
    }
  }
  
  // ============================================================================
  // 2. CDB 监听：更新操作数就绪状态
  // ============================================================================
  
  when(io.cdb_in.valid) {
    val phyRd = io.cdb_in.bits.phyRd
    val data = io.cdb_in.bits.data
    
    // 更新 LQ 中的操作数就绪状态
    for (i <- 0 until 8) {
      when(lq(i).valid && lq(i).src1Tag === phyRd) {
        lq(i).src1Ready := true.B
      }
    }
    
    // 更新 SQ 中的操作数就绪状态
    for (i <- 0 until 8) {
      when(sq(i).valid) {
        when(sq(i).src1Tag === phyRd) {
          sq(i).src1Ready := true.B
        }
        when(sq(i).src2Tag === phyRd) {
          sq(i).src2Ready := true.B
          sq(i).storeData := data
        }
      }
    }
  }
  
  // ============================================================================
  // 3. AGU 请求生成
  // ============================================================================
  
  // AGU 请求仲裁：选择一个 LQ/SQ 条目发送 AGU 请求
  val aguReqValid = WireInit(false.B)
  val aguReqBits = Wire(new AGUReq)
  
  // 收集所有需要发送 AGU 请求的条目
  val aguReqCandidates = Wire(Vec(16, Bool()))
  for (i <- 0 until 8) {
    aguReqCandidates(i) := lq(i).valid && lq(i).src1Ready && !lq(i).PAReady
    aguReqCandidates(i + 8) := sq(i).valid && sq(i).src1Ready && !sq(i).PAReady
  }
  
  // 仲裁：选择优先级最高的候选者
  val aguReqVec = Cat(aguReqCandidates.reverse)
  val aguReqIdx = PriorityEncoder(aguReqVec)
  
  when(aguReqVec =/= 0.U) {
    aguReqValid := true.B
    when(aguReqIdx < 8.U) {
      // LQ 条目
      val idx = aguReqIdx
      aguReqBits.baseAddr := io.rs1_data
      aguReqBits.offset := lq(idx).imm
      aguReqBits.memWidth := lq(idx).memWidth
      aguReqBits.memOp := LSUOp.LOAD
      aguReqBits.privMode := lq(idx).privMode
      aguReqBits.ctx.epoch := lq(idx).epoch
      aguReqBits.ctx.branchMask := lq(idx).branchMask
      aguReqBits.ctx.robId := lq(idx).robId
    }.otherwise {
      // SQ 条目
      val idx = aguReqIdx - 8.U
      aguReqBits.baseAddr := io.rs1_data
      aguReqBits.offset := sq(idx).imm
      aguReqBits.memWidth := sq(idx).memWidth
      aguReqBits.memOp := LSUOp.STORE
      aguReqBits.privMode := sq(idx).privMode
      aguReqBits.ctx.epoch := sq(idx).epoch
      aguReqBits.ctx.branchMask := sq(idx).branchMask
      aguReqBits.ctx.robId := sq(idx).robId
    }
  }
  
  io.agu_req.valid := aguReqValid
  io.agu_req.bits := aguReqBits
  
  // ============================================================================
  // 4. AGU 响应处理
  // ============================================================================
  
  when(io.agu_resp.valid) {
    val resp = io.agu_resp
    val robId = resp.bits.ctx.robId
    
    // 查找对应的 LQ 条目
    for (i <- 0 until 8) {
      when(lq(i).valid && lq(i).robId === robId && !lq(i).PAReady) {
        lq(i).PA := resp.bits.pa
        lq(i).PAReady := true.B
        lq(i).exception := resp.bits.exception
        
        // 建立 Store 依赖
        val newStoreMask = WireInit(0.U(8.W))
        for (j <- 0 until 8) {
          when(sq(j).valid && (!sq(j).PAReady || isAddressOverlap(lq(i).PA, sq(j).PA, lq(i).memWidth, sq(j).memWidth))) {
            newStoreMask(j) := 1.U
          }
        }
        lq(i).storeMask := newStoreMask
      }
    }
    
    // 查找对应的 SQ 条目
    for (i <- 0 until 8) {
      when(sq(i).valid && sq(i).robId === robId && !sq(i).PAReady) {
        sq(i).PA := resp.bits.pa
        sq(i).PAReady := true.B
        sq(i).exception := resp.bits.exception
      }
    }
  }
  
  // ============================================================================
  // 5. Store-Load 转发机制
  // ============================================================================
  
  // 当 Store 计算出 PA 后，进行地址比对
  for (i <- 0 until 8) {
    when(sq(i).valid && sq(i).PAReady && !RegNext(sq(i).PAReady)) {
      for (j <- 0 until 8) {
        when(lq(j).valid && lq(j).storeMask(i)) {
          when(!isAddressOverlap(lq(j).PA, sq(i).PA, lq(j).memWidth, sq(i).memWidth)) {
            // 地址不冲突，清除依赖
            lq(j).storeMask := lq(j).storeMask & ~(1.U << i)
          }
        }
      }
    }
  }
  
  // 当 Store 执行时，进行数据转发
  for (i <- 0 until 8) {
    when(sq(i).valid && sq(i).PAReady && sq(i).src2Ready && sq(i).committed) {
      for (j <- 0 until 8) {
        when(lq(j).valid && lq(j).storeMask(i)) {
          // 发生 Store-Load 转发
          lq(j).isForwarding := true.B
          lq(j).forwardingData := sq(i).storeData
          // 清除依赖
          lq(j).storeMask := lq(j).storeMask & ~(1.U << i)
        }
      }
    }
  }
  
  // ============================================================================
  // 6. Load 指令执行和 CDB 广播
  // ============================================================================
  
  for (i <- 0 until 8) {
    when(lq(i).valid && lq(i).PAReady && lq(i).storeMask === 0.U && !lq(i).cacheReqSent) {
      // 检查是否有异常
      when(lq(i).exception.valid) {
        // 有异常，广播异常
        io.cdb.valid := true.B
        io.cdb.bits.robId := lq(i).robId
        io.cdb.bits.phyRd := lq(i).phyRd
        io.cdb.bits.data := 0.U
        io.cdb.bits.hasSideEffect := false.B
        io.cdb.bits.exception := lq(i).exception
        
        io.exception.valid := true.B
        io.exception.bits := lq(i).exception
        
        lq(i).valid := false.B
      }.elsewhen(lq(i).isForwarding) {
        // 使用转发的数据
        val loadData = signExtend(lq(i).forwardingData, lq(i).memSign, lq(i).memWidth)
        
        io.cdb.valid := true.B
        io.cdb.bits.robId := lq(i).robId
        io.cdb.bits.phyRd := lq(i).phyRd
        io.cdb.bits.data := loadData
        io.cdb.bits.hasSideEffect := false.B
        io.cdb.bits.exception := 0.U.asTypeOf(new Exception)
        
        lq(i).valid := false.B
      }.otherwise {
        // 发向 D-Cache
        io.d_req.valid := true.B
        io.d_req.bits.addr := lq(i).PA
        io.d_req.bits.isWrite := false.B
        io.d_req.bits.data := 0.U
        io.d_req.bits.strb := 0.U
        
        val strb = MuxCase(0xF.U, Seq(
          (lq(i).memWidth === LSUWidth.BYTE) -> Mux(lq(i).PA(1,0) === "b00".U, 0x1.U, 
            Mux(lq(i).PA(1,0) === "b01".U, 0x2.U, 
            Mux(lq(i).PA(1,0) === "b10".U, 0x4.U, 0x8.U))),
          (lq(i).memWidth === LSUWidth.HALF) -> Mux(lq(i).PA(1,0) === "b00".U, 0x3.U, 
            Mux(lq(i).PA(1,0) === "b10".U, 0xC.U, 0.U)),
          (lq(i).memWidth === LSUWidth.WORD) -> 0xF.U
        ))
        io.d_req.bits.strb := strb
        
        io.d_req.bits.ctx.epoch := lq(i).epoch
        io.d_req.bits.ctx.branchMask := lq(i).branchMask
        io.d_req.bits.ctx.robId := lq(i).robId
        
        lq(i).cacheReqSent := true.B
      }
    }
  }
  
  // ============================================================================
  // 7. D-Cache 响应处理
  // ============================================================================
  
  when(io.d_resp.valid) {
    val resp = io.d_resp
    val robId = resp.bits.ctx.robId
    
    // 查找对应的 LQ 条目
    for (i <- 0 until 8) {
      when(lq(i).valid && lq(i).robId === robId && lq(i).cacheReqSent) {
        // 检查是否有异常
        when(resp.bits.exception.valid) {
          // 有异常，广播异常
          io.cdb.valid := true.B
          io.cdb.bits.robId := lq(i).robId
          io.cdb.bits.phyRd := lq(i).phyRd
          io.cdb.bits.data := 0.U
          io.cdb.bits.hasSideEffect := false.B
          io.cdb.bits.exception := resp.bits.exception
          
          io.exception.valid := true.B
          io.exception.bits := resp.bits.exception
        }.otherwise {
          // 符号扩展数据
          val loadData = signExtend(resp.bits.data, lq(i).memSign, lq(i).memWidth)
          
          // 广播到 CDB
          io.cdb.valid := true.B
          io.cdb.bits.robId := lq(i).robId
          io.cdb.bits.phyRd := lq(i).phyRd
          io.cdb.bits.data := loadData
          io.cdb.bits.hasSideEffect := false.B
          io.cdb.bits.exception := 0.U.asTypeOf(new Exception)
        }
        
        lq(i).valid := false.B
      }
    }
  }
  
  // ============================================================================
  // 8. Store 指令提交处理
  // ============================================================================
  
  when(io.commit.valid) {
    val robId = io.commit.bits.robId
    val isStore = io.commit.bits.isStore
    
    when(isStore) {
      // 查找对应的 SQ 条目
      for (i <- 0 until 8) {
        when(sq(i).valid && sq(i).robId === robId && !sq(i).committed) {
          // 检查是否有异常
          when(sq(i).exception.valid) {
            // 有异常，广播异常
            io.exception.valid := true.B
            io.exception.bits := sq(i).exception
            
            sq(i).valid := false.B
          }.otherwise {
            // 标记为已提交
            sq(i).committed := true.B
          }
        }
      }
    }
  }
  
  // ============================================================================
  // 9. Store 指令执行（向 D-Cache 写入）
  // ============================================================================
  
  for (i <- 0 until 8) {
    when(sq(i).valid && sq(i).PAReady && sq(i).src2Ready && sq(i).committed) {
      // 发向 D-Cache
      io.d_req.valid := true.B
      io.d_req.bits.addr := sq(i).PA
      io.d_req.bits.isWrite := true.B
      io.d_req.bits.data := sq(i).storeData
      
      val strb = MuxCase(0xF.U, Seq(
        (sq(i).memWidth === LSUWidth.BYTE) -> Mux(sq(i).PA(1,0) === "b00".U, 0x1.U, 
          Mux(sq(i).PA(1,0) === "b01".U, 0x2.U, 
          Mux(sq(i).PA(1,0) === "b10".U, 0x4.U, 0x8.U))),
        (sq(i).memWidth === LSUWidth.HALF) -> Mux(sq(i).PA(1,0) === "b00".U, 0x3.U, 
          Mux(sq(i).PA(1,0) === "b10".U, 0xC.U, 0.U)),
        (sq(i).memWidth === LSUWidth.WORD) -> 0xF.U
      ))
      io.d_req.bits.strb := strb
      
      io.d_req.bits.ctx.epoch := sq(i).epoch
      io.d_req.bits.ctx.branchMask := sq(i).branchMask
      io.d_req.bits.ctx.robId := sq(i).robId
      
      sq(i).valid := false.B
    }
  }
  
  // ============================================================================
  // 10. 冲刷处理
  // ============================================================================
  
  // Global Flush：清空 LQ 和 SQ
  when(io.flush.global) {
    for (i <- 0 until 8) {
      lq(i).valid := false.B
      sq(i).valid := false.B
    }
  }
  
  // Branch Flush：根据 branchMask 清理依赖指令
  when(io.flush.branchMask =/= 0.U) {
    val kill = io.flush.branchMask
    
    for (i <- 0 until 8) {
      when(lq(i).valid) {
        when((lq(i).branchMask & kill) =/= 0.U) {
          // 需要冲刷
          lq(i).valid := false.B
        }.otherwise {
          // 移除对应的分支依赖
          lq(i).branchMask := lq(i).branchMask & ~kill
        }
      }
      
      when(sq(i).valid) {
        when((sq(i).branchMask & kill) =/= 0.U) {
          // 需要冲刷
          sq(i).valid := false.B
        }.otherwise {
          // 移除对应的分支依赖
          sq(i).branchMask := sq(i).branchMask & ~kill
        }
      }
    }
  }
}
