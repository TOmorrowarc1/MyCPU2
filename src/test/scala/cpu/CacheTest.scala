package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * Cache (缓存) 模块测试
 * 
 * 测试 Cache 的功能，包括：
 * - I-Cache: 指令缓存 Hit/Miss 处理，Tag 比较，FENCE.I 处理
 * - D-Cache: 数据缓存 Hit/Miss 处理，Tag 比较，读/写操作，字节掩码，脏行写回，FENCE.I 处理
 * - 流水线访问：2 周期流水线访问
 * - AXI 接口：模拟 AXI 读/写响应
 */
class CacheTest extends AnyFlatSpec with ChiselScalatestTester {

  // ============================================================================
  // 辅助函数
  // ============================================================================

  /**
   * 创建默认内存上下文
   */
  def createDefaultContext(): MemContext = {
    val ctx = Wire(new MemContext)
    ctx.epoch := 0.U
    ctx.branchMask := 0.U
    ctx.robId := 0.U
    ctx
  }

  /**
   * 创建指定参数的内存上下文
   */
  def createContext(epoch: Int, branchMask: Int, robId: Int): MemContext = {
    val ctx = Wire(new MemContext)
    ctx.epoch := epoch.U
    ctx.branchMask := branchMask.U
    ctx.robId := robId.U
    ctx
  }

  /**
   * 发送 I-Cache 请求
   */
  def sendICacheReq(dut: Cache, pc: Int, ctx: MemContext): Unit = {
    dut.io.i_req.valid.poke(true.B)
    dut.io.i_req.bits.pc.poke(pc.U)
    dut.io.i_req.bits.ctx.epoch.poke(ctx.epoch)
    dut.io.i_req.bits.ctx.branchMask.poke(ctx.branchMask)
    dut.io.i_req.bits.ctx.robId.poke(ctx.robId)
  }

  /**
   * 清除 I-Cache 请求
   */
  def clearICacheReq(dut: Cache): Unit = {
    dut.io.i_req.valid.poke(false.B)
    dut.io.i_req.bits.pc.poke(0.U)
    dut.io.i_req.bits.ctx.epoch.poke(0.U)
    dut.io.i_req.bits.ctx.branchMask.poke(0.U)
    dut.io.i_req.bits.ctx.robId.poke(0.U)
  }

  /**
   * 发送 D-Cache 请求
   */
  def sendDCacheReq(dut: Cache, addr: Int, isWrite: Boolean, data: Int, strb: Int, ctx: MemContext): Unit = {
    dut.io.d_req.valid.poke(true.B)
    dut.io.d_req.bits.addr.poke(addr.U)
    dut.io.d_req.bits.isWrite.poke(isWrite.B)
    dut.io.d_req.bits.data.poke(data.U)
    dut.io.d_req.bits.strb.poke(strb.U)
    dut.io.d_req.bits.ctx.epoch.poke(ctx.epoch)
    dut.io.d_req.bits.ctx.branchMask.poke(ctx.branchMask)
    dut.io.d_req.bits.ctx.robId.poke(ctx.robId)
  }

  /**
   * 清除 D-Cache 请求
   */
  def clearDCacheReq(dut: Cache): Unit = {
    dut.io.d_req.valid.poke(false.B)
    dut.io.d_req.bits.addr.poke(0.U)
    dut.io.d_req.bits.isWrite.poke(false.B)
    dut.io.d_req.bits.data.poke(0.U)
    dut.io.d_req.bits.strb.poke(0.U)
    dut.io.d_req.bits.ctx.epoch.poke(0.U)
    dut.io.d_req.bits.ctx.branchMask.poke(0.U)
    dut.io.d_req.bits.ctx.robId.poke(0.U)
  }

  /**
   * 初始化 AXI 接口（默认无响应）
   */
  def initAXI(dut: Cache): Unit = {
    dut.io.axi.r.valid.poke(false.B)
    dut.io.axi.r.bits.data.poke(0.U)
    dut.io.axi.r.bits.id.poke(AXIID.I_CACHE)
    dut.io.axi.r.bits.last.poke(false.B)
    dut.io.axi.r.bits.user.epoch.poke(0.U)
    dut.io.axi.b.valid.poke(false.B)
    dut.io.axi.b.bits.id.poke(AXIID.D_CACHE)
    dut.io.axi.b.bits.user.epoch.poke(0.U)
  }

  /**
   * 发送 AXI 读响应
   */
  def sendAXIReadResp(dut: Cache, data: BigInt, id: AXIID.Type, last: Boolean = true): Unit = {
    dut.io.axi.r.valid.poke(true.B)
    dut.io.axi.r.bits.data.poke(data.U)
    dut.io.axi.r.bits.id.poke(id)
    dut.io.axi.r.bits.last.poke(last.B)
  }

  /**
   * 清除 AXI 读响应
   */
  def clearAXIReadResp(dut: Cache): Unit = {
    dut.io.axi.r.valid.poke(false.B)
    dut.io.axi.r.bits.data.poke(0.U)
    dut.io.axi.r.bits.last.poke(false.B)
  }

  /**
   * 发送 AXI 写响应
   */
  def sendAXIWriteResp(dut: Cache, id: AXIID.Type): Unit = {
    dut.io.axi.b.valid.poke(true.B)
    dut.io.axi.b.bits.id.poke(id)
  }

  /**
   * 清除 AXI 写响应
   */
  def clearAXIWriteResp(dut: Cache): Unit = {
    dut.io.axi.b.valid.poke(false.B)
  }

  /**
   * 验证 I-Cache 响应
   */
  def expectICacheResp(dut: Cache, valid: Boolean, data: Int, ctx: MemContext): Unit = {
    dut.io.i_resp.valid.expect(valid.B)
    if (valid) {
      dut.io.i_resp.bits.data.expect(data.U)
      dut.io.i_resp.bits.ctx.epoch.expect(ctx.epoch)
      dut.io.i_resp.bits.ctx.branchMask.expect(ctx.branchMask)
      dut.io.i_resp.bits.ctx.robId.expect(ctx.robId)
      dut.io.i_resp.bits.exception.valid.expect(false.B)
    }
  }

  /**
   * 验证 D-Cache 响应
   */
  def expectDCacheResp(dut: Cache, valid: Boolean, data: Int, ctx: MemContext): Unit = {
    dut.io.d_resp.valid.expect(valid.B)
    if (valid) {
      dut.io.d_resp.bits.data.expect(data.U)
      dut.io.d_resp.bits.ctx.epoch.expect(ctx.epoch)
      dut.io.d_resp.bits.ctx.branchMask.expect(ctx.branchMask)
      dut.io.d_resp.bits.ctx.robId.expect(ctx.robId)
      dut.io.d_resp.bits.exception.valid.expect(false.B)
    }
  }

  /**
   * 验证无异常
   */
  def expectNoException(dut: Cache): Unit = {
    dut.io.i_resp.bits.exception.valid.expect(false.B)
    dut.io.d_resp.bits.exception.valid.expect(false.B)
  }

  // ============================================================================
  // 1. I-Cache 基础测试
  // ============================================================================

  "Cache" should "I-Cache 第一次访问应该 Miss，第二次访问应该 Hit" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 第一次访问：Cache Miss，需要从 AXI 读取数据
      sendICacheReq(dut, 0x1000, ctx)
      dut.clock.step()  // Stage 1

      // Stage 2：检测到 Miss，发起 AXI 读请求
      clearICacheReq(dut)
      dut.clock.step()  // Stage 2

      // 模拟 AXI 读响应
      val lineData = BigInt("DEADBEEF" * 8, 16)  // 512-bit 数据
      sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
      dut.clock.step()  // 接收 AXI 响应
      clearAXIReadResp(dut)

      // 第二次访问：Cache Hit
      sendICacheReq(dut, 0x1000, ctx)
      dut.clock.step()  // Stage 1
      clearICacheReq(dut)
      dut.clock.step()  // Stage 2

      // 验证命中响应
      expectICacheResp(dut, valid = true, data = 0xDEADBEEF, ctx)
    }
  }

  "Cache" should "I-Cache 正确处理不同地址的访问" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 测试多个不同地址
      val testCases = Seq(
        (0x1000, 0x12345678),
        (0x2000, 0x87654321),
        (0x3000, 0xABCDEF00),
        (0x4000, 0x00112233)
      )

      for ((addr, expectedData) <- testCases) {
        // 第一次访问：Cache Miss
        sendICacheReq(dut, addr, ctx)
        dut.clock.step()
        clearICacheReq(dut)
        dut.clock.step()

        // 模拟 AXI 读响应
        val lineData = BigInt(expectedData.toString * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)

        // 第二次访问：Cache Hit
        sendICacheReq(dut, addr, ctx)
        dut.clock.step()
        clearICacheReq(dut)
        dut.clock.step()

        // 验证命中响应
        expectICacheResp(dut, valid = true, data = expectedData, ctx)
      }
    }
  }

  "Cache" should "I-Cache 正确处理同一 Cache Line 内的不同偏移" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 同一个 Cache Line (64 字节) 内的不同偏移
      val baseAddr = 0x1000
      val offsets = Seq(0x0, 0x4, 0x8, 0xC, 0x10, 0x20, 0x30, 0x3C)

      // 第一次访问：Cache Miss
      sendICacheReq(dut, baseAddr, ctx)
      dut.clock.step()
      clearICacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData = BigInt("000102030405060708090A0B0C0D0E0F" * 2, 16)
      sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 测试不同偏移的访问
      for (offset <- offsets) {
        val addr = baseAddr + offset
        sendICacheReq(dut, addr, ctx)
        dut.clock.step()
        clearICacheReq(dut)
        dut.clock.step()

        // 验证命中响应
        val byteOffset = offset % 4
        val wordOffset = offset / 4
        val expectedData = wordOffset & 0xFF | ((wordOffset + 1) & 0xFF) << 8 | 
                          ((wordOffset + 2) & 0xFF) << 16 | ((wordOffset + 3) & 0xFF) << 24
        expectICacheResp(dut, valid = true, data = expectedData, ctx)
      }
    }
  }

  // ============================================================================
  // 2. D-Cache 读操作测试
  // ============================================================================

  "Cache" should "D-Cache 读操作第一次访问应该 Miss，第二次访问应该 Hit" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 第一次访问：Cache Miss，需要从 AXI 读取数据
      sendDCacheReq(dut, 0x1000, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()  // Stage 1

      // Stage 2：检测到 Miss，发起 AXI 读请求
      clearDCacheReq(dut)
      dut.clock.step()  // Stage 2

      // 模拟 AXI 读响应
      val lineData = BigInt("12345678" * 8, 16)  // 512-bit 数据
      sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
      dut.clock.step()  // 接收 AXI 响应
      clearAXIReadResp(dut)

      // 第二次访问：Cache Hit
      sendDCacheReq(dut, 0x1000, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()  // Stage 1
      clearDCacheReq(dut)
      dut.clock.step()  // Stage 2

      // 验证命中响应
      expectDCacheResp(dut, valid = true, data = 0x12345678, ctx)
    }
  }

  "Cache" should "D-Cache 读操作正确处理不同地址的访问" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 测试多个不同地址
      val testCases = Seq(
        (0x1000, 0x11111111),
        (0x2000, 0x22222222),
        (0x3000, 0x33333333),
        (0x4000, 0x44444444)
      )

      for ((addr, expectedData) <- testCases) {
        // 第一次访问：Cache Miss
        sendDCacheReq(dut, addr, isWrite = false, data = 0, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()

        // 模拟 AXI 读响应
        val lineData = BigInt(expectedData.toString * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)

        // 第二次访问：Cache Hit
        sendDCacheReq(dut, addr, isWrite = false, data = 0, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()

        // 验证命中响应
        expectDCacheResp(dut, valid = true, data = expectedData, ctx)
      }
    }
  }

  // ============================================================================
  // 3. D-Cache 写操作测试
  // ============================================================================

  "Cache" should "D-Cache 写操作正确更新 Cache 行" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 先读取数据到 Cache
      sendDCacheReq(dut, 0x1000, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData = BigInt("00000000" * 8, 16)
      sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 写入新数据
      sendDCacheReq(dut, 0x1000, isWrite = true, data = 0x12345678, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 验证写入成功
      expectDCacheResp(dut, valid = true, data = 0, ctx)

      // 再次读取，验证数据已更新
      sendDCacheReq(dut, 0x1000, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 验证读取到新数据
      expectDCacheResp(dut, valid = true, data = 0x12345678, ctx)
    }
  }

  "Cache" should "D-Cache 写操作正确使用字节掩码" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 先读取数据到 Cache
      sendDCacheReq(dut, 0x1000, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData = BigInt("FFFFFFFF" * 8, 16)
      sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 测试不同的字节掩码
      val testCases = Seq(
        (0x12345678, 0x1, 0x12FFFFFF),  // 只写最低字节
        (0x12345678, 0x2, 0x34FFFFFF),  // 只写第二个字节
        (0x12345678, 0x4, 0x56FFFFFF),  // 只写第三个字节
        (0x12345678, 0x8, 0x78FFFFFF),  // 只写最高字节
        (0x12345678, 0x3, 0x3412FFFF),  // 写最低两个字节
        (0x12345678, 0xF, 0x12345678)   // 写所有字节
      )

      for ((writeData, strb, expectedData) <- testCases) {
        // 写入数据
        sendDCacheReq(dut, 0x1000, isWrite = true, data = writeData, strb = strb, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()

        // 验证写入成功
        expectDCacheResp(dut, valid = true, data = 0, ctx)

        // 再次读取，验证数据已更新
        sendDCacheReq(dut, 0x1000, isWrite = false, data = 0, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()

        // 验证读取到新数据
        expectDCacheResp(dut, valid = true, data = expectedData, ctx)
      }
    }
  }

  // ============================================================================
  // 4. Tag 比较测试
  // ============================================================================

  "Cache" should "I-Cache 正确比较 Tag" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 地址分解：tag(31:14), index(13:6), offset(5:0)
      // 测试相同 index 但不同 tag 的地址
      val addr1 = 0x00001000  // tag = 0x00000, index = 0x40
      val addr2 = 0x40001000  // tag = 0x10000, index = 0x40

      // 访问第一个地址
      sendICacheReq(dut, addr1, ctx)
      dut.clock.step()
      clearICacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData1 = BigInt("11111111" * 8, 16)
      sendAXIReadResp(dut, lineData1, AXIID.I_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 第二次访问第一个地址：应该 Hit
      sendICacheReq(dut, addr1, ctx)
      dut.clock.step()
      clearICacheReq(dut)
      dut.clock.step()
      expectICacheResp(dut, valid = true, data = 0x11111111, ctx)

      // 访问第二个地址（相同 index，不同 tag）：应该 Miss
      sendICacheReq(dut, addr2, ctx)
      dut.clock.step()
      clearICacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData2 = BigInt("22222222" * 8, 16)
      sendAXIReadResp(dut, lineData2, AXIID.I_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 第二次访问第二个地址：应该 Hit
      sendICacheReq(dut, addr2, ctx)
      dut.clock.step()
      clearICacheReq(dut)
      dut.clock.step()
      expectICacheResp(dut, valid = true, data = 0x22222222, ctx)
    }
  }

  "Cache" should "D-Cache 正确比较 Tag" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 地址分解：tag(31:14), index(13:6), offset(5:0)
      // 测试相同 index 但不同 tag 的地址
      val addr1 = 0x00001000  // tag = 0x00000, index = 0x40
      val addr2 = 0x40001000  // tag = 0x10000, index = 0x40

      // 访问第一个地址
      sendDCacheReq(dut, addr1, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData1 = BigInt("11111111" * 8, 16)
      sendAXIReadResp(dut, lineData1, AXIID.D_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 第二次访问第一个地址：应该 Hit
      sendDCacheReq(dut, addr1, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()
      expectDCacheResp(dut, valid = true, data = 0x11111111, ctx)

      // 访问第二个地址（相同 index，不同 tag）：应该 Miss
      sendDCacheReq(dut, addr2, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData2 = BigInt("22222222" * 8, 16)
      sendAXIReadResp(dut, lineData2, AXIID.D_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 第二次访问第二个地址：应该 Hit
      sendDCacheReq(dut, addr2, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()
      expectDCacheResp(dut, valid = true, data = 0x22222222, ctx)
    }
  }

  // ============================================================================
  // 5. 脏行写回测试
  // ============================================================================

  "Cache" should "D-Cache Miss 时正确写回脏行" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 地址分解：tag(31:14), index(13:6), offset(5:0)
      val addr1 = 0x00001000  // tag = 0x00000, index = 0x40
      val addr2 = 0x40001000  // tag = 0x10000, index = 0x40

      // 访问第一个地址
      sendDCacheReq(dut, addr1, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData1 = BigInt("11111111" * 8, 16)
      sendAXIReadResp(dut, lineData1, AXIID.D_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 修改数据，使其变为脏行
      sendDCacheReq(dut, addr1, isWrite = true, data = 0x12345678, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 访问第二个地址（相同 index，不同 tag）：应该先写回脏行
      sendDCacheReq(dut, addr2, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 写响应
      sendAXIWriteResp(dut, AXIID.D_CACHE)
      dut.clock.step()
      clearAXIWriteResp(dut)

      // 模拟 AXI 读响应
      val lineData2 = BigInt("22222222" * 8, 16)
      sendAXIReadResp(dut, lineData2, AXIID.D_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)

      // 验证第二个地址的访问成功
      sendDCacheReq(dut, addr2, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()
      expectDCacheResp(dut, valid = true, data = 0x22222222, ctx)
    }
  }

  // ============================================================================
  // 6. FENCE.I 测试
  // ============================================================================

  "Cache" should "I-Cache 正确处理 FENCE.I 指令" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 访问多个地址
      for (i <- 0 until 4) {
        val addr = 0x1000 + i * 0x40
        sendICacheReq(dut, addr, ctx)
        dut.clock.step()
        clearICacheReq(dut)
        dut.clock.step()

        // 模拟 AXI 读响应
        val lineData = BigInt((i * 0x11111111).toString * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)
      }

      // 发起 FENCE.I
      dut.io.fence_i.poke(true.B)
      dut.clock.step()
      dut.io.fence_i.poke(false.B)

      // 等待 FENCE.I 完成（需要扫描所有 256 行）
      for (_ <- 0 until 260) {
        dut.clock.step()
      }

      // 再次访问之前的地址：应该 Miss（因为 FENCE.I 清空了 I-Cache）
      sendICacheReq(dut, 0x1000, ctx)
      dut.clock.step()
      clearICacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData = BigInt("DEADBEEF" * 8, 16)
      sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)
    }
  }

  "Cache" should "D-Cache 正确处理 FENCE.I 指令" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 访问多个地址并修改数据
      for (i <- 0 until 4) {
        val addr = 0x1000 + i * 0x40
        sendDCacheReq(dut, addr, isWrite = false, data = 0, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()

        // 模拟 AXI 读响应
        val lineData = BigInt("00000000" * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)

        // 修改数据，使其变为脏行
        sendDCacheReq(dut, addr, isWrite = true, data = i * 0x11111111, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()
      }

      // 发起 FENCE.I
      dut.io.fence_i.poke(true.B)
      dut.clock.step()
      dut.io.fence_i.poke(false.B)

      // 等待 FENCE.I 完成（需要扫描所有 256 行，并写回脏行）
      for (_ <- 0 until 300) {
        dut.clock.step()
      }

      // 再次访问之前的地址：应该 Miss（因为 FENCE.I 清空了 D-Cache）
      sendDCacheReq(dut, 0x1000, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 模拟 AXI 读响应
      val lineData = BigInt("DEADBEEF" * 8, 16)
      sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)
    }
  }

  // ============================================================================
  // 7. 流水线访问测试
  // ============================================================================

  "Cache" should "正确处理 2 周期流水线访问" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 发送连续的请求
      val addrs = Seq(0x1000, 0x1004, 0x1008, 0x100C)

      for (addr <- addrs) {
        sendICacheReq(dut, addr, ctx)
        dut.clock.step()  // Stage 1
        clearICacheReq(dut)
        dut.clock.step()  // Stage 2

        // 模拟 AXI 读响应
        val lineData = BigInt("DEADBEEF" * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)
      }
    }
  }

  // ============================================================================
  // 8. 上下文传递测试
  // ============================================================================

  "Cache" should "正确传递内存上下文" in {
    test(new Cache) { dut =>
      initAXI(dut)

      // 测试不同的上下文值
      val testCases = Seq(
        (0, 0, 0),
        (1, 1, 1),
        (2, 2, 2),
        (3, 3, 3),
        (0, 0xF, 0x1F)
      )

      for ((epoch, branchMask, robId) <- testCases) {
        val ctx = createContext(epoch, branchMask, robId)

        // I-Cache 测试
        sendICacheReq(dut, 0x1000, ctx)
        dut.clock.step()
        clearICacheReq(dut)
        dut.clock.step()

        // 模拟 AXI 读响应
        val lineData = BigInt("DEADBEEF" * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)

        // 验证上下文
        sendICacheReq(dut, 0x1000, ctx)
        dut.clock.step()
        clearICacheReq(dut)
        dut.clock.step()
        expectICacheResp(dut, valid = true, data = 0xDEADBEEF, ctx)

        // D-Cache 测试
        sendDCacheReq(dut, 0x2000, isWrite = false, data = 0, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()

        // 模拟 AXI 读响应
        sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)

        // 验证上下文
        sendDCacheReq(dut, 0x2000, isWrite = false, data = 0, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()
        expectDCacheResp(dut, valid = true, data = 0xDEADBEEF, ctx)
      }
    }
  }

  // ============================================================================
  // 9. 综合测试
  // ============================================================================

  "Cache" should "正确处理复杂的测试场景" in {
    test(new Cache) { dut =>
      initAXI(dut)
      val ctx = createDefaultContext()

      // 测试 1: I-Cache 读取多个地址
      for (i <- 0 until 4) {
        val addr = 0x1000 + i * 0x40
        sendICacheReq(dut, addr, ctx)
        dut.clock.step()
        clearICacheReq(dut)
        dut.clock.step()

        val lineData = BigInt((i * 0x11111111).toString * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)
      }

      // 测试 2: D-Cache 读取多个地址
      for (i <- 0 until 4) {
        val addr = 0x2000 + i * 0x40
        sendDCacheReq(dut, addr, isWrite = false, data = 0, strb = 0xF, ctx)
        dut.clock.step()
        clearDCacheReq(dut)
        dut.clock.step()

        val lineData = BigInt((i * 0x22222222).toString * 8, 16)
        sendAXIReadResp(dut, lineData, AXIID.D_CACHE, last = true)
        dut.clock.step()
        clearAXIReadResp(dut)
      }

      // 测试 3: D-Cache 写入数据
      sendDCacheReq(dut, 0x2000, isWrite = true, data = 0x12345678, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()

      // 测试 4: 验证写入的数据
      sendDCacheReq(dut, 0x2000, isWrite = false, data = 0, strb = 0xF, ctx)
      dut.clock.step()
      clearDCacheReq(dut)
      dut.clock.step()
      expectDCacheResp(dut, valid = true, data = 0x12345678, ctx)

      // 测试 5: 发起 FENCE.I
      dut.io.fence_i.poke(true.B)
      dut.clock.step()
      dut.io.fence_i.poke(false.B)

      // 等待 FENCE.I 完成
      for (_ <- 0 until 300) {
        dut.clock.step()
      }

      // 测试 6: FENCE.I 后的访问应该 Miss
      sendICacheReq(dut, 0x1000, ctx)
      dut.clock.step()
      clearICacheReq(dut)
      dut.clock.step()

      val lineData = BigInt("DEADBEEF" * 8, 16)
      sendAXIReadResp(dut, lineData, AXIID.I_CACHE, last = true)
      dut.clock.step()
      clearAXIReadResp(dut)
    }
  }
}
