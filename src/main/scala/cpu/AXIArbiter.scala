package cpu

import chisel3._
import chisel3.util._

/**
 * AXIArbiter (AXI 仲裁器) 模块
 * 
 * 汇聚 I-Cache 和 D-Cache 的请求，按优先级仲裁，并根据事务 ID 分发响应
 * 
 * 功能：
 * - 请求汇聚：将 I-Cache 的读请求与 D-Cache 的读/写请求进行仲裁
 * - 响应分发：通过 AXI ID 识别总线返回的响应，将其路由回正确的 Cache 模块
 * - 优先级仲裁：D-Cache > I-Cache
 * - 状态维护：维护 isBusy 状态，防止多个请求同时占用总线
 */
class AXIArbiter extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 I-Cache 的接口
    val i_cache = Flipped(new WideAXI4Bundle)
    
    // 来自 D-Cache 的接口
    val d_cache = Flipped(new WideAXI4Bundle)
    
    // 到 MainMemory 的接口
    val memory = new WideAXI4Bundle
  })

  // ============================================================================
  // 状态机定义
  // ============================================================================
  
  object ArbiterState extends ChiselEnum {
    val Idle, Busy = Value
  }

  val state = RegInit(ArbiterState.Idle)
  val isBusy = RegInit(false.B)

  // ============================================================================
  // 请求仲裁逻辑
  // ============================================================================
  
  // 检查是否有请求
  val iCacheHasReq = io.i_cache.ar.valid
  val dCacheHasReadReq = io.d_cache.ar.valid
  val dCacheHasWriteReq = io.d_cache.aw.valid && io.d_cache.w.valid
  val dCacheHasReq = dCacheHasReadReq || dCacheHasWriteReq

  // 仲裁结果
  val dWins = dCacheHasReq
  val iWins = iCacheHasReq && !dWins

  // ============================================================================
  // 请求转发到 MainMemory
  // ============================================================================
  
  // 默认值
  io.memory.ar.valid := false.B
  io.memory.ar.bits := DontCare
  io.memory.aw.valid := false.B
  io.memory.aw.bits := DontCare
  io.memory.w.valid := false.B
  io.memory.w.bits := DontCare

  // I-Cache 和 D-Cache 的 ready 信号
  io.i_cache.ar.ready := false.B
  io.d_cache.ar.ready := false.B
  io.d_cache.aw.ready := false.B
  io.d_cache.w.ready := false.B

  // ============================================================================
  // 状态机逻辑
  // ============================================================================
  
  switch(state) {
    is(ArbiterState.Idle) {
      // 空闲状态：等待请求
      when(dWins) {
        // D-Cache 赢得仲裁
        when(dCacheHasReadReq) {
          // D-Cache 读请求
          io.memory.ar.valid := io.d_cache.ar.valid
          io.memory.ar.bits.addr := io.d_cache.ar.bits.addr
          io.memory.ar.bits.id := AXIID.D_CACHE
          io.memory.ar.bits.len := io.d_cache.ar.bits.len
          io.memory.ar.bits.user := io.d_cache.ar.bits.user
          io.d_cache.ar.ready := io.memory.ar.ready
        }.elsewhen(dCacheHasWriteReq) {
          // D-Cache 写请求
          io.memory.aw.valid := io.d_cache.aw.valid
          io.memory.aw.bits.addr := io.d_cache.aw.bits.addr
          io.memory.aw.bits.id := AXIID.D_CACHE
          io.memory.aw.bits.len := io.d_cache.aw.bits.len
          io.memory.aw.bits.user := io.d_cache.aw.bits.user
          io.memory.w.valid := io.d_cache.w.valid
          io.memory.w.bits.data := io.d_cache.w.bits.data
          io.memory.w.bits.strb := io.d_cache.w.bits.strb
          io.memory.w.bits.last := io.d_cache.w.bits.last
          io.d_cache.aw.ready := io.memory.aw.ready
          io.d_cache.w.ready := io.memory.w.ready
        }

        // 请求被接受，进入 Busy 状态
        when(io.memory.ar.fire || io.memory.aw.fire) {
          state := ArbiterState.Busy
          isBusy := true.B
        }
      }.elsewhen(iWins) {
        // I-Cache 赢得仲裁（只有读请求）
        io.memory.ar.valid := io.i_cache.ar.valid
        io.memory.ar.bits.addr := io.i_cache.ar.bits.addr
        io.memory.ar.bits.id := AXIID.I_CACHE
        io.memory.ar.bits.len := io.i_cache.ar.bits.len
        io.memory.ar.bits.user := io.i_cache.ar.bits.user
        io.i_cache.ar.ready := io.memory.ar.ready

        // 请求被接受，进入 Busy 状态
        when(io.memory.ar.fire) {
          state := ArbiterState.Busy
          isBusy := true.B
        }
      }
    }
    is(ArbiterState.Busy) {
      // 忙碌状态：等待事务完成
      // 不接受新请求，所有 ready 信号保持 false
      
      // 检查事务是否完成
      // 读事务：R 通道完成且 last 标志为真
      // 写事务：B 通道完成
      when(io.memory.r.fire && io.memory.r.bits.last) {
        state := ArbiterState.Idle
        isBusy := false.B
      }.elsewhen(io.memory.b.fire) {
        state := ArbiterState.Idle
        isBusy := false.B
      }
    }
  }

  // ============================================================================
  // 响应路由逻辑
  // ============================================================================
  
  // 默认值
  io.i_cache.r.valid := false.B
  io.i_cache.r.bits := DontCare
  io.d_cache.r.valid := false.B
  io.d_cache.r.bits := DontCare
  io.d_cache.b.valid := false.B
  io.d_cache.b.bits := DontCare

  // R 通道响应路由（读数据）
  when(io.memory.r.valid) {
    val inflightId = io.memory.r.bits.id
    when(inflightId === AXIID.I_CACHE) {
      // 分发到 I-Cache
      io.i_cache.r.valid := io.memory.r.valid
      io.i_cache.r.bits.data := io.memory.r.bits.data
      io.i_cache.r.bits.id := io.memory.r.bits.id
      io.i_cache.r.bits.last := io.memory.r.bits.last
      io.i_cache.r.bits.user := io.memory.r.bits.user
      io.memory.r.ready := io.i_cache.r.ready
    }.elsewhen(inflightId === AXIID.D_CACHE) {
      // 分发到 D-Cache
      io.d_cache.r.valid := io.memory.r.valid
      io.d_cache.r.bits.data := io.memory.r.bits.data
      io.d_cache.r.bits.id := io.memory.r.bits.id
      io.d_cache.r.bits.last := io.memory.r.bits.last
      io.d_cache.r.bits.user := io.memory.r.bits.user
      io.memory.r.ready := io.d_cache.r.ready
    }
  }

  // B 通道响应路由（写确认）
  // 只有 D-Cache 会发起写操作，所以 B 通道响应总是分发到 D-Cache
  when(io.memory.b.valid) {
    io.d_cache.b.valid := io.memory.b.valid
    io.d_cache.b.bits.id := io.memory.b.bits.id
    io.d_cache.b.bits.user := io.memory.b.bits.user
    io.memory.b.ready := io.d_cache.b.ready
  }
}
