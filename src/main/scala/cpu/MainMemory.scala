package cpu

import chisel3._
import chisel3.util._

/**
 * MainMemory (主存) 模块
 * 
 * 模拟物理内存的行为，提供高延迟、宽带宽的内存访问接口
 * 通过 AXI4 协议与上层缓存系统进行通信
 */
class MainMemory extends Module with CPUConfig {
  val io = IO(new Bundle {
    // AXI4 从设备接口（使用 Flipped 表示 Slave 端）
    val axi = Flipped(new WideAXI4Bundle)
  })

  // ============================================================================
  // 配置参数
  // ============================================================================
  
  // 内存访问延迟（周期数）
  val LATENCY = 10
  
  // 内存大小（4GB = 2^32 字节）
  val SIZE = 1 << 30

  // ============================================================================
  // 状态机定义
  // ============================================================================
  
  object RamState extends ChiselEnum {
    val Idle     = Value  // 空闲状态，等待新请求
    val Delay    = Value  // 延迟模拟状态
    val Response = Value  // 响应状态，发送响应数据
  }

  // ============================================================================
  // 请求信息锁存寄存器
  // ============================================================================
  
  class RequestReg extends Bundle with CPUConfig {
    val addr = UInt(32.W)       // 请求地址
    val id   = AXIID()          // 事务 ID
    val user = new AXIContext   // 上下文信息（epoch）
    val data = UInt(512.W)      // 写数据（仅写操作）
    val strb = UInt(64.W)      // 字节写掩码（仅写操作）
  }

  // 状态和寄存器
  val state      = RegInit(RamState.Idle)
  val counter    = RegInit(0.U(32.W))
  val req_reg    = Reg(new RequestReg)
  val is_read    = RegInit(false.B)

  // ============================================================================
  // SRAM 实现
  // ============================================================================
  
  // 定义 SRAM：SIZE/64 个条目，每个条目 64 字节
  // 使用 Vec(64, UInt(8.W)) 表示 64 字节块
  val mem = SyncReadMem(
    SIZE / 64,                    // 条目数量
    Vec(64, UInt(8.W))           // 每个条目 64 字节
  )

  // 可以从文件预加载内存内容（例如 kernel.hex）
  // loadMemoryFromFile(mem, "kernel.hex")

  // 读数据寄存器（用于存储 SRAM 读出的数据）
  val readDataReg = Reg(Vec(64, UInt(8.W)))

  // ============================================================================
  // 默认信号赋值
  // ============================================================================
  
  // 默认所有 ready 信号为 false
  io.axi.ar.ready := false.B
  io.axi.aw.ready := false.B
  io.axi.w.ready  := false.B
  
  // 默认所有 valid 信号为 false
  io.axi.r.valid  := false.B
  io.axi.b.valid  := false.B

  // ============================================================================
  // 状态机逻辑
  // ============================================================================
  
  switch(state) {
    // ========================================================================
    // IDLE 状态（空闲态）
    // ========================================================================
    is(RamState.Idle) {
      // 读优先策略：先检查读请求
      when(io.axi.ar.valid) {
        // 锁存读请求信息
        req_reg.addr := io.axi.ar.bits.addr
        req_reg.id   := io.axi.ar.bits.id
        req_reg.user := io.axi.ar.bits.user
        
        // 初始化延迟计数器
        counter := LATENCY.U - 1.U
        
        // 标记为读操作
        is_read := true.B
        
        // 跳转到延迟状态
        state := RamState.Delay
        
        // 握手完成
        io.axi.ar.ready := true.B
      }
      // 如果没有读请求，检查写请求（需要同时有 AW 和 W）
      .elsewhen(io.axi.aw.valid && io.axi.w.valid) {
        // 锁存写请求信息
        req_reg.addr := io.axi.aw.bits.addr
        req_reg.id   := io.axi.aw.bits.id
        req_reg.user := io.axi.aw.bits.user
        req_reg.data := io.axi.w.bits.data
        req_reg.strb := io.axi.w.bits.strb
        
        // 初始化延迟计数器
        counter := LATENCY.U - 1.U
        
        // 标记为写操作
        is_read := false.B
        
        // 跳转到延迟状态
        state := RamState.Delay
        
        // 握手完成
        io.axi.aw.ready := true.B
        io.axi.w.ready  := true.B
      }
    }

    // ========================================================================
    // DELAY 状态（延迟模拟态）
    // ========================================================================
    is(RamState.Delay) {
      when(counter === 0.U) {
        // 延迟结束，发起 SRAM 访问
        when(is_read) {
          // 读操作：发起 SRAM 读
          val readData = mem.read(req_reg.addr >> 6.U, true.B)
          // readData 在下一个周期才有效，存储到寄存器
          readDataReg := readData
        }.otherwise {
          // 写操作：执行 SRAM 写
          // 需要将 512-bit 数据转换为 Vec(64, UInt(8.W))
          val writeDataVec = VecInit((0 until 64).map { i =>
            req_reg.data(8 * (i + 1) - 1, 8 * i)
          })
          // 将字节掩码转换为 Seq[Bool]
          val writeMask = (0 until 64).map { i =>
            req_reg.strb(i)
          }
          mem.write(
            req_reg.addr >> 6.U,
            writeDataVec,
            writeMask
          )
        }
        
        // 跳转到响应状态
        state := RamState.Response
      }.otherwise {
        // 继续延迟
        counter := counter - 1.U
      }
    }

    // ========================================================================
    // RESPONSE 状态（响应态）
    // ========================================================================
    is(RamState.Response) {
      when(is_read) {
        // 读响应处理
        io.axi.r.valid := true.B
        
        // 将 Vec(64, UInt(8.W)) 转换为 UInt(512.W)
        io.axi.r.bits.data := readDataReg.asUInt
        io.axi.r.bits.id   := req_reg.id
        io.axi.r.bits.last := true.B
        io.axi.r.bits.user := req_reg.user
        
        // 等待握手完成
        when(io.axi.r.ready) {
          state := RamState.Idle
        }
      }.otherwise {
        // 写响应处理
        io.axi.b.valid := true.B
        io.axi.b.bits.id   := req_reg.id
        io.axi.b.bits.user := req_reg.user
        
        // 等待握手完成
        when(io.axi.b.ready) {
          state := RamState.Idle
        }
      }
    }
  }
}
