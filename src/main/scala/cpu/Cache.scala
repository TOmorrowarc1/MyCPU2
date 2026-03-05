package cpu

import chisel3._
import chisel3.util._

/**
 * Cache (缓存) 模块
 * 
 * 提供基于物理地址的高速访问，I-Cache 服务 Fetcher，D-Cache 服务 LSU
 * 支持 FENCE.I 脏行清空
 * 
 * 功能：
 * - 2 周期流水线访问
 * - 三个独立阵列（Tag/Data/Status）
 * - AXIReqBuffer 缓冲 AXI 请求
 * - FENCE.I 处理（清空 I-Cache，写回脏行）
 * - Write-Back 策略
 */
class Cache extends Module with CPUConfig {
  val io = IO(new Bundle {
    // I-Cache 接口
    val i_req = Input(Valid(new Bundle {
      val pc = UInt(32.W)         // 程序计数器
      val ctx = new MemContext    // 内存上下文
    }))
    val i_resp = Output(Valid(new Bundle {
      val data = UInt(32.W)       // 指令数据
      val ctx = new MemContext    // 内存上下文
      val exception = new Exception  // 异常信息
    }))
    
    // D-Cache 接口
    val d_req = Input(Valid(new Bundle {
      val addr = UInt(32.W)       // 物理地址
      val isWrite = Bool()        // 是否写操作
      val data = UInt(32.W)      // 写数据
      val strb = UInt(4.W)       // 字节掩码
      val ctx = new MemContext    // 内存上下文
    }))
    val d_resp = Output(Valid(new Bundle {
      val data = UInt(32.W)      // 读数据
      val ctx = new MemContext   // 内存上下文
      val exception = new Exception  // 异常信息
    }))
    
    // 到 AXIArbiter 的接口
    val axi = new WideAXI4Bundle
    
    // FENCE.I 信号
    val fence_i = Input(Bool())
  })

  // ============================================================================
  // Cache 参数配置
  // ============================================================================
  
  // Cache Line: 64 字节 (512-bit)
  val lineBytes = 64
  val lineBits = lineBytes * 8
  
  // 地址分解
  val offsetBits = 6  // 2^6 = 64 字节
  val indexBits = 8   // 256 行
  val tagBits = 32 - offsetBits - indexBits  // 18 位
  
  // Cache 行数
  val numLines = 1 << indexBits
  
  // ============================================================================
  // 状态机定义
  // ============================================================================
  
  object CacheState extends ChiselEnum {
    val Pipeline, AXIReqPending, FENCEIActive = Value
  }
  
  // I-Cache 和 D-Cache 独立状态机
  val iState = RegInit(CacheState.Pipeline)
  val dState = RegInit(CacheState.Pipeline)
  
  // ============================================================================
  // Cache Line 状态
  // ============================================================================
  
  class CacheLineStatus extends Bundle {
    val valid = Bool()
    val dirty = Bool()
    val tag = UInt(tagBits.W)
  }
  
  // ============================================================================
  // I-Cache 结构
  // ============================================================================
  
  // I-Cache: 三个独立阵列
  val iTagArray = SyncReadMem(numLines, UInt(tagBits.W))
  val iDataArray = SyncReadMem(numLines, UInt(lineBits.W))
  val iStatusArray = RegInit(VecInit(Seq.fill(numLines)(0.U.asTypeOf(new CacheLineStatus))))
  
  // I-Cache 地址分解
  val iReqAddr = io.i_req.bits.pc
  val iReqIndex = iReqAddr(indexBits + offsetBits - 1, offsetBits)
  val iReqOffset = iReqAddr(offsetBits - 1, 0)
  val iReqTag = iReqAddr(31, indexBits + offsetBits)
  
  // I-Cache 流水线 Stage 1 寄存器
  val iStage1Valid = RegInit(false.B)
  val iStage1Addr = Reg(UInt(32.W))
  val iStage1Ctx = Reg(new MemContext)
  val iStage1IsWrite = RegInit(false.B)  // I-Cache 只读，始终为 false
  
  // I-Cache 流水线 Stage 2 寄存器
  val iStage2Valid = RegInit(false.B)
  val iStage2Addr = Reg(UInt(32.W))
  val iStage2Ctx = Reg(new MemContext)
  val iStage2Tag = Reg(UInt(tagBits.W))
  val iStage2Data = Reg(UInt(lineBits.W))
  val iStage2Status = Reg(new CacheLineStatus)
  
  // I-Cache AXI 请求缓冲
  val iAXIReqBuffer = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new Bundle {
    val valid = Bool()
    val addr = UInt(32.W)
    val ctx = new MemContext
  }))))
  val iAXIReqBufferIdx = RegInit(0.U(1.W))
  
  // I-Cache FENCE.I 扫描索引
  val iScanIndex = RegInit(0.U(indexBits.W))
  val iFenceIDone = RegInit(false.B)
  
  // ============================================================================
  // D-Cache 结构
  // ============================================================================
  
  // D-Cache: 三个独立阵列
  val dTagArray = SyncReadMem(numLines, UInt(tagBits.W))
  val dDataArray = SyncReadMem(numLines, UInt(lineBits.W))
  val dStatusArray = RegInit(VecInit(Seq.fill(numLines)(0.U.asTypeOf(new CacheLineStatus))))
  
  // D-Cache 地址分解
  val dReqAddr = io.d_req.bits.addr
  val dReqIndex = dReqAddr(indexBits + offsetBits - 1, offsetBits)
  val dReqOffset = dReqAddr(offsetBits - 1, 0)
  val dReqTag = dReqAddr(31, indexBits + offsetBits)
  
  // D-Cache 流水线 Stage 1 寄存器
  val dStage1Valid = RegInit(false.B)
  val dStage1Addr = Reg(UInt(32.W))
  val dStage1Ctx = Reg(new MemContext)
  val dStage1IsWrite = RegInit(false.B)
  val dStage1Data = Reg(UInt(32.W))
  val dStage1Strb = Reg(UInt(4.W))
  
  // D-Cache 流水线 Stage 2 寄存器
  val dStage2Valid = RegInit(false.B)
  val dStage2Addr = Reg(UInt(32.W))
  val dStage2Ctx = Reg(new MemContext)
  val dStage2Tag = Reg(UInt(tagBits.W))
  val dStage2Data = Reg(UInt(lineBits.W))
  val dStage2Status = Reg(new CacheLineStatus)
  val dStage2IsWrite = RegInit(false.B)
  val dStage2Data32 = Reg(UInt(32.W))
  val dStage2Strb = Reg(UInt(4.W))
  
  // D-Cache AXI 请求缓冲
  val dAXIReqBuffer = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new Bundle {
    val valid = Bool()
    val addr = UInt(32.W)
    val data = UInt(lineBits.W)
    val ctx = new MemContext
    val isWrite = Bool()
  }))))
  val dAXIReqBufferIdx = RegInit(0.U(1.W))
  
  // D-Cache FENCE.I 扫描索引
  val dScanIndex = RegInit(0.U(indexBits.W))
  val dFenceIDone = RegInit(false.B)
  
  // ============================================================================
  // I-Cache 状态机逻辑
  // ============================================================================
  
  // I-Cache FENCE.I 写回请求信号
  val iFenceIWritebackReq = WireInit(false.B)
  val iFenceIWritebackAddr = WireInit(0.U(32.W))
  val iFenceIWritebackData = WireInit(0.U(lineBits.W))
  
  switch(iState) {
    is(CacheState.Pipeline) {
      // 正常流水线状态
      when(io.fence_i) {
        iState := CacheState.FENCEIActive
        iScanIndex := 0.U
        iFenceIDone := false.B
      }
    }
    is(CacheState.AXIReqPending) {
      // 等待 AXI 请求完成
      when(io.axi.r.valid && io.axi.r.bits.id === AXIID.I_CACHE && io.axi.r.bits.last) {
        // 更新 Cache 行
        val fillIndex = iAXIReqBuffer(iAXIReqBufferIdx).addr(indexBits + offsetBits - 1, offsetBits)
        val fillTag = iAXIReqBuffer(iAXIReqBufferIdx).addr(31, indexBits + offsetBits)
        iTagArray.write(fillIndex, fillTag)
        iDataArray.write(fillIndex, io.axi.r.bits.data)
        iStatusArray(fillIndex).valid := true.B
        iStatusArray(fillIndex).dirty := false.B
        iStatusArray(fillIndex).tag := fillTag
        
        // 清空 AXI 请求缓冲
        iAXIReqBuffer(iAXIReqBufferIdx).valid := false.B
        iAXIReqBufferIdx := ~iAXIReqBufferIdx
        
        // 返回 Pipeline 状态
        iState := CacheState.Pipeline
      }
    }
    is(CacheState.FENCEIActive) {
      // FENCE.I 处理状态
      val status = iStatusArray(iScanIndex)
      when(status.valid && status.dirty) {
        // 发起写回
        val writebackAddr = Cat(status.tag, iScanIndex, 0.U(offsetBits.W))
        iFenceIWritebackReq := true.B
        iFenceIWritebackAddr := writebackAddr
        iFenceIWritebackData := iDataArray.read(iScanIndex)
        
        when(io.axi.aw.fire && io.axi.w.fire) {
          iStatusArray(iScanIndex).valid := false.B
          iStatusArray(iScanIndex).dirty := false.B
          iScanIndex := iScanIndex + 1.U
        }
      }.otherwise {
        // 清除有效位和脏位
        iStatusArray(iScanIndex).valid := false.B
        iStatusArray(iScanIndex).dirty := false.B
        iScanIndex := iScanIndex + 1.U
      }
      
      // 检查是否完成
      when(iScanIndex === (numLines - 1).U) {
        iFenceIDone := true.B
        iState := CacheState.Pipeline
      }
    }
  }
  
  // ============================================================================
  // D-Cache 状态机逻辑
  // ============================================================================
  
  // D-Cache FENCE.I 写回请求信号
  val dFenceIWritebackReq = WireInit(false.B)
  val dFenceIWritebackAddr = WireInit(0.U(32.W))
  val dFenceIWritebackData = WireInit(0.U(lineBits.W))
  
  switch(dState) {
    is(CacheState.Pipeline) {
      // 正常流水线状态
      when(io.fence_i) {
        dState := CacheState.FENCEIActive
        dScanIndex := 0.U
        dFenceIDone := false.B
      }
    }
    is(CacheState.AXIReqPending) {
      // 等待 AXI 请求完成
      when(io.axi.r.valid && io.axi.r.bits.id === AXIID.D_CACHE && io.axi.r.bits.last) {
        // 更新 Cache 行
        val fillIndex = dAXIReqBuffer(dAXIReqBufferIdx).addr(indexBits + offsetBits - 1, offsetBits)
        val fillTag = dAXIReqBuffer(dAXIReqBufferIdx).addr(31, indexBits + offsetBits)
        dTagArray.write(fillIndex, fillTag)
        dDataArray.write(fillIndex, io.axi.r.bits.data)
        dStatusArray(fillIndex).valid := true.B
        dStatusArray(fillIndex).dirty := false.B
        dStatusArray(fillIndex).tag := fillTag
        
        // 清空 AXI 请求缓冲
        dAXIReqBuffer(dAXIReqBufferIdx).valid := false.B
        dAXIReqBufferIdx := ~dAXIReqBufferIdx
        
        // 返回 Pipeline 状态
        dState := CacheState.Pipeline
      }.elsewhen(io.axi.b.valid && io.axi.b.bits.id === AXIID.D_CACHE) {
        // 写回完成
        dAXIReqBuffer(dAXIReqBufferIdx).valid := false.B
        dAXIReqBufferIdx := ~dAXIReqBufferIdx
        dState := CacheState.Pipeline
      }
    }
    is(CacheState.FENCEIActive) {
      // FENCE.I 处理状态
      val status = dStatusArray(dScanIndex)
      when(status.valid && status.dirty) {
        // 发起写回
        val writebackAddr = Cat(status.tag, dScanIndex, 0.U(offsetBits.W))
        dFenceIWritebackReq := true.B
        dFenceIWritebackAddr := writebackAddr
        dFenceIWritebackData := dDataArray.read(dScanIndex)
        
        when(io.axi.aw.fire && io.axi.w.fire) {
          dStatusArray(dScanIndex).valid := false.B
          dStatusArray(dScanIndex).dirty := false.B
          dScanIndex := dScanIndex + 1.U
        }
      }.otherwise {
        // 清除有效位和脏位
        dStatusArray(dScanIndex).valid := false.B
        dStatusArray(dScanIndex).dirty := false.B
        dScanIndex := dScanIndex + 1.U
      }
      
      // 检查是否完成
      when(dScanIndex === (numLines - 1).U) {
        dFenceIDone := true.B
        dState := CacheState.Pipeline
      }
    }
  }
  
  // ============================================================================
  // I-Cache 流水线逻辑
  // ============================================================================
  
  // Stage 1: 锁存请求信息，发起对 Tag 和 Data SRAM 的同步读取
  when(iState === CacheState.Pipeline && io.i_req.valid) {
    iStage1Valid := true.B
    iStage1Addr := iReqAddr
    iStage1Ctx := io.i_req.bits.ctx
    iStage1IsWrite := false.B
  }.elsewhen(iStage2Valid) {
    iStage1Valid := false.B
  }
  
  // 读取 Tag 和 Data
  val iTagRead = iTagArray.read(iReqIndex)
  val iDataRead = iDataArray.read(iReqIndex)
  val iStatusRead = iStatusArray(iReqIndex)
  
  // Stage 2: 请求信息前进一位，比对 Tag，返回结果或发起缺失处理
  when(iStage1Valid) {
    iStage2Valid := true.B
    iStage2Addr := iStage1Addr
    iStage2Ctx := iStage1Ctx
    iStage2Tag := iTagRead
    iStage2Data := iDataRead
    iStage2Status := iStatusRead
  }.elsewhen(iState === CacheState.Pipeline) {
    iStage2Valid := false.B
  }
  
  // Tag 比较逻辑
  val iHit = iStage2Status.valid && (iStage2Tag === iStage2Addr(31, indexBits + offsetBits))
  
  // 命中处理
  when(iStage2Valid && iHit) {
    // 计算指令数据（根据 offset）
    val byteOffset = iStage2Addr(1, 0)
    val instData = iStage2Data >> (byteOffset * 8.U)
    io.i_resp.valid := true.B
    io.i_resp.bits.data := instData(31, 0)
    io.i_resp.bits.ctx := iStage2Ctx
    io.i_resp.bits.exception := 0.U.asTypeOf(new Exception)
  }
  
  // 未命中处理
  when(iStage2Valid && !iHit && iState === CacheState.Pipeline) {
    // 检查是否需要写回
    when(iStage2Status.valid && iStage2Status.dirty) {
      // 先写回脏行
      val writebackAddr = Cat(iStage2Status.tag, iStage2Addr(indexBits + offsetBits - 1, offsetBits), 0.U(offsetBits.W))
      when(io.axi.aw.fire && io.axi.w.fire) {
        // 写回完成，发起读请求
        iAXIReqBuffer(iAXIReqBufferIdx).valid := true.B
        iAXIReqBuffer(iAXIReqBufferIdx).addr := Cat(iStage2Addr(31, offsetBits), 0.U(offsetBits.W))
        iAXIReqBuffer(iAXIReqBufferIdx).ctx := iStage2Ctx
        iState := CacheState.AXIReqPending
      }.otherwise {
        io.axi.aw.valid := true.B
        io.axi.aw.bits.addr := writebackAddr
        io.axi.aw.bits.id := AXIID.I_CACHE
        io.axi.aw.bits.len := 0.U
        io.axi.aw.bits.user.epoch := iStage2Ctx.epoch
        io.axi.w.valid := true.B
        io.axi.w.bits.data := iStage2Data
        io.axi.w.bits.strb := "hFFFFFFFFFFFFFFFF".U(64.W)
        io.axi.w.bits.last := true.B
      }
    }.otherwise {
      // 直接发起读请求
      iAXIReqBuffer(iAXIReqBufferIdx).valid := true.B
      iAXIReqBuffer(iAXIReqBufferIdx).addr := Cat(iStage2Addr(31, offsetBits), 0.U(offsetBits.W))
      iAXIReqBuffer(iAXIReqBufferIdx).ctx := iStage2Ctx
      iState := CacheState.AXIReqPending
      
      io.axi.ar.valid := true.B
      io.axi.ar.bits.addr := Cat(iStage2Addr(31, offsetBits), 0.U(offsetBits.W))
      io.axi.ar.bits.id := AXIID.I_CACHE
      io.axi.ar.bits.len := 0.U
      io.axi.ar.bits.user.epoch := iStage2Ctx.epoch
    }
  }
  
  // ============================================================================
  // D-Cache 流水线逻辑
  // ============================================================================
  
  // Stage 1: 锁存请求信息，发起对 Tag 和 Data SRAM 的同步读取
  when(dState === CacheState.Pipeline && io.d_req.valid) {
    dStage1Valid := true.B
    dStage1Addr := dReqAddr
    dStage1Ctx := io.d_req.bits.ctx
    dStage1IsWrite := io.d_req.bits.isWrite
    dStage1Data := io.d_req.bits.data
    dStage1Strb := io.d_req.bits.strb
  }.elsewhen(dStage2Valid) {
    dStage1Valid := false.B
  }
  
  // 读取 Tag 和 Data
  val dTagRead = dTagArray.read(dReqIndex)
  val dDataRead = dDataArray.read(dReqIndex)
  val dStatusRead = dStatusArray(dReqIndex)
  
  // Stage 2: 请求信息前进一位，比对 Tag，返回结果或发起缺失处理
  when(dStage1Valid) {
    dStage2Valid := true.B
    dStage2Addr := dStage1Addr
    dStage2Ctx := dStage1Ctx
    dStage2Tag := dTagRead
    dStage2Data := dDataRead
    dStage2Status := dStatusRead
    dStage2IsWrite := dStage1IsWrite
    dStage2Data32 := dStage1Data
    dStage2Strb := dStage1Strb
  }.elsewhen(dState === CacheState.Pipeline) {
    dStage2Valid := false.B
  }
  
  // Tag 比较逻辑
  val dHit = dStage2Status.valid && (dStage2Tag === dStage2Addr(31, indexBits + offsetBits))
  
  // 命中处理
  when(dStage2Valid && dHit) {
    when(dStage2IsWrite) {
      // 写命中：更新 Cache 行
      val byteOffset = dStage2Addr(1, 0)
      val newData = dStage2Data32
      val mask = dStage2Strb
      
      // 更新 Data Array（使用字节掩码）
      val currentData = dStage2Data
      val byte0 = Mux(mask(0), newData(7, 0), currentData(7, 0))
      val byte1 = Mux(mask(1), newData(15, 8), currentData(15, 8))
      val byte2 = Mux(mask(2), newData(23, 16), currentData(23, 16))
      val byte3 = Mux(mask(3), newData(31, 24), currentData(31, 24))
      val updatedData = Cat(byte3, byte2, byte1, byte0)
      
      // 根据字节偏移更新 Cache 行
      val wordIndex = dStage2Addr(4, 2)  // Cache 行中的字索引
      val lineData = dDataArray.read(dStage2Addr(indexBits + offsetBits - 1, offsetBits))
      val newLineData = MuxCase(lineData, (0 until 16).map { i =>
        val isNotLast = (i.U =/= 15.U)
        val isNotFirst = (i.U =/= 0.U)
        val highPart = Mux(isNotLast, lineData(511, (i + 1) * 32), 0.U)
        val lowPart = Mux(isNotFirst, lineData(i * 32 - 1, 0), 0.U)
        (wordIndex === i.U) -> Cat(highPart, updatedData, lowPart)
      })
      dDataArray.write(dStage2Addr(indexBits + offsetBits - 1, offsetBits), newLineData)
      
      // 更新 Dirty 标志
      dStatusArray(dStage2Addr(indexBits + offsetBits - 1, offsetBits)).dirty := true.B
      
      // 返回响应
      io.d_resp.valid := true.B
      io.d_resp.bits.data := 0.U
      io.d_resp.bits.ctx := dStage2Ctx
      io.d_resp.bits.exception := 0.U.asTypeOf(new Exception)
    }.otherwise {
      // 读命中：返回数据
      val byteOffset = dStage2Addr(1, 0)
      val readData = dStage2Data >> (byteOffset * 8.U)
      io.d_resp.valid := true.B
      io.d_resp.bits.data := readData(31, 0)
      io.d_resp.bits.ctx := dStage2Ctx
      io.d_resp.bits.exception := 0.U.asTypeOf(new Exception)
    }
  }
  
  // 未命中处理
  when(dStage2Valid && !dHit && dState === CacheState.Pipeline) {
    // 检查是否需要写回
    when(dStage2Status.valid && dStage2Status.dirty) {
      // 先写回脏行
      val writebackAddr = Cat(dStage2Status.tag, dStage2Addr(indexBits + offsetBits - 1, offsetBits), 0.U(offsetBits.W))
      when(io.axi.aw.fire && io.axi.w.fire) {
        // 写回完成，发起读请求
        dAXIReqBuffer(dAXIReqBufferIdx).valid := true.B
        dAXIReqBuffer(dAXIReqBufferIdx).addr := Cat(dStage2Addr(31, offsetBits), 0.U(offsetBits.W))
        dAXIReqBuffer(dAXIReqBufferIdx).ctx := dStage2Ctx
        dAXIReqBuffer(dAXIReqBufferIdx).isWrite := false.B
        dState := CacheState.AXIReqPending
      }.otherwise {
        io.axi.aw.valid := true.B
        io.axi.aw.bits.addr := writebackAddr
        io.axi.aw.bits.id := AXIID.D_CACHE
        io.axi.aw.bits.len := 0.U
        io.axi.aw.bits.user.epoch := dStage2Ctx.epoch
        io.axi.w.valid := true.B
        io.axi.w.bits.data := dStage2Data
        io.axi.w.bits.strb := "hFFFFFFFFFFFFFFFF".U(64.W)
        io.axi.w.bits.last := true.B
      }
    }.otherwise {
      // 直接发起读请求
      dAXIReqBuffer(dAXIReqBufferIdx).valid := true.B
      dAXIReqBuffer(dAXIReqBufferIdx).addr := Cat(dStage2Addr(31, offsetBits), 0.U(offsetBits.W))
      dAXIReqBuffer(dAXIReqBufferIdx).ctx := dStage2Ctx
      dAXIReqBuffer(dAXIReqBufferIdx).isWrite := false.B
      dState := CacheState.AXIReqPending
      
      io.axi.ar.valid := true.B
      io.axi.ar.bits.addr := Cat(dStage2Addr(31, offsetBits), 0.U(offsetBits.W))
      io.axi.ar.bits.id := AXIID.D_CACHE
      io.axi.ar.bits.len := 0.U
      io.axi.ar.bits.user.epoch := dStage2Ctx.epoch
    }
  }
  
  // ============================================================================
  // AXI 接口默认值
  // ============================================================================
  
  // 默认值
  io.axi.ar.valid := false.B
  io.axi.ar.bits := DontCare
  io.axi.aw.valid := false.B
  io.axi.aw.bits := DontCare
  io.axi.w.valid := false.B
  io.axi.w.bits := DontCare
  io.axi.r.ready := true.B
  io.axi.b.ready := true.B
  
  // FENCE.I 写回请求
  when(iFenceIWritebackReq) {
    io.axi.aw.valid := true.B
    io.axi.aw.bits.addr := iFenceIWritebackAddr
    io.axi.aw.bits.id := AXIID.I_CACHE
    io.axi.aw.bits.len := 0.U
    io.axi.w.valid := true.B
    io.axi.w.bits.data := iFenceIWritebackData
    io.axi.w.bits.strb := "hFFFFFFFFFFFFFFFF".U(64.W)
    io.axi.w.bits.last := true.B
  }
  
  when(dFenceIWritebackReq) {
    io.axi.aw.valid := true.B
    io.axi.aw.bits.addr := dFenceIWritebackAddr
    io.axi.aw.bits.id := AXIID.D_CACHE
    io.axi.aw.bits.len := 0.U
    io.axi.w.valid := true.B
    io.axi.w.bits.data := dFenceIWritebackData
    io.axi.w.bits.strb := "hFFFFFFFFFFFFFFFF".U(64.W)
    io.axi.w.bits.last := true.B
  }
  
  // ============================================================================
  // 响应默认值
  // ============================================================================
  
  // I-Cache 响应默认值
  when(!iStage2Valid || !iHit) {
    io.i_resp.valid := false.B
    io.i_resp.bits := DontCare
  }
  
  // D-Cache 响应默认值
  when(!dStage2Valid || !dHit) {
    io.d_resp.valid := false.B
    io.d_resp.bits := DontCare
  }
}
