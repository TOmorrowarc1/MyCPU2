package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * 测试专用的 MainMemory 模块
 * 使用较小的内存大小以适应测试环境
 */
class MainMemoryTestModule extends Module with CPUConfig {
  val io = IO(new Bundle {
    // AXI4 从设备接口（使用 Flipped 表示 Slave 端）
    val axi = Flipped(new WideAXI4Bundle)
  })

  // ============================================================================
  // 配置参数
  // ============================================================================
  
  // 内存访问延迟（周期数）
  val LATENCY = 10
  
  // 内存大小（测试环境使用较小的内存大小：64KB = 2^16 字节）
  val SIZE = 1 << 16

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
  val req_reg    = RegInit({
    val r = Wire(new RequestReg)
    r.addr := 0.U
    r.id := 0.U.asTypeOf(AXIID())
    r.user.epoch := 0.U
    r.data := 0.U
    r.strb := 0.U
    r
  })
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

  // 读数据寄存器（用于存储 SRAM 读出的数据）
  val readDataReg = RegInit(VecInit(Seq.fill(64)(0.U(8.W))))

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
      // 默认赋值（避免未初始化错误）
      io.axi.r.valid := false.B
      io.axi.b.valid := false.B
      io.axi.r.bits.id := DontCare
      io.axi.r.bits.user.epoch := DontCare
      io.axi.r.bits.last := DontCare
      io.axi.r.bits.data := DontCare
      io.axi.b.bits.id := DontCare
      io.axi.b.bits.user.epoch := DontCare
      
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

/**
 * MainMemory (主存) 模块测试
 * 
 * 测试 MainMemory 的功能，包括：
 * - 读/写操作正确性：验证读操作和写操作的正确性
 * - 延迟模型：验证 10 周期延迟的正确性
 * - 状态机转换：验证状态机的正确转换（Idle → Delay → Response → Idle）
 * - AXI4 握手协议：验证 AXI4 握手协议的正确性
 * - 字节掩码：验证字节掩码的正确处理
 */
class MainMemoryTest extends AnyFlatSpec with ChiselScalatestTester {

  // ============================================================================
  // 辅助函数
  // ============================================================================

  /**
   * 创建默认的 AXI 上下文
   */
  def createDefaultAXIContext(): AXIContext = {
    val ctx = Wire(new AXIContext)
    ctx.epoch := 0.U
    ctx
  }

  /**
   * 发送读地址请求（AR 通道）
   * @param dut MainMemory 模块
   * @param addr 读地址（字节地址）
   * @param id 事务 ID
   * @param epoch 纪元信息
   */
  def sendReadRequest(dut: MainMemoryTestModule, addr: BigInt, id: AXIID.Type, epoch: Int = 0): Unit = {
    dut.io.axi.ar.valid.poke(true.B)
    dut.io.axi.ar.bits.addr.poke(addr.U)
    dut.io.axi.ar.bits.id.poke(id)
    dut.io.axi.ar.bits.len.poke(0.U) // 宽总线，一次传输
    dut.io.axi.ar.bits.user.epoch.poke(epoch.U)
  }

  /**
   * 发送写地址请求（AW 通道）
   * @param dut MainMemory 模块
   * @param addr 写地址（字节地址）
   * @param id 事务 ID
   * @param epoch 纪元信息
   */
  def sendWriteAddress(dut: MainMemoryTestModule, addr: BigInt, id: AXIID.Type, epoch: Int = 0): Unit = {
    dut.io.axi.aw.valid.poke(true.B)
    dut.io.axi.aw.bits.addr.poke(addr.U)
    dut.io.axi.aw.bits.id.poke(id)
    dut.io.axi.aw.bits.len.poke(0.U) // 宽总线，一次传输
    dut.io.axi.aw.bits.user.epoch.poke(epoch.U)
  }

  /**
   * 发送写数据（W 通道）
   * @param dut MainMemory 模块
   * @param data 写数据（512-bit）
   * @param strb 字节写掩码（64-bit）
   */
  def sendWriteData(dut: MainMemoryTestModule, data: BigInt, strb: BigInt = 0xFFFFFFFFFFFFFFFFL): Unit = {
    dut.io.axi.w.valid.poke(true.B)
    dut.io.axi.w.bits.data.poke(data.U)
    dut.io.axi.w.bits.strb.poke(strb.U)
    dut.io.axi.w.bits.last.poke(true.B) // 宽总线，一次传输
  }

  /**
   * 清除所有 AXI 输入信号
   */
  def clearAXIInputs(dut: MainMemoryTestModule): Unit = {
    dut.io.axi.ar.valid.poke(false.B)
    dut.io.axi.aw.valid.poke(false.B)
    dut.io.axi.w.valid.poke(false.B)
    dut.io.axi.r.ready.poke(false.B)
    dut.io.axi.b.ready.poke(false.B)
  }

  /**
   * 等待 AR 握手完成
   */
  def waitForARHandshake(dut: MainMemoryTestModule): Unit = {
    while (!dut.io.axi.ar.ready.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step() // 完成握手
  }

  /**
   * 等待 AW 握手完成
   */
  def waitForAWHandshake(dut: MainMemoryTestModule): Unit = {
    while (!dut.io.axi.aw.ready.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step() // 完成握手
  }

  /**
   * 等待 W 握手完成
   */
  def waitForWHandshake(dut: MainMemoryTestModule): Unit = {
    while (!dut.io.axi.w.ready.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step() // 完成握手
  }

  /**
   * 执行完整的读操作
   * @param dut MainMemory 模块
   * @param addr 读地址
   * @param id 事务 ID
   * @param epoch 纪元信息
   * @return 读出的数据
   */
  def performRead(dut: MainMemoryTestModule, addr: BigInt, id: AXIID.Type, epoch: Int = 0): BigInt = {
    // 发送读请求
    sendReadRequest(dut, addr, id, epoch)
    waitForARHandshake(dut)
    clearAXIInputs(dut)

    // 等待延迟周期（10 周期）
    // 延迟结束后，发起 SRAM 读，再过 1 个周期数据有效
    for (_ <- 0 until 11) {
      dut.clock.step()
    }

    // 接收读响应
    dut.io.axi.r.ready.poke(true.B)
    while (!dut.io.axi.r.valid.peek().litToBoolean) {
      dut.clock.step()
    }
    val data = dut.io.axi.r.bits.data.peek().litValue
    dut.clock.step()
    dut.io.axi.r.ready.poke(false.B)

    data
  }

  /**
   * 执行完整的写操作
   * @param dut MainMemory 模块
   * @param addr 写地址
   * @param data 写数据
   * @param strb 字节写掩码
   * @param id 事务 ID
   * @param epoch 纪元信息
   */
  def performWrite(dut: MainMemoryTestModule, addr: BigInt, data: BigInt, strb: BigInt = 0xFFFFFFFFFFFFFFFFL, 
                   id: AXIID.Type, epoch: Int = 0): Unit = {
    // 发送写地址和数据
    sendWriteAddress(dut, addr, id, epoch)
    sendWriteData(dut, data, strb)
    waitForAWHandshake(dut)
    waitForWHandshake(dut)
    clearAXIInputs(dut)

    // 等待延迟周期（10 周期）
    for (_ <- 0 until 11) {
      dut.clock.step()
    }

    // 接收写响应
    dut.io.axi.b.ready.poke(true.B)
    while (!dut.io.axi.b.valid.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step()
    dut.io.axi.b.ready.poke(false.B)
  }

  /**
   * 验证读响应
   * @param dut MainMemory 模块
   * @param expectedData 期望的数据
   * @param expectedId 期望的 ID
   * @param expectedEpoch 期望的纪元
   */
  def expectReadResponse(dut: MainMemoryTestModule, expectedData: BigInt, expectedId: AXIID.Type, 
                         expectedEpoch: Int = 0): Unit = {
    dut.io.axi.r.bits.data.expect(expectedData.U)
    dut.io.axi.r.bits.id.expect(expectedId)
    dut.io.axi.r.bits.last.expect(true.B)
    dut.io.axi.r.bits.user.epoch.expect(expectedEpoch.U)
  }

  /**
   * 验证写响应
   * @param dut MainMemory 模块
   * @param expectedId 期望的 ID
   * @param expectedEpoch 期望的纪元
   */
  def expectWriteResponse(dut: MainMemoryTestModule, expectedId: AXIID.Type, expectedEpoch: Int = 0): Unit = {
    dut.io.axi.b.bits.id.expect(expectedId)
    dut.io.axi.b.bits.user.epoch.expect(expectedEpoch.U)
  }

  // ============================================================================
  // 1. 读操作测试
  // ============================================================================

  "MainMemory" should "正确执行读操作并返回数据" in {
    test(new MainMemoryTestModule) { dut =>
      // 先写入一些数据
      val addr = 0x1000L
      val data = 0xAABBCCDD11223344L
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 1)

      // 读取数据
      val readData = performRead(dut, addr, AXIID.D_CACHE, 1)
      
      // 验证读出的数据正确
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确读取 512-bit 数据块" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x2000L
      val data = BigInt("0x123456789ABCDEF0" + "FEDCBA9876543210", 16)
      
      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 读取数据
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      
      // 验证读出的数据正确
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确读取多个地址的数据" in {
    test(new MainMemoryTestModule) { dut =>
      val testCases = Seq(
        (0x1000L, 0x1111111122222222L),
        (0x2000L, 0x3333333344444444L),
        (0x3000L, 0x5555555566666666L),
        (0x4000L, 0x7777777788888888L)
      )

      // 写入数据
      for ((addr, data) <- testCases) {
        performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      }

      // 读取数据并验证
      for ((addr, expectedData) <- testCases) {
        val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
        assert(readData == expectedData, s"Expected ${expectedData}, got ${readData}")
      }
    }
  }

  "MainMemory" should "正确处理 I-Cache 和 D-Cache 的读请求" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0xAAAAAAAAAAAAAAAAL

      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // I-Cache 读请求
      val readDataI = performRead(dut, addr, AXIID.I_CACHE, 0)
      assert(readDataI == data, s"Expected ${data}, got ${readDataI}")

      // D-Cache 读请求
      val readDataD = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readDataD == data, s"Expected ${data}, got ${readDataD}")
    }
  }

  // ============================================================================
  // 2. 写操作测试
  // ============================================================================

  "MainMemory" should "正确执行写操作" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1122334455667788L

      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 读取并验证
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确写入 512-bit 数据块" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x2000L
      val data = BigInt("0x9ABCDEF012345678" + "567890ABCDEF1234", 16)

      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 1)

      // 读取并验证
      val readData = performRead(dut, addr, AXIID.D_CACHE, 1)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确写入多个地址的数据" in {
    test(new MainMemoryTestModule) { dut =>
      val testCases = Seq(
        (0x1000L, 0x1111111122222222L),
        (0x2000L, 0x3333333344444444L),
        (0x3000L, 0x5555555566666666L),
        (0x4000L, 0x7777777788888888L)
      )

      // 写入数据
      for ((addr, data) <- testCases) {
        performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      }

      // 读取并验证
      for ((addr, expectedData) <- testCases) {
        val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
        assert(readData == expectedData, s"Expected ${expectedData}, got ${readData}")
      }
    }
  }

  // ============================================================================
  // 3. 延迟模型测试
  // ============================================================================

  "MainMemory" should "读操作具有 10 周期的延迟" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 发送读请求
      sendReadRequest(dut, addr, AXIID.D_CACHE, 0)
      waitForARHandshake(dut)
      clearAXIInputs(dut)

      // 等待 10 个延迟周期
      for (i <- 0 until 10) {
        dut.io.axi.r.valid.expect(false.B)
        dut.clock.step()
      }

      // 第 11 个周期，数据应该有效
      dut.clock.step()
      dut.io.axi.r.valid.expect(true.B)
      dut.io.axi.r.bits.data.expect(data.U)
      
      // 完成握手
      dut.io.axi.r.ready.poke(true.B)
      dut.clock.step()
      dut.io.axi.r.ready.poke(false.B)
    }
  }

  "MainMemory" should "写操作具有 10 周期的延迟" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0xFEDCBA0987654321L

      // 发送写请求
      sendWriteAddress(dut, addr, AXIID.D_CACHE, 0)
      sendWriteData(dut, data, 0xFFFFFFFFFFFFFFFFL)
      waitForAWHandshake(dut)
      waitForWHandshake(dut)
      clearAXIInputs(dut)

      // 等待 10 个延迟周期
      for (i <- 0 until 10) {
        dut.io.axi.b.valid.expect(false.B)
        dut.clock.step()
      }

      // 第 11 个周期，写响应应该有效
      dut.clock.step()
      dut.io.axi.b.valid.expect(true.B)
      
      // 完成握手
      dut.io.axi.b.ready.poke(true.B)
      dut.clock.step()
      dut.io.axi.b.ready.poke(false.B)

      // 验证数据已写入
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "连续读操作的正确延迟" in {
    test(new MainMemoryTestModule) { dut =>
      val addr1 = 0x1000L
      val addr2 = 0x2000L
      val data1 = 0x1111111122222222L
      val data2 = 0x3333333344444444L

      // 写入数据
      performWrite(dut, addr1, data1, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      performWrite(dut, addr2, data2, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 连续读操作
      val readData1 = performRead(dut, addr1, AXIID.D_CACHE, 0)
      val readData2 = performRead(dut, addr2, AXIID.D_CACHE, 0)

      assert(readData1 == data1, s"Expected ${data1}, got ${readData1}")
      assert(readData2 == data2, s"Expected ${data2}, got ${readData2}")
    }
  }

  // ============================================================================
  // 4. 状态机转换测试
  // ============================================================================

  "MainMemory" should "状态机正确转换：Idle → Delay → Response → Idle（读操作）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L

      // 初始状态：Idle
      // 发送读请求
      sendReadRequest(dut, addr, AXIID.D_CACHE, 0)
      dut.clock.step() // 完成握手，状态转换为 Delay
      clearAXIInputs(dut)

      // 状态：Delay，等待 10 个周期
      for (_ <- 0 until 10) {
        dut.clock.step()
      }

      // 状态：Response，等待 R 握手
      dut.io.axi.r.ready.poke(true.B)
      while (!dut.io.axi.r.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step() // 完成握手，状态转换为 Idle
      dut.io.axi.r.ready.poke(false.B)

      // 状态：Idle，可以接受新请求
      sendReadRequest(dut, addr, AXIID.D_CACHE, 0)
      dut.io.axi.ar.ready.expect(true.B) // 证明回到了 Idle 状态
      dut.clock.step()
    }
  }

  "MainMemory" should "状态机正确转换：Idle → Delay → Response → Idle（写操作）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 初始状态：Idle
      // 发送写请求
      sendWriteAddress(dut, addr, AXIID.D_CACHE, 0)
      sendWriteData(dut, data, 0xFFFFFFFFFFFFFFFFL)
      dut.clock.step() // 完成握手，状态转换为 Delay
      clearAXIInputs(dut)

      // 状态：Delay，等待 10 个周期
      for (_ <- 0 until 10) {
        dut.clock.step()
      }

      // 状态：Response，等待 B 握手
      dut.io.axi.b.ready.poke(true.B)
      while (!dut.io.axi.b.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step() // 完成握手，状态转换为 Idle
      dut.io.axi.b.ready.poke(false.B)

      // 状态：Idle，可以接受新请求
      sendWriteAddress(dut, addr, AXIID.D_CACHE, 0)
      dut.io.axi.aw.ready.expect(true.B) // 证明回到了 Idle 状态
      dut.clock.step()
    }
  }

  "MainMemory" should "在 Idle 状态下不接受新的请求直到当前请求完成" in {
    test(new MainMemoryTestModule) { dut =>
      val addr1 = 0x1000L
      val addr2 = 0x2000L

      // 发送第一个读请求
      sendReadRequest(dut, addr1, AXIID.D_CACHE, 0)
      dut.clock.step() // 完成握手
      clearAXIInputs(dut)

      // 在 Delay 状态下，尝试发送第二个请求
      // 应该被拒绝（ready 为 false）
      dut.io.axi.ar.valid.poke(true.B)
      dut.io.axi.ar.bits.addr.poke(addr2.U)
      dut.io.axi.ar.bits.id.poke(AXIID.D_CACHE)
      dut.io.axi.ar.bits.len.poke(0.U)
      dut.io.axi.ar.bits.user.epoch.poke(0.U)
      dut.io.axi.ar.ready.expect(false.B)
      
      // 等待第一个请求完成
      for (_ <- 0 until 11) {
        dut.clock.step()
      }
      dut.io.axi.r.ready.poke(true.B)
      while (!dut.io.axi.r.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.axi.r.ready.poke(false.B)
      clearAXIInputs(dut)
    }
  }

  // ============================================================================
  // 5. AXI4 握手协议测试
  // ============================================================================

  "MainMemory" should "正确处理 AR 通道握手" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L

      // 发送 AR 请求
      dut.io.axi.ar.valid.poke(true.B)
      dut.io.axi.ar.bits.addr.poke(addr.U)
      dut.io.axi.ar.bits.id.poke(AXIID.D_CACHE)
      dut.io.axi.ar.bits.len.poke(0.U)
      dut.io.axi.ar.bits.user.epoch.poke(0.U)

      // 等待 ready 为 true
      while (!dut.io.axi.ar.ready.peek().litToBoolean) {
        dut.clock.step()
      }

      // 完成握手
      dut.clock.step()
      clearAXIInputs(dut)
    }
  }

  "MainMemory" should "正确处理 AW 和 W 通道握手" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 发送 AW 请求
      dut.io.axi.aw.valid.poke(true.B)
      dut.io.axi.aw.bits.addr.poke(addr.U)
      dut.io.axi.aw.bits.id.poke(AXIID.D_CACHE)
      dut.io.axi.aw.bits.len.poke(0.U)
      dut.io.axi.aw.bits.user.epoch.poke(0.U)

      // 发送 W 数据
      dut.io.axi.w.valid.poke(true.B)
      dut.io.axi.w.bits.data.poke(data.U)
      dut.io.axi.w.bits.strb.poke(0xFFFFFFFFFFFFFFFFL.U)
      dut.io.axi.w.bits.last.poke(true.B)

      // 等待 AW ready 为 true
      while (!dut.io.axi.aw.ready.peek().litToBoolean) {
        dut.clock.step()
      }

      // 完成握手
      dut.clock.step()
      clearAXIInputs(dut)
    }
  }

  "MainMemory" should "正确处理 R 通道握手" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 发送读请求
      sendReadRequest(dut, addr, AXIID.D_CACHE, 0)
      waitForARHandshake(dut)
      clearAXIInputs(dut)

      // 等待延迟
      for (_ <- 0 until 11) {
        dut.clock.step()
      }

      // 接收 R 响应
      dut.io.axi.r.ready.poke(true.B)
      while (!dut.io.axi.r.valid.peek().litToBoolean) {
        dut.clock.step()
      }

      // 验证响应
      expectReadResponse(dut, data, AXIID.D_CACHE, 0)

      // 完成握手
      dut.clock.step()
      dut.io.axi.r.ready.poke(false.B)
    }
  }

  "MainMemory" should "正确处理 B 通道握手" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 发送写请求
      sendWriteAddress(dut, addr, AXIID.D_CACHE, 0)
      sendWriteData(dut, data, 0xFFFFFFFFFFFFFFFFL)
      waitForAWHandshake(dut)
      waitForWHandshake(dut)
      clearAXIInputs(dut)

      // 等待延迟
      for (_ <- 0 until 11) {
        dut.clock.step()
      }

      // 接收 B 响应
      dut.io.axi.b.ready.poke(true.B)
      while (!dut.io.axi.b.valid.peek().litToBoolean) {
        dut.clock.step()
      }

      // 验证响应
      expectWriteResponse(dut, AXIID.D_CACHE, 0)

      // 完成握手
      dut.clock.step()
      dut.io.axi.b.ready.poke(false.B)
    }
  }

  "MainMemory" should "正确回传事务 ID 和 epoch 信息（读操作）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.I_CACHE, 2)

      // 发送读请求
      sendReadRequest(dut, addr, AXIID.I_CACHE, 2)
      waitForARHandshake(dut)
      clearAXIInputs(dut)

      // 等待延迟
      for (_ <- 0 until 11) {
        dut.clock.step()
      }

      // 接收 R 响应
      dut.io.axi.r.ready.poke(true.B)
      while (!dut.io.axi.r.valid.peek().litToBoolean) {
        dut.clock.step()
      }

      // 验证 ID 和 epoch 正确回传
      dut.io.axi.r.bits.id.expect(AXIID.I_CACHE)
      dut.io.axi.r.bits.user.epoch.expect(2.U)
      dut.io.axi.r.bits.data.expect(data.U)
      dut.io.axi.r.bits.last.expect(true.B)

      // 完成握手
      dut.clock.step()
      dut.io.axi.r.ready.poke(false.B)
    }
  }

  "MainMemory" should "正确回传事务 ID 和 epoch 信息（写操作）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0xFEDCBA0987654321L

      // 发送写请求
      sendWriteAddress(dut, addr, AXIID.D_CACHE, 3)
      sendWriteData(dut, data, 0xFFFFFFFFFFFFFFFFL)
      waitForAWHandshake(dut)
      waitForWHandshake(dut)
      clearAXIInputs(dut)

      // 等待延迟
      for (_ <- 0 until 11) {
        dut.clock.step()
      }

      // 接收 B 响应
      dut.io.axi.b.ready.poke(true.B)
      while (!dut.io.axi.b.valid.peek().litToBoolean) {
        dut.clock.step()
      }

      // 验证 ID 和 epoch 正确回传
      dut.io.axi.b.bits.id.expect(AXIID.D_CACHE)
      dut.io.axi.b.bits.user.epoch.expect(3.U)

      // 完成握手
      dut.clock.step()
      dut.io.axi.b.ready.poke(false.B)
    }
  }

  // ============================================================================
  // 6. 字节掩码测试
  // ============================================================================

  "MainMemory" should "正确处理字节掩码（全 1）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL
      val strb = 0xFFFFFFFFFFFFFFFFL

      // 写入数据（全字节掩码）
      performWrite(dut, addr, data, strb, AXIID.D_CACHE, 0)

      // 读取并验证
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理字节掩码（部分字节）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val originalData = 0x1111111111111111L
      val newData = 0x2233445566778899L
      val strb = 0x00000000FFFFFFFFL // 只写入低 32 位

      // 先写入原始数据
      performWrite(dut, addr, originalData, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 使用部分字节掩码写入
      performWrite(dut, addr, newData, strb, AXIID.D_CACHE, 0)

      // 读取并验证
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      // 低 32 位应该是 newData，高 32 位应该是 originalData
      val expectedData = (originalData & 0xFFFFFFFF00000000L) | (newData & 0x00000000FFFFFFFFL)
      assert(readData == expectedData, s"Expected ${expectedData}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理字节掩码（单字节）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val originalData = 0x1111111111111111L
      val newData = 0x2233445566778899L
      val strb = 0x00000000000000FFL // 只写入第一个字节

      // 先写入原始数据
      performWrite(dut, addr, originalData, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 使用单字节掩码写入
      performWrite(dut, addr, newData, strb, AXIID.D_CACHE, 0)

      // 读取并验证
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      // 只有第一个字节应该是 newData 的第一个字节
      val expectedData = (originalData & 0xFFFFFFFFFFFFFF00L) | (newData & 0x00000000000000FFL)
      assert(readData == expectedData, s"Expected ${expectedData}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理字节掩码（零掩码）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val originalData = 0x1111111111111111L
      val newData = 0x2233445566778899L
      val strb = 0x0000000000000000L // 不写入任何字节

      // 先写入原始数据
      performWrite(dut, addr, originalData, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 使用零掩码写入
      performWrite(dut, addr, newData, strb, AXIID.D_CACHE, 0)

      // 读取并验证（数据应该不变）
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == originalData, s"Expected ${originalData}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理多个字节掩码组合" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val originalData = 0x1111111111111111L

      // 测试不同的字节掩码
      val testCases = Seq(
        (0x0000000000000001L, 0x22L), // 只写入第一个字节
        (0x00000000000000FFL, 0x33L), // 写入第一个字节
        (0x000000000000FF00L, 0x44L), // 写入第二个字节
        (0x0000000000FF0000L, 0x55L), // 写入第三个字节
        (0x00000000FF000000L, 0x66L), // 写入第四个字节
        (0x000000FF00000000L, 0x77L), // 写入第五个字节
        (0x0000FF0000000000L, 0x88L), // 写入第六个字节
        (0x00FF000000000000L, 0x99L), // 写入第七个字节
        (0xFF00000000000000L, 0xAAL)  // 写入第八个字节
      )

      for ((strb, byteValue) <- testCases) {
        // 先写入原始数据
        performWrite(dut, addr, originalData, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

        // 使用字节掩码写入
        val newData = BigInt(byteValue) << (strb.toBinaryString.indexOf('1') * 8)
        performWrite(dut, addr, newData, strb, AXIID.D_CACHE, 0)

        // 读取并验证
        val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
        val expectedData = (originalData & ~strb) | (newData & strb)
        assert(readData == expectedData, s"Expected ${expectedData}, got ${readData}")
      }
    }
  }

  // ============================================================================
  // 7. 读优先策略测试
  // ============================================================================

  "MainMemory" should "读操作优先于写操作（读优先策略）" in {
    test(new MainMemoryTestModule) { dut =>
      val readAddr = 0x1000L
      val writeAddr = 0x2000L
      val writeData = 0x1234567890ABCDEFL

      // 先写入一些数据
      performWrite(dut, readAddr, 0x1111111122222222L, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 同时发送读请求和写请求
      dut.io.axi.ar.valid.poke(true.B)
      dut.io.axi.ar.bits.addr.poke(readAddr.U)
      dut.io.axi.ar.bits.id.poke(AXIID.D_CACHE)
      dut.io.axi.ar.bits.len.poke(0.U)
      dut.io.axi.ar.bits.user.epoch.poke(0.U)

      dut.io.axi.aw.valid.poke(true.B)
      dut.io.axi.aw.bits.addr.poke(writeAddr.U)
      dut.io.axi.aw.bits.id.poke(AXIID.D_CACHE)
      dut.io.axi.aw.bits.len.poke(0.U)
      dut.io.axi.aw.bits.user.epoch.poke(0.U)

      dut.io.axi.w.valid.poke(true.B)
      dut.io.axi.w.bits.data.poke(writeData.U)
      dut.io.axi.w.bits.strb.poke(0xFFFFFFFFFFFFFFFFL.U)
      dut.io.axi.w.bits.last.poke(true.B)

      // 读请求应该先被接受
      dut.io.axi.ar.ready.expect(true.B)
      dut.io.axi.aw.ready.expect(false.B)
      dut.io.axi.w.ready.expect(false.B)

      dut.clock.step()
      clearAXIInputs(dut)

      // 等待读操作完成
      for (_ <- 0 until 11) {
        dut.clock.step()
      }
      dut.io.axi.r.ready.poke(true.B)
      while (!dut.io.axi.r.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.axi.r.ready.poke(false.B)

      // 现在可以接受写请求
      sendWriteAddress(dut, writeAddr, AXIID.D_CACHE, 0)
      sendWriteData(dut, writeData, 0xFFFFFFFFFFFFFFFFL)
      dut.io.axi.aw.ready.expect(true.B)
      dut.io.axi.w.ready.expect(true.B)
    }
  }

  // ============================================================================
  // 8. 边界情况测试
  // ============================================================================

  "MainMemory" should "正确处理地址 0x0 的访问" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x0L
      val data = 0x1234567890ABCDEFL

      // 写入并读取
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理大地址的访问" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0xE000L
      val data = 0xFEDCBA0987654321L

      // 写入并读取
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理全 0 数据" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x0L

      // 写入并读取
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理全 1 数据" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0xFFFFFFFFFFFFFFFFL

      // 写入并读取
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == data, s"Expected ${data}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理读后写（Read-after-Write）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data1 = 0x1111111122222222L
      val data2 = 0x3333333344444444L

      // 写入 data1
      performWrite(dut, addr, data1, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 读取
      val readData1 = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData1 == data1, s"Expected ${data1}, got ${readData1}")

      // 写入 data2
      performWrite(dut, addr, data2, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 再次读取
      val readData2 = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData2 == data2, s"Expected ${data2}, got ${readData2}")
    }
  }

  "MainMemory" should "正确处理写后读（Write-after-Read）" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 先读取（初始数据为 0）
      val readData1 = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData1 == 0L, s"Expected 0, got ${readData1}")

      // 写入数据
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 再次读取
      val readData2 = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData2 == data, s"Expected ${data}, got ${readData2}")
    }
  }

  "MainMemory" should "正确处理连续的写操作" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val dataValues = Seq(0x1111111122222222L, 0x3333333344444444L, 
                          0x5555555566666666L, 0x7777777788888888L)

      // 连续写入
      for (data <- dataValues) {
        performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)
      }

      // 读取最终数据
      val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
      assert(readData == dataValues.last, s"Expected ${dataValues.last}, got ${readData}")
    }
  }

  "MainMemory" should "正确处理不同 epoch 的请求" in {
    test(new MainMemoryTestModule) { dut =>
      val addr = 0x1000L
      val data = 0x1234567890ABCDEFL

      // 写入数据（epoch = 0）
      performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 读取数据（epoch = 1）
      val readData = performRead(dut, addr, AXIID.D_CACHE, 1)
      assert(readData == data, s"Expected ${data}, got ${readData}")

      // 读取数据（epoch = 2）
      val readData2 = performRead(dut, addr, AXIID.D_CACHE, 2)
      assert(readData2 == data, s"Expected ${data}, got ${readData2}")

      // 读取数据（epoch = 3）
      val readData3 = performRead(dut, addr, AXIID.D_CACHE, 3)
      assert(readData3 == data, s"Expected ${data}, got ${readData3}")
    }
  }

  // ============================================================================
  // 9. 综合测试
  // ============================================================================

  "MainMemory" should "正确处理复杂的测试场景" in {
    test(new MainMemoryTestModule) { dut =>
      // 场景：混合读写操作，不同 ID 和 epoch

      // 1. 写入多个地址
      val writeOps = Seq(
        (0x1000L, 0x1111111122222222L, AXIID.I_CACHE, 0),
        (0x2000L, 0x3333333344444444L, AXIID.D_CACHE, 1),
        (0x3000L, 0x5555555566666666L, AXIID.I_CACHE, 2),
        (0x4000L, 0x7777777788888888L, AXIID.D_CACHE, 3)
      )

      for ((addr, data, id, epoch) <- writeOps) {
        performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, id, epoch)
      }

      // 2. 读取并验证
      val readOps = Seq(
        (0x1000L, 0x1111111122222222L, AXIID.I_CACHE, 0),
        (0x2000L, 0x3333333344444444L, AXIID.D_CACHE, 1),
        (0x3000L, 0x5555555566666666L, AXIID.I_CACHE, 2),
        (0x4000L, 0x7777777788888888L, AXIID.D_CACHE, 3)
      )

      for ((addr, expectedData, id, epoch) <- readOps) {
        val readData = performRead(dut, addr, id, epoch)
        assert(readData == expectedData, s"Expected ${expectedData}, got ${readData}")
      }

      // 3. 使用部分字节掩码更新数据
      performWrite(dut, 0x1000L, 0xAAAAAAAAAAAAAAAAL, 0x00000000FFFFFFFFL, AXIID.D_CACHE, 0)
      val updatedData = performRead(dut, 0x1000L, AXIID.D_CACHE, 0)
      val expectedUpdated = (0x1111111122222222L & 0xFFFFFFFF00000000L) | (0xAAAAAAAAAAAAAAAAL & 0x00000000FFFFFFFFL)
      assert(updatedData == expectedUpdated, s"Expected ${expectedUpdated}, got ${updatedData}")
    }
  }

  "MainMemory" should "正确处理大量连续的读写操作" in {
    test(new MainMemoryTestModule) { dut =>
      val numOps = 10

      // 连续读写操作
      for (i <- 0 until numOps) {
        val addr = 0x1000L + i * 0x100L
        val data = BigInt(i + 1) * 0x1111111111111111L

        // 写入
        performWrite(dut, addr, data, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

        // 读取并验证
        val readData = performRead(dut, addr, AXIID.D_CACHE, 0)
        assert(readData == data, s"Expected ${data}, got ${readData}")
      }
    }
  }

  "MainMemory" should "正确处理跨块边界的访问" in {
    test(new MainMemoryTestModule) { dut =>
      // 测试 64 字节块边界
      val blockBoundary = 0x1000L

      // 写入块边界前的地址
      val addr1 = blockBoundary - 0x40L
      val data1 = 0x1111111122222222L
      performWrite(dut, addr1, data1, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 写入块边界后的地址
      val addr2 = blockBoundary
      val data2 = 0x3333333344444444L
      performWrite(dut, addr2, data2, 0xFFFFFFFFFFFFFFFFL, AXIID.D_CACHE, 0)

      // 读取并验证
      val readData1 = performRead(dut, addr1, AXIID.D_CACHE, 0)
      val readData2 = performRead(dut, addr2, AXIID.D_CACHE, 0)

      assert(readData1 == data1, s"Expected ${data1}, got ${readData1}")
      assert(readData2 == data2, s"Expected ${data2}, got ${readData2}")
    }
  }
}
