package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * AXIArbiter (AXI 仲裁器) 模块测试
 * 
 * 测试 AXIArbiter 的功能，包括：
 * - 仲裁优先级：验证 D-Cache 优先级高于 I-Cache
 * - 响应路由：验证响应根据事务 ID 正确路由到对应的 Cache
 * - isBusy 状态：验证忙状态的正确维护
 * - Valid/Ready 握手协议：验证 AXI 握手协议的正确性
 */
class AXIArbiterTest extends AnyFlatSpec with ChiselScalatestTester {

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
   * 发送 I-Cache 读请求
   * @param dut 被测模块
   * @param addr 地址
   * @param len 突发长度
   * @param ctx AXI 上下文
   */
  def sendICacheReadReq(dut: AXIArbiter, addr: Int, len: Int, ctx: AXIContext): Unit = {
    dut.io.i_cache.ar.valid.poke(true.B)
    dut.io.i_cache.ar.bits.addr.poke(addr.U)
    dut.io.i_cache.ar.bits.id.poke(AXIID.I_CACHE)
    dut.io.i_cache.ar.bits.len.poke(len.U)
    dut.io.i_cache.ar.bits.user.epoch.poke(ctx.epoch)
  }

  /**
   * 发送 D-Cache 读请求
   * @param dut 被测模块
   * @param addr 地址
   * @param len 突发长度
   * @param ctx AXI 上下文
   */
  def sendDCacheReadReq(dut: AXIArbiter, addr: Int, len: Int, ctx: AXIContext): Unit = {
    dut.io.d_cache.ar.valid.poke(true.B)
    dut.io.d_cache.ar.bits.addr.poke(addr.U)
    dut.io.d_cache.ar.bits.id.poke(AXIID.D_CACHE)
    dut.io.d_cache.ar.bits.len.poke(len.U)
    dut.io.d_cache.ar.bits.user.epoch.poke(ctx.epoch)
  }

  /**
   * 发送 D-Cache 写请求
   * @param dut 被测模块
   * @param addr 地址
   * @param data 数据
   * @param strb 字节掩码
   * @param len 突发长度
   * @param ctx AXI 上下文
   */
  def sendDCacheWriteReq(dut: AXIArbiter, addr: Int, data: BigInt, strb: BigInt, 
                        len: Int, ctx: AXIContext): Unit = {
    dut.io.d_cache.aw.valid.poke(true.B)
    dut.io.d_cache.aw.bits.addr.poke(addr.U)
    dut.io.d_cache.aw.bits.id.poke(AXIID.D_CACHE)
    dut.io.d_cache.aw.bits.len.poke(len.U)
    dut.io.d_cache.aw.bits.user.epoch.poke(ctx.epoch)

    dut.io.d_cache.w.valid.poke(true.B)
    dut.io.d_cache.w.bits.data.poke(data.U)
    dut.io.d_cache.w.bits.strb.poke(strb.U)
    dut.io.d_cache.w.bits.last.poke(true.B)
  }

  /**
   * 清除所有请求
   */
  def clearAllReqs(dut: AXIArbiter): Unit = {
    dut.io.i_cache.ar.valid.poke(false.B)
    dut.io.d_cache.ar.valid.poke(false.B)
    dut.io.d_cache.aw.valid.poke(false.B)
    dut.io.d_cache.w.valid.poke(false.B)
  }

  /**
   * 设置 MainMemory 的读响应
   * @param dut 被测模块
   * @param id 事务 ID
   * @param data 数据
   * @param last 是否为最后一个数据
   * @param ctx AXI 上下文
   */
  def setMemReadResp(dut: AXIArbiter, id: AXIID.Type, data: BigInt, 
                     last: Boolean, ctx: AXIContext): Unit = {
    dut.io.memory.r.valid.poke(true.B)
    dut.io.memory.r.bits.data.poke(data.U)
    dut.io.memory.r.bits.id.poke(id)
    dut.io.memory.r.bits.last.poke(last.B)
    dut.io.memory.r.bits.user.epoch.poke(ctx.epoch)
  }

  /**
   * 设置 MainMemory 的写响应
   * @param dut 被测模块
   * @param id 事务 ID
   * @param ctx AXI 上下文
   */
  def setMemWriteResp(dut: AXIArbiter, id: AXIID.Type, ctx: AXIContext): Unit = {
    dut.io.memory.b.valid.poke(true.B)
    dut.io.memory.b.bits.id.poke(id)
    dut.io.memory.b.bits.user.epoch.poke(ctx.epoch)
  }

  /**
   * 清除 MainMemory 的响应
   */
  def clearMemResp(dut: AXIArbiter): Unit = {
    dut.io.memory.r.valid.poke(false.B)
    dut.io.memory.b.valid.poke(false.B)
    dut.io.memory.ar.ready.poke(false.B)
    dut.io.memory.aw.ready.poke(false.B)
    dut.io.memory.w.ready.poke(false.B)
  }

  /**
   * 验证 MainMemory 的读请求
   * @param dut 被测模块
   * @param expectedAddr 期望的地址
   * @param expectedId 期望的事务 ID
   * @param expectedLen 期望的突发长度
   */
  def expectMemReadReq(dut: AXIArbiter, expectedAddr: Int, 
                       expectedId: AXIID.Type, expectedLen: Int): Unit = {
    dut.io.memory.ar.valid.expect(true.B)
    dut.io.memory.ar.bits.addr.expect(expectedAddr.U)
    dut.io.memory.ar.bits.id.expect(expectedId)
    dut.io.memory.ar.bits.len.expect(expectedLen.U)
  }

  /**
   * 验证 MainMemory 的写地址请求
   * @param dut 被测模块
   * @param expectedAddr 期望的地址
   * @param expectedId 期望的事务 ID
   * @param expectedLen 期望的突发长度
   */
  def expectMemWriteAddrReq(dut: AXIArbiter, expectedAddr: Int, 
                            expectedId: AXIID.Type, expectedLen: Int): Unit = {
    dut.io.memory.aw.valid.expect(true.B)
    dut.io.memory.aw.bits.addr.expect(expectedAddr.U)
    dut.io.memory.aw.bits.id.expect(expectedId)
    dut.io.memory.aw.bits.len.expect(expectedLen.U)
  }

  /**
   * 验证 MainMemory 的写数据请求
   * @param dut 被测模块
   * @param expectedData 期望的数据
   * @param expectedStrb 期望的字节掩码
   * @param expectedLast 期望的 last 标志
   */
  def expectMemWriteDataReq(dut: AXIArbiter, expectedData: BigInt, 
                            expectedStrb: BigInt, expectedLast: Boolean): Unit = {
    dut.io.memory.w.valid.expect(true.B)
    dut.io.memory.w.bits.data.expect(expectedData.U)
    dut.io.memory.w.bits.strb.expect(expectedStrb.U)
    dut.io.memory.w.bits.last.expect(expectedLast.B)
  }

  /**
   * 验证 I-Cache 的读响应
   * @param dut 被测模块
   * @param expectedData 期望的数据
   * @param expectedLast 期望的 last 标志
   * @param ctx 期望的 AXI 上下文
   */
  def expectICacheReadResp(dut: AXIArbiter, expectedData: BigInt, 
                            expectedLast: Boolean, ctx: AXIContext): Unit = {
    dut.io.i_cache.r.valid.expect(true.B)
    dut.io.i_cache.r.bits.data.expect(expectedData.U)
    dut.io.i_cache.r.bits.id.expect(AXIID.I_CACHE)
    dut.io.i_cache.r.bits.last.expect(expectedLast.B)
    dut.io.i_cache.r.bits.user.epoch.expect(ctx.epoch)
  }

  /**
   * 验证 D-Cache 的读响应
   * @param dut 被测模块
   * @param expectedData 期望的数据
   * @param expectedLast 期望的 last 标志
   * @param ctx 期望的 AXI 上下文
   */
  def expectDCacheReadResp(dut: AXIArbiter, expectedData: BigInt, 
                            expectedLast: Boolean, ctx: AXIContext): Unit = {
    dut.io.d_cache.r.valid.expect(true.B)
    dut.io.d_cache.r.bits.data.expect(expectedData.U)
    dut.io.d_cache.r.bits.id.expect(AXIID.D_CACHE)
    dut.io.d_cache.r.bits.last.expect(expectedLast.B)
    dut.io.d_cache.r.bits.user.epoch.expect(ctx.epoch)
  }

  /**
   * 验证 D-Cache 的写响应
   * @param dut 被测模块
   * @param ctx 期望的 AXI 上下文
   */
  def expectDCacheWriteResp(dut: AXIArbiter, ctx: AXIContext): Unit = {
    dut.io.d_cache.b.valid.expect(true.B)
    dut.io.d_cache.b.bits.id.expect(AXIID.D_CACHE)
    dut.io.d_cache.b.bits.user.epoch.expect(ctx.epoch)
  }

  /**
   * 验证 I-Cache 的 ready 信号
   * @param dut 被测模块
   * @param expectedReady 期望的 ready 值
   */
  def expectICacheReady(dut: AXIArbiter, expectedReady: Boolean): Unit = {
    dut.io.i_cache.ar.ready.expect(expectedReady.B)
  }

  /**
   * 验证 D-Cache 的 ready 信号
   * @param dut 被测模块
   * @param arExpectedReady AR 通道期望的 ready 值
   * @param awExpectedReady AW 通道期望的 ready 值
   * @param wExpectedReady W 通道期望的 ready 值
   */
  def expectDCacheReady(dut: AXIArbiter, arExpectedReady: Boolean, 
                        awExpectedReady: Boolean, wExpectedReady: Boolean): Unit = {
    dut.io.d_cache.ar.ready.expect(arExpectedReady.B)
    dut.io.d_cache.aw.ready.expect(awExpectedReady.B)
    dut.io.d_cache.w.ready.expect(wExpectedReady.B)
  }

  // ============================================================================
  // 1. 仲裁优先级测试
  // ============================================================================

  "AXIArbiter" should "在 I-Cache 单独请求时，I-Cache 赢得仲裁" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 I-Cache 赢得仲裁
      expectMemReadReq(dut, 0x1000, AXIID.I_CACHE, 0)
      expectICacheReady(dut, true)
      expectDCacheReady(dut, false, false, false)

      // MainMemory 接受请求
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 验证请求被接受
      expectICacheReady(dut, false)
      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "在 D-Cache 单独读请求时，D-Cache 赢得仲裁" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 D-Cache 读请求
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 D-Cache 赢得仲裁
      expectMemReadReq(dut, 0x2000, AXIID.D_CACHE, 0)
      expectDCacheReady(dut, true, false, false)
      expectICacheReady(dut, false)

      // MainMemory 接受请求
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 验证请求被接受
      expectDCacheReady(dut, false, false, false)
      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "在 D-Cache 单独写请求时，D-Cache 赢得仲裁" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 D-Cache 写请求
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 D-Cache 赢得仲裁
      expectMemWriteAddrReq(dut, 0x3000, AXIID.D_CACHE, 0)
      expectMemWriteDataReq(dut, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, true)
      expectDCacheReady(dut, false, true, true)
      expectICacheReady(dut, false)

      // MainMemory 接受请求
      dut.io.memory.aw.ready.poke(true.B)
      dut.io.memory.w.ready.poke(true.B)
      dut.clock.step()

      // 验证请求被接受
      expectDCacheReady(dut, false, false, false)
      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "在 I-Cache 和 D-Cache 同时请求时，D-Cache 优先" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 同时发送 I-Cache 和 D-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 D-Cache 赢得仲裁
      expectMemReadReq(dut, 0x2000, AXIID.D_CACHE, 0)
      expectDCacheReady(dut, true, false, false)
      expectICacheReady(dut, false)

      // MainMemory 接受请求
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 验证 D-Cache 请求被接受
      expectDCacheReady(dut, false, false, false)
      expectICacheReady(dut, false)

      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "在 I-Cache 和 D-Cache 写请求同时到达时，D-Cache 优先" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 同时发送 I-Cache 读请求和 D-Cache 写请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 D-Cache 赢得仲裁
      expectMemWriteAddrReq(dut, 0x3000, AXIID.D_CACHE, 0)
      expectMemWriteDataReq(dut, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, true)
      expectDCacheReady(dut, false, true, true)
      expectICacheReady(dut, false)

      // MainMemory 接受请求
      dut.io.memory.aw.ready.poke(true.B)
      dut.io.memory.w.ready.poke(true.B)
      dut.clock.step()

      // 验证 D-Cache 请求被接受
      expectDCacheReady(dut, false, false, false)
      expectICacheReady(dut, false)

      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  // ============================================================================
  // 2. 响应路由测试
  // ============================================================================

  "AXIArbiter" should "将 I-Cache 的读响应正确路由到 I-Cache" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()
      val testData = BigInt("123456789ABCDEF0", 16)

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的读响应（I-Cache ID）
      setMemReadResp(dut, AXIID.I_CACHE, testData, true, ctx)
      dut.clock.step()

      // 验证响应路由到 I-Cache
      expectICacheReadResp(dut, testData, true, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 验证响应被接受
      dut.io.i_cache.r.valid.expect(false.B)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "将 D-Cache 的读响应正确路由到 D-Cache" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()
      val testData = BigInt("FEDCBA9876543210", 16)

      // 发送 D-Cache 读请求
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的读响应（D-Cache ID）
      setMemReadResp(dut, AXIID.D_CACHE, testData, true, ctx)
      dut.clock.step()

      // 验证响应路由到 D-Cache
      expectDCacheReadResp(dut, testData, true, ctx)
      dut.io.d_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 验证响应被接受
      dut.io.d_cache.r.valid.expect(false.B)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "将 D-Cache 的写响应正确路由到 D-Cache" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 D-Cache 写请求
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      dut.io.memory.aw.ready.poke(true.B)
      dut.io.memory.w.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的写响应
      setMemWriteResp(dut, AXIID.D_CACHE, ctx)
      dut.clock.step()

      // 验证响应路由到 D-Cache
      expectDCacheWriteResp(dut, ctx)
      dut.io.d_cache.b.ready.poke(true.B)
      dut.clock.step()

      // 验证响应被接受
      dut.io.d_cache.b.valid.expect(false.B)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "根据事务 ID 正确分发多个读响应" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()
      val iCacheData = BigInt("1111111111111111", 16)
      val dCacheData = BigInt("2222222222222222", 16)

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 I-Cache 读响应
      setMemReadResp(dut, AXIID.I_CACHE, iCacheData, true, ctx)
      dut.clock.step()

      // 验证响应路由到 I-Cache
      expectICacheReadResp(dut, iCacheData, true, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 清除响应
      clearMemResp(dut)
      dut.clock.step()

      // 发送 D-Cache 读请求
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 D-Cache 读响应
      setMemReadResp(dut, AXIID.D_CACHE, dCacheData, true, ctx)
      dut.clock.step()

      // 验证响应路由到 D-Cache
      expectDCacheReadResp(dut, dCacheData, true, ctx)
      dut.io.d_cache.r.ready.poke(true.B)
      dut.clock.step()

      clearMemResp(dut)
    }
  }

  // ============================================================================
  // 3. isBusy 状态测试
  // ============================================================================

  "AXIArbiter" should "在发送请求后进入 Busy 状态" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // MainMemory 接受请求
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 验证进入 Busy 状态（不接受新请求）
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 尝试发送新请求，应该被拒绝
      sendICacheReadReq(dut, 0x1004, 0, ctx)
      dut.clock.step()

      // 验证 I-Cache ready 为 false
      expectICacheReady(dut, false)

      clearAllReqs(dut)
    }
  }

  "AXIArbiter" should "在读响应完成后退出 Busy 状态" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()
      val testData = BigInt("123456789ABCDEF0", 16)

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的读响应（last=true）
      setMemReadResp(dut, AXIID.I_CACHE, testData, true, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 清除响应
      clearMemResp(dut)
      dut.clock.step()

      // 验证退出 Busy 状态（可以接受新请求）
      sendICacheReadReq(dut, 0x1004, 0, ctx)
      dut.clock.step()

      // 验证 I-Cache ready 为 true
      expectICacheReady(dut, true)

      clearAllReqs(dut)
    }
  }

  "AXIArbiter" should "在写响应完成后退出 Busy 状态" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 D-Cache 写请求
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      dut.io.memory.aw.ready.poke(true.B)
      dut.io.memory.w.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的写响应
      setMemWriteResp(dut, AXIID.D_CACHE, ctx)
      dut.io.d_cache.b.ready.poke(true.B)
      dut.clock.step()

      // 清除响应
      clearMemResp(dut)
      dut.clock.step()

      // 验证退出 Busy 状态（可以接受新请求）
      sendICacheReadReq(dut, 0x1004, 0, ctx)
      dut.clock.step()

      // 验证 I-Cache ready 为 true
      expectICacheReady(dut, true)

      clearAllReqs(dut)
    }
  }

  "AXIArbiter" should "在 Busy 状态下拒绝所有新请求" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 在 Busy 状态下尝试发送多个请求
      sendICacheReadReq(dut, 0x1004, 0, ctx)
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      dut.clock.step()

      // 验证所有 ready 信号为 false
      expectICacheReady(dut, false)
      expectDCacheReady(dut, false, false, false)

      clearAllReqs(dut)
    }
  }

  // ============================================================================
  // 4. Valid/Ready 握手协议测试
  // ============================================================================

  "AXIArbiter" should "正确处理 I-Cache 读请求的握手协议" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 I-Cache 读请求（valid=true）
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 valid 为 true，ready 为 false（MainMemory 未准备好）
      dut.io.memory.ar.valid.expect(true.B)
      dut.io.memory.ar.ready.expect(false.B)

      // MainMemory 准备好（ready=true）
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 验证握手成功（fire）
      expectICacheReady(dut, false)

      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "正确处理 D-Cache 读请求的握手协议" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 D-Cache 读请求（valid=true）
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 valid 为 true，ready 为 false（MainMemory 未准备好）
      dut.io.memory.ar.valid.expect(true.B)
      dut.io.memory.ar.ready.expect(false.B)

      // MainMemory 准备好（ready=true）
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 验证握手成功（fire）
      expectDCacheReady(dut, false, false, false)

      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "正确处理 D-Cache 写请求的握手协议" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 D-Cache 写请求（valid=true）
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      clearMemResp(dut)
      dut.clock.step()

      // 验证 valid 为 true，ready 为 false（MainMemory 未准备好）
      dut.io.memory.aw.valid.expect(true.B)
      dut.io.memory.aw.ready.expect(false.B)
      dut.io.memory.w.valid.expect(true.B)
      dut.io.memory.w.ready.expect(false.B)

      // MainMemory 准备好（ready=true）
      dut.io.memory.aw.ready.poke(true.B)
      dut.io.memory.w.ready.poke(true.B)
      dut.clock.step()

      // 验证握手成功（fire）
      expectDCacheReady(dut, false, false, false)

      clearAllReqs(dut)
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "正确处理 I-Cache 读响应的握手协议" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()
      val testData = BigInt("123456789ABCDEF0", 16)

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的读响应（valid=true）
      setMemReadResp(dut, AXIID.I_CACHE, testData, true, ctx)
      dut.clock.step()

      // 验证 valid 为 true，ready 为 false（I-Cache 未准备好）
      dut.io.i_cache.r.valid.expect(true.B)
      dut.io.i_cache.r.ready.expect(false.B)

      // I-Cache 准备好（ready=true）
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 验证握手成功（fire）
      dut.io.i_cache.r.valid.expect(false.B)

      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "正确处理 D-Cache 读响应的握手协议" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()
      val testData = BigInt("FEDCBA9876543210", 16)

      // 发送 D-Cache 读请求
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的读响应（valid=true）
      setMemReadResp(dut, AXIID.D_CACHE, testData, true, ctx)
      dut.clock.step()

      // 验证 valid 为 true，ready 为 false（D-Cache 未准备好）
      dut.io.d_cache.r.valid.expect(true.B)
      dut.io.d_cache.r.ready.expect(false.B)

      // D-Cache 准备好（ready=true）
      dut.io.d_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 验证握手成功（fire）
      dut.io.d_cache.r.valid.expect(false.B)

      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "正确处理 D-Cache 写响应的握手协议" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 D-Cache 写请求
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      dut.io.memory.aw.ready.poke(true.B)
      dut.io.memory.w.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送 MainMemory 的写响应（valid=true）
      setMemWriteResp(dut, AXIID.D_CACHE, ctx)
      dut.clock.step()

      // 验证 valid 为 true，ready 为 false（D-Cache 未准备好）
      dut.io.d_cache.b.valid.expect(true.B)
      dut.io.d_cache.b.ready.expect(false.B)

      // D-Cache 准备好（ready=true）
      dut.io.d_cache.b.ready.poke(true.B)
      dut.clock.step()

      // 验证握手成功（fire）
      dut.io.d_cache.b.valid.expect(false.B)

      clearMemResp(dut)
    }
  }

  // ============================================================================
  // 5. 综合测试
  // ============================================================================

  "AXIArbiter" should "正确处理连续的 I-Cache 读请求" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 连续发送 3 个 I-Cache 读请求
      for (i <- 0 until 3) {
        val addr = 0x1000 + i * 0x10
        val testData = BigInt("123456789ABCDEF0", 16) + i

        // 发送请求
        sendICacheReadReq(dut, addr, 0, ctx)
        dut.io.memory.ar.ready.poke(true.B)
        dut.clock.step()

        // 清除请求
        clearAllReqs(dut)
        clearMemResp(dut)
        dut.clock.step()

        // 发送响应
        setMemReadResp(dut, AXIID.I_CACHE, testData, true, ctx)
        dut.io.i_cache.r.ready.poke(true.B)
        dut.clock.step()

        // 清除响应
        clearMemResp(dut)
        dut.clock.step()
      }
    }
  }

  "AXIArbiter" should "正确处理连续的 D-Cache 读请求" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 连续发送 3 个 D-Cache 读请求
      for (i <- 0 until 3) {
        val addr = 0x2000 + i * 0x10
        val testData = BigInt("FEDCBA9876543210", 16) + i

        // 发送请求
        sendDCacheReadReq(dut, addr, 0, ctx)
        dut.io.memory.ar.ready.poke(true.B)
        dut.clock.step()

        // 清除请求
        clearAllReqs(dut)
        clearMemResp(dut)
        dut.clock.step()

        // 发送响应
        setMemReadResp(dut, AXIID.D_CACHE, testData, true, ctx)
        dut.io.d_cache.r.ready.poke(true.B)
        dut.clock.step()

        // 清除响应
        clearMemResp(dut)
        dut.clock.step()
      }
    }
  }

  "AXIArbiter" should "正确处理混合的 I-Cache 和 D-Cache 请求" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 测试 1: I-Cache 读
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()
      setMemReadResp(dut, AXIID.I_CACHE, BigInt("1111111111111111", 16), true, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()
      clearMemResp(dut)
      dut.clock.step()

      // 测试 2: D-Cache 读
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()
      setMemReadResp(dut, AXIID.D_CACHE, BigInt("2222222222222222", 16), true, ctx)
      dut.io.d_cache.r.ready.poke(true.B)
      dut.clock.step()
      clearMemResp(dut)
      dut.clock.step()

      // 测试 3: D-Cache 写
      sendDCacheWriteReq(dut, 0x3000, BigInt("DEADBEEFCAFEBABE", 16), 0xFF, 0, ctx)
      dut.io.memory.aw.ready.poke(true.B)
      dut.io.memory.w.ready.poke(true.B)
      dut.clock.step()
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()
      setMemWriteResp(dut, AXIID.D_CACHE, ctx)
      dut.io.d_cache.b.ready.poke(true.B)
      dut.clock.step()
      clearMemResp(dut)
      dut.clock.step()

      // 测试 4: I-Cache 读
      sendICacheReadReq(dut, 0x1004, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()
      setMemReadResp(dut, AXIID.I_CACHE, BigInt("3333333333333333", 16), true, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "正确处理 I-Cache 和 D-Cache 交替请求" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 交替发送 I-Cache 和 D-Cache 读请求
      for (i <- 0 until 5) {
        val iCacheAddr = 0x1000 + i * 0x10
        val dCacheAddr = 0x2000 + i * 0x10
        val iCacheData = BigInt("1111111111111111", 16) + i
        val dCacheData = BigInt("2222222222222222", 16) + i

        // I-Cache 读
        sendICacheReadReq(dut, iCacheAddr, 0, ctx)
        dut.io.memory.ar.ready.poke(true.B)
        dut.clock.step()
        clearAllReqs(dut)
        clearMemResp(dut)
        dut.clock.step()
        setMemReadResp(dut, AXIID.I_CACHE, iCacheData, true, ctx)
        dut.io.i_cache.r.ready.poke(true.B)
        dut.clock.step()
        clearMemResp(dut)
        dut.clock.step()

        // D-Cache 读
        sendDCacheReadReq(dut, dCacheAddr, 0, ctx)
        dut.io.memory.ar.ready.poke(true.B)
        dut.clock.step()
        clearAllReqs(dut)
        clearMemResp(dut)
        dut.clock.step()
        setMemReadResp(dut, AXIID.D_CACHE, dCacheData, true, ctx)
        dut.io.d_cache.r.ready.poke(true.B)
        dut.clock.step()
        clearMemResp(dut)
        dut.clock.step()
      }
    }
  }

  "AXIArbiter" should "正确处理 D-Cache 优先级在繁忙场景下" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 场景：I-Cache 正在处理请求，D-Cache 发起新请求
      // I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 此时 I-Cache 请求已发送，但响应未到达
      // D-Cache 发起读请求
      sendDCacheReadReq(dut, 0x2000, 0, ctx)
      dut.clock.step()

      // 验证 D-Cache ready 为 false（Busy 状态）
      expectDCacheReady(dut, false, false, false)

      // 完成 I-Cache 请求
      setMemReadResp(dut, AXIID.I_CACHE, BigInt("1111111111111111", 16), true, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()
      clearMemResp(dut)
      dut.clock.step()

      // 现在 D-Cache 应该可以发送请求
      dut.clock.step()
      expectMemReadReq(dut, 0x2000, AXIID.D_CACHE, 0)
      expectDCacheReady(dut, true, false, false)

      // 完成 D-Cache 请求
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()
      setMemReadResp(dut, AXIID.D_CACHE, BigInt("2222222222222222", 16), true, ctx)
      dut.io.d_cache.r.ready.poke(true.B)
      dut.clock.step()
      clearMemResp(dut)
    }
  }

  "AXIArbiter" should "正确处理不同 epoch 值的请求和响应" in {
    test(new AXIArbiter) { dut =>
      // 测试不同的 epoch 值
      for (epoch <- 0 until 4) {
        val ctx = Wire(new AXIContext)
        ctx.epoch := epoch.U

        // I-Cache 读
        sendICacheReadReq(dut, 0x1000, 0, ctx)
        dut.io.memory.ar.ready.poke(true.B)
        dut.clock.step()
        clearAllReqs(dut)
        clearMemResp(dut)
        dut.clock.step()
        setMemReadResp(dut, AXIID.I_CACHE, BigInt("123456789ABCDEF0", 16), true, ctx)
        dut.io.i_cache.r.ready.poke(true.B)
        dut.clock.step()
        clearMemResp(dut)
        dut.clock.step()
      }
    }
  }

  "AXIArbiter" should "正确处理边界地址值" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 测试边界地址
      val boundaryAddrs = Seq(0x0, 0x4, 0x1000, 0xFFFFFFF0, 0xFFFFFFFC)

      for (addr <- boundaryAddrs) {
        // I-Cache 读
        sendICacheReadReq(dut, addr, 0, ctx)
        dut.io.memory.ar.ready.poke(true.B)
        dut.clock.step()
        clearAllReqs(dut)
        clearMemResp(dut)
        dut.clock.step()
        setMemReadResp(dut, AXIID.I_CACHE, BigInt("123456789ABCDEF0", 16), true, ctx)
        dut.io.i_cache.r.ready.poke(true.B)
        dut.clock.step()
        clearMemResp(dut)
        dut.clock.step()
      }
    }
  }

  "AXIArbiter" should "正确处理无请求时的空闲状态" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 无请求，验证所有 ready 信号为 false
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      expectICacheReady(dut, false)
      expectDCacheReady(dut, false, false, false)

      // 验证 MainMemory 的请求信号为 false
      dut.io.memory.ar.valid.expect(false.B)
      dut.io.memory.aw.valid.expect(false.B)
      dut.io.memory.w.valid.expect(false.B)
    }
  }

  "AXIArbiter" should "正确处理 last 标志为 false 的情况" in {
    test(new AXIArbiter) { dut =>
      val ctx = createDefaultAXIContext()

      // 发送 I-Cache 读请求
      sendICacheReadReq(dut, 0x1000, 0, ctx)
      dut.io.memory.ar.ready.poke(true.B)
      dut.clock.step()

      // 清除请求
      clearAllReqs(dut)
      clearMemResp(dut)
      dut.clock.step()

      // 发送第一个读响应（last=false）
      setMemReadResp(dut, AXIID.I_CACHE, BigInt("1111111111111111", 16), false, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 清除响应
      clearMemResp(dut)
      dut.clock.step()

      // 验证仍然在 Busy 状态（不接受新请求）
      sendICacheReadReq(dut, 0x1004, 0, ctx)
      dut.clock.step()
      expectICacheReady(dut, false)
      clearAllReqs(dut)

      // 发送最后一个读响应（last=true）
      setMemReadResp(dut, AXIID.I_CACHE, BigInt("2222222222222222", 16), true, ctx)
      dut.io.i_cache.r.ready.poke(true.B)
      dut.clock.step()

      // 清除响应
      clearMemResp(dut)
      dut.clock.step()

      // 验证退出 Busy 状态
      sendICacheReadReq(dut, 0x1004, 0, ctx)
      dut.clock.step()
      expectICacheReady(dut, true)
      clearAllReqs(dut)
    }
  }
}
