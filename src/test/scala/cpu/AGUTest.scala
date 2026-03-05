package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * AGU (地址生成单元) 模块测试
 * 
 * 测试 AGU 的功能，包括：
 * - 地址计算：PA = BaseAddr + Offset
 * - 对齐检查：BYTE/HALF/WORD 访问的对齐要求
 * - 异常生成：对齐异常和访问异常的正确生成
 * - 异常优先级：对齐异常优先于访问异常
 * - 地址溢出：验证地址溢出情况的处理
 */
class AGUTest extends AnyFlatSpec with ChiselScalatestTester {

  // ============================================================================
  // 辅助函数
  // ============================================================================

  /**
   * 设置默认 PMP 配置（全部为 OFF 模式）
   */
  def setDefaultPMPConfig(dut: AGU): Unit = {
    for (i <- 0 until 16) {
      dut.io.pmpcfg(i).poke(0.U)
      dut.io.pmpaddr(i).poke(0.U)
    }
  }

  /**
   * 设置 PMP 配置寄存器
   * @param idx PMP 条目索引
   * @param cfg 配置值 (8位: bit[7]=L, bit[6:3]=A, bit[2]=X, bit[1]=W, bit[0]=R)
   * @param addr 地址值 (32位)
   */
  def setPMPEntry(dut: AGU, idx: Int, cfg: Int, addr: Int): Unit = {
    dut.io.pmpcfg(idx).poke(cfg.U)
    dut.io.pmpaddr(idx).poke(addr.U)
  }

  /**
   * 发送 AGU 请求
   * @param baseAddr 基地址
   * @param offset 偏移量
   * @param memWidth 访存位宽 (BYTE/HALF/WORD)
   * @param memOp 访存类型 (LOAD/STORE)
   * @param privMode 特权级 (U/S/M)
   * @param ctx 内存上下文
   */
  def sendAGUReq(dut: AGU, baseAddr: Int, offset: Int, memWidth: LSUWidth.Type, 
                 memOp: LSUOp.Type, privMode: PrivMode.Type, 
                 ctx: MemContext): Unit = {
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.baseAddr.poke(baseAddr.U)
    dut.io.req.bits.offset.poke(offset.U)
    dut.io.req.bits.memWidth.poke(memWidth)
    dut.io.req.bits.memOp.poke(memOp)
    dut.io.req.bits.privMode.poke(privMode)
    dut.io.req.bits.ctx.epoch.poke(ctx.epoch)
    dut.io.req.bits.ctx.branchMask.poke(ctx.branchMask)
    dut.io.req.bits.ctx.robId.poke(ctx.robId)
  }

  /**
   * 清除 AGU 请求
   */
  def clearAGUReq(dut: AGU): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.baseAddr.poke(0.U)
    dut.io.req.bits.offset.poke(0.U)
    dut.io.req.bits.memWidth.poke(LSUWidth.BYTE)
    dut.io.req.bits.memOp.poke(LSUOp.NOP)
    dut.io.req.bits.privMode.poke(PrivMode.M)
    dut.io.req.bits.ctx.epoch.poke(0.U)
    dut.io.req.bits.ctx.branchMask.poke(0.U)
    dut.io.req.bits.ctx.robId.poke(0.U)
  }

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
   * 验证无异常
   */
  def expectNoException(dut: AGU): Unit = {
    dut.io.resp.bits.exception.valid.expect(false.B)
    dut.io.resp.bits.exception.cause.expect(0.U)
    dut.io.resp.bits.exception.tval.expect(0.U)
  }

  /**
   * 验证异常
   * @param cause 异常原因
   * @param tval 异常地址
   */
  def expectException(dut: AGU, cause: Int, tval: Int): Unit = {
    dut.io.resp.bits.exception.valid.expect(true.B)
    dut.io.resp.bits.exception.cause.expect(cause.U)
    dut.io.resp.bits.exception.tval.expect(tval.U)
  }

  /**
   * 验证物理地址
   * @param expectedPa 期望的物理地址
   */
  def expectPA(dut: AGU, expectedPa: Int): Unit = {
    dut.io.resp.bits.pa.expect(expectedPa.U)
  }

  /**
   * 验证响应有效
   */
  def expectRespValid(dut: AGU, valid: Boolean): Unit = {
    dut.io.resp.valid.expect(valid.B)
  }

  /**
   * 验证上下文
   * @param ctx 期望的上下文
   */
  def expectContext(dut: AGU, ctx: MemContext): Unit = {
    dut.io.resp.bits.ctx.epoch.expect(ctx.epoch)
    dut.io.resp.bits.ctx.branchMask.expect(ctx.branchMask)
    dut.io.resp.bits.ctx.robId.expect(ctx.robId)
  }

  // ============================================================================
  // 1. 地址计算测试
  // ============================================================================

  "AGU" should "正确计算物理地址 PA = BaseAddr + Offset" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试基本地址计算
      val testCases = Seq(
        (0x1000, 0x10, 0x1010),
        (0x0, 0x100, 0x100),
        (0xFFFF, 0x1, 0x10000),
        (0x80000000, 0x1000, 0x80001000),
        (0x1000, 0xFFFFFFF0, 0xFF0) // 地址溢出（32 位截断）
      )

      for ((baseAddr, offset, expectedPa) <- testCases) {
        sendAGUReq(dut, baseAddr, offset, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, expectedPa)
        expectNoException(dut)
        expectContext(dut, ctx)

        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  "AGU" should "正确计算负偏移量的地址（使用补码）" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试负偏移量（使用 32 位补码表示）
      val testCases = Seq(
        (0x2000, 0xFFFFFFF0, 0x1FF0),  // -16
        (0x1000, 0xFFFFFFFC, 0xFFC),   // -4
        (0x8000, 0xFFFFFF00, 0x7F00)   // -256
      )

      for ((baseAddr, offset, expectedPa) <- testCases) {
        sendAGUReq(dut, baseAddr, offset, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, expectedPa)
        expectNoException(dut)

        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  // ============================================================================
  // 2. 对齐检查测试
  // ============================================================================

  "AGU" should "BYTE 访问无需对齐检查" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试 BYTE 访问，所有地址都应该通过
      for (addr <- Seq(0x0, 0x1, 0x2, 0x3, 0x1000, 0x1001, 0x1002, 0x1003, 0xFFFF, 0x10000)) {
        sendAGUReq(dut, 0x1000, addr - 0x1000, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, addr)
        expectNoException(dut)

        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  "AGU" should "HALF 访问需要 2 字节对齐（最低 1 位为 0）" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试对齐的 HALF 访问
      for (addr <- Seq(0x0, 0x2, 0x4, 0x1000, 0x1002, 0x1004)) {
        sendAGUReq(dut, 0x1000, addr - 0x1000, LSUWidth.HALF, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, addr)
        expectNoException(dut)

        clearAGUReq(dut)
        dut.clock.step()
      }

      // 测试未对齐的 HALF 访问
      for (addr <- Seq(0x1, 0x3, 0x5, 0x1001, 0x1003, 0x1005)) {
        sendAGUReq(dut, 0x1000, addr - 0x1000, LSUWidth.HALF, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, addr)
        expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, addr)

        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  "AGU" should "WORD 访问需要 4 字节对齐（最低 2 位为 0）" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试对齐的 WORD 访问
      for (addr <- Seq(0x0, 0x4, 0x8, 0x1000, 0x1004, 0x1008)) {
        sendAGUReq(dut, 0x1000, addr - 0x1000, LSUWidth.WORD, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, addr)
        expectNoException(dut)

        clearAGUReq(dut)
        dut.clock.step()
      }

      // 测试未对齐的 WORD 访问
      for (addr <- Seq(0x1, 0x2, 0x3, 0x5, 0x6, 0x7, 0x1001, 0x1002, 0x1003)) {
        sendAGUReq(dut, 0x1000, addr - 0x1000, LSUWidth.WORD, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, addr)
        expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, addr)

        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  "AGU" should "STORE 操作的对齐检查与 LOAD 相同" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试 HALF STORE 未对齐
      sendAGUReq(dut, 0x1000, 0x1, LSUWidth.HALF, LSUOp.STORE, PrivMode.M, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ADDRESS_MISALIGNED.litValue.toInt, 0x1001)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 WORD STORE 未对齐
      sendAGUReq(dut, 0x1000, 0x2, LSUWidth.WORD, LSUOp.STORE, PrivMode.M, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ADDRESS_MISALIGNED.litValue.toInt, 0x1002)
    }
  }

  // ============================================================================
  // 3. 异常生成测试
  // ============================================================================

  "AGU" should "正确生成 LOAD_ADDRESS_MISALIGNED 异常" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // HALF 访问未对齐
      sendAGUReq(dut, 0x1000, 0x1, LSUWidth.HALF, LSUOp.LOAD, PrivMode.M, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, 0x1001)
      clearAGUReq(dut)
      dut.clock.step()

      // WORD 访问未对齐
      sendAGUReq(dut, 0x2000, 0x1, LSUWidth.WORD, LSUOp.LOAD, PrivMode.M, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, 0x2001)
    }
  }

  "AGU" should "正确生成 STORE_ADDRESS_MISALIGNED 异常" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // HALF 访问未对齐
      sendAGUReq(dut, 0x1000, 0x1, LSUWidth.HALF, LSUOp.STORE, PrivMode.M, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ADDRESS_MISALIGNED.litValue.toInt, 0x1001)
      clearAGUReq(dut)
      dut.clock.step()

      // WORD 访问未对齐
      sendAGUReq(dut, 0x2000, 0x1, LSUWidth.WORD, LSUOp.STORE, PrivMode.M, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ADDRESS_MISALIGNED.litValue.toInt, 0x2001)
    }
  }

  "AGU" should "正确生成 LOAD_ACCESS_FAULT 异常" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 条目，使 U-mode 访问失败
      // entry 0: NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)  // A=2, 无权限

      // U-mode LOAD 访问应该失败
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
    }
  }

  "AGU" should "正确生成 STORE_ACCESS_FAULT 异常" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 条目，使 U-mode 访问失败
      // entry 0: NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)  // A=2, 无权限

      // U-mode STORE 访问应该失败
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.STORE, PrivMode.U, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x1000)
    }
  }

  "AGU" should "异常 tval 应该包含正确的地址" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试对齐异常的 tval
      for (addr <- Seq(0x1001, 0x1002, 0x1003)) {
        sendAGUReq(dut, 0x1000, addr - 0x1000, LSUWidth.WORD, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()
        expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, addr)
        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  // ============================================================================
  // 4. 异常优先级测试
  // ============================================================================

  "AGU" should "对齐异常优先于访问异常（LOAD 操作）" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 条目，使 U-mode 访问失败
      // entry 0: NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)  // A=2, 无权限

      // 未对齐访问，应该生成对齐异常而不是访问异常
      sendAGUReq(dut, 0x1000, 0x1, LSUWidth.HALF, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      // 应该生成对齐异常，而不是访问异常
      expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, 0x1001)
    }
  }

  "AGU" should "对齐异常优先于访问异常（STORE 操作）" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 条目，使 U-mode 访问失败
      // entry 0: NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)  // A=2, 无权限

      // 未对齐访问，应该生成对齐异常而不是访问异常
      sendAGUReq(dut, 0x1000, 0x2, LSUWidth.WORD, LSUOp.STORE, PrivMode.U, ctx)
      dut.clock.step()
      // 应该生成对齐异常，而不是访问异常
      expectException(dut, ExceptionCause.STORE_ADDRESS_MISALIGNED.litValue.toInt, 0x1002)
    }
  }

  "AGU" should "对齐检查通过后才进行 PMP 检查" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 条目，使 U-mode 访问失败
      // entry 0: NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)  // A=2, 无权限

      // 对齐的访问，应该生成访问异常
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      clearAGUReq(dut)
      dut.clock.step()

      // 未对齐的访问，应该生成对齐异常
      sendAGUReq(dut, 0x1000, 0x1, LSUWidth.HALF, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, 0x1001)
    }
  }

  // ============================================================================
  // 5. 地址溢出测试
  // ============================================================================

  "AGU" should "正确处理地址溢出情况" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试地址溢出（32 位溢出）
      val testCases = Seq(
        (0xFFFFFFF0, 0x20, 0x10),  // 溢出（32 位截断）
        (0xFFFFFFFC, 0x10, 0xC),   // 溢出（32 位截断）
        (0x80000000, 0x80000000, 0x0)  // 溢出（32 位截断）
      )

      for ((baseAddr, offset, expectedPa) <- testCases) {
        sendAGUReq(dut, baseAddr, offset, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectRespValid(dut, true)
        expectPA(dut, expectedPa.toInt)
        expectNoException(dut)

        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  "AGU" should "地址溢出时仍然进行对齐检查" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 地址溢出但未对齐
      sendAGUReq(dut, 0xFFFFFFFC, 0x4, LSUWidth.HALF, LSUOp.LOAD, PrivMode.M, ctx)
      dut.clock.step()
      // PA = 0x100000000 & 0xFFFFFFFF = 0x0，未对齐
      expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, 0x0)
    }
  }

  // ============================================================================
  // 6. 上下文传递测试
  // ============================================================================

  "AGU" should "正确传递内存上下文" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)

      // 测试不同的上下文值
      val testCases = Seq(
        (0.U, 0.U, 0.U),
        (1.U, 1.U, 1.U),
        (2.U, 2.U, 2.U),
        (3.U, 3.U, 3.U),
        (0.U, 0xF.U, 0x1F.U)
      )

      for ((epoch, branchMask, robId) <- testCases) {
        val ctx = Wire(new MemContext)
        ctx.epoch := epoch
        ctx.branchMask := branchMask
        ctx.robId := robId

        sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()

        expectContext(dut, ctx)

        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  // ============================================================================
  // 7. 无效请求测试
  // ============================================================================

  "AGU" should "正确处理无效请求 (req.valid = false)" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)

      // 无效请求不应该生成有效响应
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.baseAddr.poke(0x1000.U)
      dut.io.req.bits.offset.poke(0x0.U)
      dut.io.req.bits.memWidth.poke(LSUWidth.BYTE)
      dut.io.req.bits.memOp.poke(LSUOp.LOAD)
      dut.io.req.bits.privMode.poke(PrivMode.M)
      dut.io.req.bits.ctx.epoch.poke(0.U)
      dut.io.req.bits.ctx.branchMask.poke(0.U)
      dut.io.req.bits.ctx.robId.poke(0.U)
      dut.clock.step()

      expectRespValid(dut, false)
    }
  }

  // ============================================================================
  // 8. 综合测试
  // ============================================================================

  "AGU" should "正确处理复杂的测试场景" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 配置
      // entry 0: NA4, [0x1000, 0x1004), 只读
      // entry 1: NA4, [0x2000, 0x2004), 读写
      setPMPEntry(dut, 0, 0x11, 0x400)  // A=2, R=1
      setPMPEntry(dut, 1, 0x13, 0x800)  // A=2, R=1, W=1

      // 测试 1: M-mode 访问，对齐，应该成功
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.WORD, LSUOp.LOAD, PrivMode.M, ctx)
      dut.clock.step()
      expectRespValid(dut, true)
      expectPA(dut, 0x1000)
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 2: U-mode 读访问，对齐，有权限，应该成功
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectRespValid(dut, true)
      expectPA(dut, 0x1000)
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 3: U-mode 写访问，对齐，无权限，应该失败
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.STORE, PrivMode.U, ctx)
      dut.clock.step()
      expectRespValid(dut, true)
      expectPA(dut, 0x1000)
      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x1000)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 4: U-mode 访问，未对齐，应该生成对齐异常
      sendAGUReq(dut, 0x2000, 0x1, LSUWidth.HALF, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectRespValid(dut, true)
      expectPA(dut, 0x2001)
      expectException(dut, ExceptionCause.LOAD_ADDRESS_MISALIGNED.litValue.toInt, 0x2001)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 5: U-mode 读写访问，对齐，有权限，应该成功
      sendAGUReq(dut, 0x2000, 0x0, LSUWidth.WORD, LSUOp.STORE, PrivMode.U, ctx)
      dut.clock.step()
      expectRespValid(dut, true)
      expectPA(dut, 0x2000)
      expectNoException(dut)
    }
  }

  "AGU" should "正确处理所有访存位宽和操作类型" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 配置，使 U-mode 访问成功
      // entry 0: TOR, [0, 0x2000), 读写
      setPMPEntry(dut, 0, 0x0B, 0x800)  // A=1, R=1, W=1

      // 测试 BYTE LOAD
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 BYTE STORE
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.BYTE, LSUOp.STORE, PrivMode.U, ctx)
      dut.clock.step()
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 HALF LOAD（对齐）
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.HALF, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 HALF STORE（对齐）
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.HALF, LSUOp.STORE, PrivMode.U, ctx)
      dut.clock.step()
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 WORD LOAD（对齐）
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.WORD, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 WORD STORE（对齐）
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.WORD, LSUOp.STORE, PrivMode.U, ctx)
      dut.clock.step()
      expectNoException(dut)
    }
  }

  "AGU" should "正确处理所有特权级" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 设置 PMP 配置，使所有特权级访问成功
      // entry 0: TOR, [0, 0x2000), 读写
      setPMPEntry(dut, 0, 0x0B, 0x800)  // A=1, R=1, W=1

      // 测试 U-mode
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.WORD, LSUOp.LOAD, PrivMode.U, ctx)
      dut.clock.step()
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 S-mode
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.WORD, LSUOp.LOAD, PrivMode.S, ctx)
      dut.clock.step()
      expectNoException(dut)
      clearAGUReq(dut)
      dut.clock.step()

      // 测试 M-mode
      sendAGUReq(dut, 0x1000, 0x0, LSUWidth.WORD, LSUOp.LOAD, PrivMode.M, ctx)
      dut.clock.step()
      expectNoException(dut)
    }
  }

  "AGU" should "正确处理边界地址" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试边界地址
      val boundaryCases = Seq(
        (0x0, 0x0, LSUWidth.BYTE),
        (0x0, 0x0, LSUWidth.HALF),
        (0x0, 0x0, LSUWidth.WORD),
        (0xFFFFFFFC, 0x0, LSUWidth.WORD),
        (0xFFFFFFF0, 0x0, LSUWidth.WORD),
        (0x10000, 0x0, LSUWidth.WORD)
      )

      for ((baseAddr, offset, memWidth) <- boundaryCases) {
        sendAGUReq(dut, baseAddr, offset, memWidth, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()
        expectPA(dut, baseAddr + offset)
        // 边界地址可能未对齐，所以不检查异常
        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }

  "AGU" should "正确处理连续的地址计算" in {
    test(new AGU) { dut =>
      setDefaultPMPConfig(dut)
      val ctx = createDefaultContext()

      // 测试连续的地址计算
      for (i <- 0 until 10) {
        val baseAddr = 0x1000 + i * 4
        sendAGUReq(dut, baseAddr, 0x0, LSUWidth.WORD, LSUOp.LOAD, PrivMode.M, ctx)
        dut.clock.step()
        expectPA(dut, baseAddr)
        expectNoException(dut)
        clearAGUReq(dut)
        dut.clock.step()
      }
    }
  }
}
