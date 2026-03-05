package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * PMPChecker 模块测试
 * 
 * 测试物理内存保护检查器的功能，包括：
 * - 四种地址匹配模式（OFF, TOR, NA4, NAPOT）
 * - 优先级仲裁（小编号优先）
 * - 权限检查（R/W/X）
 * - 默认策略（不同特权级）
 * - 异常生成
 */
class PMPCheckerTest extends AnyFlatSpec with ChiselScalatestTester {

  // ============================================================================
  // 辅助函数
  // ============================================================================

  /**
   * 设置默认 PMP 配置（全部为 OFF 模式）
   */
  def setDefaultPMPConfig(dut: PMPChecker): Unit = {
    for (i <- 0 until 16) {
      dut.io.pmpcfg(i).poke(0.U)
    }
    for (i <- 0 until 64) {
      dut.io.pmpaddr(i).poke(0.U)
    }
  }

  /**
   * 设置 PMP 配置寄存器
   * @param cfg 配置值 (8位: bit[7]=L, bit[6:3]=A, bit[2]=X, bit[1]=W, bit[0]=R)
   * @param addr 地址值 (32位)
   */
  def setPMPEntry(dut: PMPChecker, idx: Int, cfg: Int, addr: Int): Unit = {
    val index = idx / 4
    val cfgByteOffset = (idx % 4) * 8
    dut.io.pmpcfg(index).poke((cfg << cfgByteOffset).U)
    dut.io.pmpaddr(idx).poke(addr.U)
  }

  /**
   * 发送 PMP 检查请求
   * @param addr 物理地址
   * @param memOp 访存类型 (LOAD/STORE/NOP)
   * @param privMode 特权级 (U/S/M)
   */
  def sendPMPReq(dut: PMPChecker, addr: Long, memOp: LSUOp.Type, privMode: PrivMode.Type): Unit = {
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.addr.poke(addr.U)
    dut.io.req.bits.memOp.poke(memOp)
    dut.io.req.bits.privMode.poke(privMode)
  }

  /**
   * 清除 PMP 检查请求
   */
  def clearPMPReq(dut: PMPChecker): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.addr.poke(0.U)
    dut.io.req.bits.memOp.poke(LSUOp.NOP)
    dut.io.req.bits.privMode.poke(PrivMode.M)
  }

  /**
   * 验证无异常
   */
  def expectNoException(dut: PMPChecker): Unit = {
    dut.io.exception.valid.expect(false.B)
    dut.io.exception.cause.expect(0.U)
    dut.io.exception.tval.expect(0.U)
  }

  /**
   * 验证异常
   * @param cause 异常原因
   * @param tval 异常地址
   */
  def expectException(dut: PMPChecker, cause: Int, tval: Int): Unit = {
    dut.io.exception.valid.expect(true.B)
    dut.io.exception.cause.expect(cause.U)
    dut.io.exception.tval.expect(tval.U)
  }

  /**
   * 验证 PMP 匹配状态
   */
  def expectPMPMatch(dut: PMPChecker, matchExpected: Boolean): Unit = {
    dut.io.pmpMatch.expect(matchExpected.B)
  }

  // ============================================================================
  // 1. OFF 模式测试
  // ============================================================================

  "PMPChecker" should "OFF 模式不匹配任何地址" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 OFF 模式 (A=0)
      setPMPEntry(dut, 0, 0x00, 0x400)

      // 测试多个地址，都不应该匹配
      for (addr <- Seq(0x0, 0x1000, 0x2000, 0x10000000)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, false)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  "PMPChecker" should "OFF 模式下 M-mode 默认允许访问" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 OFF 模式
      setPMPEntry(dut, 0, 0x00, 0x1000)

      // M-mode 访问应该允许
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.M)
      dut.clock.step()

      expectNoException(dut)
      expectPMPMatch(dut, false)
    }
  }

  "PMPChecker" should "OFF 模式下 U-mode 默认拒绝访问" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 OFF 模式
      setPMPEntry(dut, 0, 0x00, 0x1000)

      // U-mode 访问应该拒绝
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()

      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, false)
    }
  }

  // ============================================================================
  // 2. TOR 模式测试
  // ============================================================================

  "PMPChecker" should "TOR 模式正确匹配地址范围 [0, pmpaddr[i]) (entry 0)" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 TOR 模式 (A=1)，匹配 [0, 0x4000)
      setPMPEntry(dut, 0, 0x09, 0x1000)  // A=1 (bit[4:3]=1), R=1 (bit[0]=1)

      // 在范围内的地址应该匹配
      for (addr <- Seq(0x0, 0x1000, 0x2000, 0x3FFC)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)

        expectNoException(dut)
        expectPMPMatch(dut, true)

        dut.clock.step()
        clearPMPReq(dut)
      }

      // 超出范围的地址不应该匹配
      for (addr <- Seq(0x4000, 0x4004, 0x8000)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, false)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  "PMPChecker" should "TOR 模式正确匹配地址范围 [pmpaddr[i-1], pmpaddr[i]) (entry > 0)" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 1 为 TOR 模式，匹配 [0x1000, 0x3000)
      setPMPEntry(dut, 0, 0x08, 0x400)   // entry 0: [0, 0x1000)
      setPMPEntry(dut, 1, 0x09, 0xC00)   // entry 1: [0x1000, 0x3000)

      // 在范围内的地址应该匹配
      for (addr <- Seq(0x1000, 0x2000, 0x2FFC)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectNoException(dut)
        expectPMPMatch(dut, true)

        clearPMPReq(dut)
        dut.clock.step()
      }

      // 超出范围的地址不应该匹配
      for (addr <- Seq(0x0, 0xFFC, 0x3000, 0x3004)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, false)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  "PMPChecker" should "TOR 模式边界测试" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 TOR 模式，匹配 [0, 0x1000)
      setPMPEntry(dut, 0, 0x09, 0x400)

      // 测试边界
      sendPMPReq(dut, 0xFFC, LSUOp.LOAD, PrivMode.U)  // 最后一个有效地址
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)  // 第一个无效地址
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, false)
    }
  }

  // ============================================================================
  // 3. NA4 模式测试
  // ============================================================================

  "PMPChecker" should "NA4 模式正确匹配单个 4 字节区域" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式 (A=2)，匹配 [0x1000, 0x1004)
      setPMPEntry(dut, 0, 0x11, 0x400)  // A=2 (bit[4:3]=2), R=1 (bit[0]=1)

      // 在范围内的地址应该匹配
      for (addr <- Seq(0x1000, 0x1001, 0x1002, 0x1003)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectNoException(dut)
        expectPMPMatch(dut, true)

        clearPMPReq(dut)
        dut.clock.step()
      }

      // 超出范围的地址不应该匹配
      for (addr <- Seq(0xFFC, 0x1004, 0x1008)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, false)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  "PMPChecker" should "NA4 模式边界测试" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，匹配 [0x2000, 0x2004)
      setPMPEntry(dut, 0, 0x11, 0x800)

      // 测试边界
      sendPMPReq(dut, 0x2000, LSUOp.LOAD, PrivMode.U)  // 第一个有效地址
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      sendPMPReq(dut, 0x2003, LSUOp.LOAD, PrivMode.U)  // 最后一个有效地址
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      sendPMPReq(dut, 0x2004, LSUOp.LOAD, PrivMode.U)  // 第一个无效地址
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x2004)
      expectPMPMatch(dut, false)
    }
  }

  // ============================================================================
  // 4. NAPOT 模式测试
  // ============================================================================

  "PMPChecker" should "NAPOT 模式正确匹配 8 字节区域" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NAPOT 模式 (A=3)，匹配 8 字节区域
      // pmpaddr 格式：...0001 -> 匹配 16 字节 (2^4)
      setPMPEntry(dut, 0, 0x19, 0x401)  // A=3 (bit[4:3]=3), R=1 (bit[0]=1), pmpaddr=0x401

      // 在范围内的地址应该匹配 [0x1000, 0x1008)
      for (addr <- Seq(0x1000, 0x1004, 0x1007)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectNoException(dut)
        expectPMPMatch(dut, true)

        clearPMPReq(dut)
        dut.clock.step()
      }

      // 超出范围的地址不应该匹配
      for (addr <- Seq(0xFFC, 0x1010)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, false)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  "PMPChecker" should "NAPOT 模式正确匹配 32 字节区域" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NAPOT 模式，匹配 32 字节区域
      // pmpaddr 格式：...0011 -> 匹配 32 字节 (2^5)
      setPMPEntry(dut, 0, 0x19, 0x403)  // A=3, R=1, pmpaddr=0x403

      // 在范围内的地址应该匹配 [0x1000, 0x1020)
      for (addr <- Seq(0x1000, 0x1010, 0x101C)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectNoException(dut)
        expectPMPMatch(dut, true)

        clearPMPReq(dut)
        dut.clock.step()
      }

      // 超出范围的地址不应该匹配
      for (addr <- Seq(0xFFC, 0x1020, 0x1024)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, false)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  "PMPChecker" should "NAPOT 模式正确匹配 64 字节区域" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NAPOT 模式，匹配 64 字节区域
      // pmpaddr 格式：...0111 -> 匹配 64 字节 (2^6)
      setPMPEntry(dut, 0, 0x19, 0x407)  // A=3, R=1, pmpaddr=0x407

      // 在范围内的地址应该匹配 [0x1000, 0x1040)
      for (addr <- Seq(0x1000, 0x1020, 0x103C)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectNoException(dut)
        expectPMPMatch(dut, true)

        clearPMPReq(dut)
        dut.clock.step()
      }

      // 超出范围的地址不应该匹配
      for (addr <- Seq(0xFFC, 0x1040, 0x1044)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, false)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  "PMPChecker" should "NAPOT 模式边界测试" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NAPOT 模式，匹配 8 字节区域
      setPMPEntry(dut, 0, 0x19, 0x400)  // 8 字节

      // 测试边界
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)  // 第一个有效地址
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      sendPMPReq(dut, 0x1007, LSUOp.LOAD, PrivMode.U)  // 最后一个有效地址
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      sendPMPReq(dut, 0x1008, LSUOp.LOAD, PrivMode.U)  // 第一个无效地址
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1008)
      expectPMPMatch(dut, false)
    }
  }

  // ============================================================================
  // 5. 优先级测试
  // ============================================================================

  "PMPChecker" should "小编号优先策略正确工作" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置多个条目，使用 NA4 模式
      // entry 0: [0x1000, 0x1004), 只读
      // entry 5: [0x1000, 0x1004), 读写
      setPMPEntry(dut, 0, 0x11, 0x400)  // A=2, R=1
      setPMPEntry(dut, 5, 0x13, 0x400)  // A=2, R=1, W=1

      // 地址 0x1000 应该匹配 entry 0（小编号优先）
      // 但 entry 0 没有写权限，所以写操作应该失败
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()

      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "小编号优先策略正确工作（读操作）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置多个条目
      // entry 0: [0x1000, 0x1004), 只读
      // entry 5: [0x1000, 0x1004), 无权限
      setPMPEntry(dut, 0, 0x11, 0x400)  // A=2, R=1
      setPMPEntry(dut, 5, 0x10, 0x400)  // A=2, 无权限

      // 地址 0x1000 应该匹配 entry 0（小编号优先）
      // entry 0 有读权限，所以读操作应该成功
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()

      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "小编号优先策略正确工作（不同地址）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置多个条目，使用 NA4 模式
      // entry 0: [0x1000, 0x1004), 只读
      // entry 5: [0x1004, 0x1008), 读写
      setPMPEntry(dut, 0, 0x11, 0x400)  // A=2, R=1
      setPMPEntry(dut, 5, 0x13, 0x401)  // A=2, R=1, W=1

      // 地址 0x1000 应该匹配 entry 0
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 地址 0x1004 应该匹配 entry 1
      sendPMPReq(dut, 0x1004, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }

  // ============================================================================
  // 6. 权限检查测试
  // ============================================================================

  "PMPChecker" should "正确检查读权限 (R)" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，只有读权限
      setPMPEntry(dut, 0, 0x11, 0x400)  // A=2, R=1

      // 读操作应该成功
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 写操作应该失败
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "正确检查写权限 (W)" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，只有写权限
      setPMPEntry(dut, 0, 0x12, 0x400)  // A=2, W=1

      // 写操作应该成功
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 读操作应该失败
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "正确检查读写权限 (RW)" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，有读写权限
      setPMPEntry(dut, 0, 0x13, 0x400)  // A=2, R=1, W=1

      // 读操作应该成功
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 写操作也应该成功
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "正确检查无权限" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)  // A=2, 无权限

      // 读操作应该失败
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 写操作也应该失败
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
    }
  }

  // ============================================================================
  // 7. 默认策略测试
  // ============================================================================

  "PMPChecker" should "M-mode 默认允许访问（无匹配）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // M-mode 访问应该允许（默认策略）
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.M)
      dut.clock.step()

      expectNoException(dut)
      expectPMPMatch(dut, false)
    }
  }

  "PMPChecker" should "M-mode 默认允许访问（匹配且 L=0）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，无权限，L=0
      setPMPEntry(dut, 0, 0x10, 0x400)  // A=2, L=0, 无权限

      // M-mode 访问应该允许（L=0 时不强制权限检查）
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.M)
      dut.clock.step()

      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "M-mode 强制权限检查（L=1）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，无权限，L=1
      setPMPEntry(dut, 0, 0x90, 0x400)  // A=2, L=1, 无权限

      // M-mode 访问应该失败（L=1 时强制权限检查）
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.M)
      dut.clock.step()

      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "U-mode 默认拒绝访问（无匹配）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // U-mode 访问应该拒绝（默认策略）
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()

      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, false)
    }
  }

  "PMPChecker" should "S-mode 默认拒绝访问（无匹配）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // S-mode 访问应该拒绝（默认策略）
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.S)
      dut.clock.step()

      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, false)
    }
  }

  "PMPChecker" should "U-mode 允许访问（匹配且有权限）" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，有读权限
      setPMPEntry(dut, 0, 0x11, 0x400)  // A=2, R=1

      // U-mode 访问应该允许
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()

      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }

  // ============================================================================
  // 8. 异常生成测试
  // ============================================================================

  "PMPChecker" should "正确生成 LOAD_ACCESS_FAULT 异常" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)

      // 读操作应该生成 LOAD_ACCESS_FAULT
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()

      expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "正确生成 STORE_ACCESS_FAULT 异常" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，无写权限
      setPMPEntry(dut, 0, 0x11, 0x400)  // 只有读权限

      // 写操作应该生成 STORE_ACCESS_FAULT
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()

      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x1000)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "异常 tval 应该包含正确的地址" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)

      // 测试多个地址
      for (addr <- Seq(0x1000, 0x1001, 0x1002, 0x1003)) {
        sendPMPReq(dut, addr, LSUOp.LOAD, PrivMode.U)
        dut.clock.step()

        expectException(dut, ExceptionCause.LOAD_ACCESS_FAULT.litValue.toInt, addr)
        expectPMPMatch(dut, true)

        clearPMPReq(dut)
        dut.clock.step()
      }
    }
  }

  // ============================================================================
  // 9. 无效请求测试
  // ============================================================================

  "PMPChecker" should "正确处理无效请求 (req.valid = false)" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，无权限
      setPMPEntry(dut, 0, 0x10, 0x400)

      // 无效请求不应该生成异常
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.addr.poke(0x1000.U)
      dut.io.req.bits.memOp.poke(LSUOp.LOAD)
      dut.io.req.bits.privMode.poke(PrivMode.U)
      dut.clock.step()

      expectNoException(dut)
      expectPMPMatch(dut, false)
    }
  }

  // ============================================================================
  // 10. 综合测试
  // ============================================================================

  "PMPChecker" should "正确处理复杂的 PMP 配置" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置复杂的 PMP 配置
      // entry 0: TOR, [0, 0x1000), 只读
      // entry 5: TOR, [0, 0x2000), 读写
      // entry 9: NA4, [0, 0x2004), 只读
      // entry 13: NAPOT, [0, 0x200C), 读写
      setPMPEntry(dut, 0, 0x09, 0x400)  // A=1, R=1
      setPMPEntry(dut, 5, 0x0B, 0x800)  // A=1, R=1, W=1
      setPMPEntry(dut, 9, 0x11, 0x800)  // A=2, R=1
      setPMPEntry(dut, 13, 0x1B, 0x801)  // A=3, R=1, W=1

      // 测试 entry 0 范围
      sendPMPReq(dut, 0x0, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      sendPMPReq(dut, 0x0, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x0)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 测试 entry 1 范围
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 测试 entry 2 范围
      sendPMPReq(dut, 0x2000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      sendPMPReq(dut, 0x2000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectException(dut, ExceptionCause.STORE_ACCESS_FAULT.litValue.toInt, 0x2000)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 测试 entry 3 范围
      sendPMPReq(dut, 0x2004, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "正确处理所有特权级" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，有读写权限
      setPMPEntry(dut, 0, 0x13, 0x400)  // A=2, R=1, W=1

      // 测试 U-mode
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 测试 S-mode
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.S)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 测试 M-mode
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.M)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }

  "PMPChecker" should "正确处理所有访问类型" in {
    test(new PMPChecker) { dut =>
      setDefaultPMPConfig(dut)

      // 设置 entry 0 为 NA4 模式，有读写权限
      setPMPEntry(dut, 0, 0x13, 0x400)  // A=2, R=1, W=1

      // 测试 LOAD
      sendPMPReq(dut, 0x1000, LSUOp.LOAD, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 测试 STORE
      sendPMPReq(dut, 0x1000, LSUOp.STORE, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
      clearPMPReq(dut)
      dut.clock.step()

      // 测试 NOP (指令取指)
      sendPMPReq(dut, 0x1000, LSUOp.NOP, PrivMode.U)
      dut.clock.step()
      expectNoException(dut)
      expectPMPMatch(dut, true)
    }
  }
}
