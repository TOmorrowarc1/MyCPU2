package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class CSRsUnitTest extends AnyFlatSpec with ChiselScalatestTester {

  // ============================================================================
  // 常量定义
  // ============================================================================

  // CSR 地址常量
  object CSRAddr {
    // M-mode CSR
    val MSTATUS = 0x300
    val MIE     = 0x304
    val MIP     = 0x344
    val MTVEC   = 0x305
    val MEPC    = 0x341
    val MCAUSE  = 0x342
    val MTVAL   = 0x343
    val MSCRATCH = 0x340
    val MEDELEG = 0x302
    val MIDELEG = 0x303
    val MCYCLE  = 0xB00
    val MINSTRET = 0xB02
    
    // S-mode CSR
    val SSTATUS = 0x100
    val SIE     = 0x104
    val SIP     = 0x144
    val SATP    = 0x180
    val STVEC   = 0x105
    val SEPC    = 0x141
    val SCAUSE  = 0x142
    val STVAL   = 0x143
    val SSCRATCH = 0x140
    
    // PMP CSR
    val PMPCFG_BASE = 0x3A0
    val PMPADDR_BASE = 0x3B0
  }

  // 特权级常量
  object PrivLevel {
    val U = 0.U  // User mode
    val S = 1.U  // Supervisor mode
    val M = 3.U  // Machine mode
  }

  // 异常原因常量
  object ExceptionCause {
    val ILLEGAL_INSTRUCTION = 2
    val BREAKPOINT = 3
    val LOAD_ADDRESS_MISALIGNED = 4
    val STORE_ADDRESS_MISALIGNED = 6
    val ECALL_U = 8
    val ECALL_S = 9
    val ECALL_M = 11
  }

  // 中断原因常量
  object InterruptCause {
    val SOFTWARE_M = 1
    val SOFTWARE_S = 1
    val TIMER_M = 5
    val TIMER_S = 5
    val EXTERNAL_M = 9
    val EXTERNAL_S = 9
  }

  // ============================================================================
  // 辅助函数
  // ============================================================================

  // 设置默认输入信号
  def setDefaultInputs(dut: CSRsUnit): Unit = {
    dut.io.csrReadReq.valid.poke(false.B)
    dut.io.csrWriteReq.valid.poke(false.B)
    dut.io.exception.valid.poke(false.B)
    dut.io.pc.poke(0.U)
    dut.io.isCSR.poke(false.B)
    dut.io.mret.poke(false.B)
    dut.io.sret.poke(false.B)
  }

  // 设置 CSR 读请求
  def setCsrReadReq(
      dut: CSRsUnit,
      csrAddr: Int,
      valid: Boolean = true
  ): Unit = {
    dut.io.csrReadReq.valid.poke(valid.B)
    dut.io.csrReadReq.bits.csrAddr.poke(csrAddr.U)
  }

  // 设置 CSR 写请求
  def setCsrWriteReq(
      dut: CSRsUnit,
      csrAddr: Int,
      data: Long,
      valid: Boolean = true
  ): Unit = {
    dut.io.csrWriteReq.valid.poke(valid.B)
    dut.io.csrWriteReq.bits.csrAddr.poke(csrAddr.U)
    dut.io.csrWriteReq.bits.data.poke((data & 0xffffffffL).U)
  }

  // 设置异常信号
  def setException(
      dut: CSRsUnit,
      cause: Int,
      tval: Long = 0,
      valid: Boolean = true
  ): Unit = {
    dut.io.exception.valid.poke(valid.B)
    dut.io.exception.cause.poke(cause.U)
    dut.io.exception.tval.poke((tval & 0xffffffffL).U)
  }

  // 验证 CSR 读响应
  def verifyCsrReadResp(
      dut: CSRsUnit,
      expectedData: Long,
      expectedExceptionValid: Boolean = false,
      expectedExceptionCause: Int = 0
  ): Unit = {
    dut.io.csrReadResp.valid.expect(true.B)
    dut.io.csrReadResp.bits.data.expect((expectedData & 0xffffffffL).U)
    dut.io.csrReadResp.bits.exception.valid.expect(expectedExceptionValid.B)
    if (expectedExceptionValid) {
      dut.io.csrReadResp.bits.exception.cause.expect(expectedExceptionCause.U)
    }
  }

  // 验证 CSR 写响应
  def verifyCsrWriteResp(
      dut: CSRsUnit,
      expectedExceptionValid: Boolean = false,
      expectedExceptionCause: Int = 0
  ): Unit = {
    dut.io.csrWriteResp.valid.expect(true.B)
    dut.io.csrWriteResp.bits.exception.valid.expect(expectedExceptionValid.B)
    if (expectedExceptionValid) {
      dut.io.csrWriteResp.bits.exception.cause.expect(expectedExceptionCause.U)
    }
  }

  // 验证全局冲刷信号
  def verifyGlobalFlush(
      dut: CSRsUnit,
      expectedFlush: Boolean,
      expectedPC: Long = 0
  ): Unit = {
    dut.io.globalFlush.expect(expectedFlush.B)
    dut.io.globalFlushPC.expect((expectedPC & 0xffffffffL).U)
  }

  // 验证特权级
  def verifyPrivMode(dut: CSRsUnit, expectedPriv: UInt): Unit = {
    dut.io.privMode.expect(expectedPriv)
  }

  // 验证 PMP 配置
  def verifyPmpConfig(
      dut: CSRsUnit,
      index: Int,
      expectedValue: Long
  ): Unit = {
    dut.io.pmpConfig(index).expect((expectedValue & 0xffffffffL).U)
  }

  // 验证 PMP 地址
  def verifyPmpAddr(
      dut: CSRsUnit,
      index: Int,
      expectedValue: Long
  ): Unit = {
    dut.io.pmpAddr(index).expect((expectedValue & 0xffffffffL).U)
  }

  // 执行 CSR 读操作
  def executeCsrRead(
      dut: CSRsUnit,
      csrAddr: Int,
      expectedData: Long,
      expectedExceptionValid: Boolean = false,
      expectedExceptionCause: Int = 0
  ): Unit = {
    setDefaultInputs(dut)
    setCsrReadReq(dut, csrAddr, valid = true)
    dut.clock.step()
    verifyCsrReadResp(dut, expectedData, expectedExceptionValid, expectedExceptionCause)
    dut.clock.step()
  }

  // 执行 CSR 写操作
  def executeCsrWrite(
      dut: CSRsUnit,
      csrAddr: Int,
      data: Long,
      expectedExceptionValid: Boolean = false,
      expectedExceptionCause: Int = 0
  ): Unit = {
    setDefaultInputs(dut)
    setCsrWriteReq(dut, csrAddr, data, valid = true)
    dut.clock.step()
    verifyCsrWriteResp(dut, expectedExceptionValid, expectedExceptionCause)
    dut.clock.step()
  }

  // 读取 CSR 并返回数据（用于验证）
  def executeCsrReadAndGetData(dut: CSRsUnit, csrAddr: Int): Long = {
    setDefaultInputs(dut)
    setCsrReadReq(dut, csrAddr, valid = true)
    dut.clock.step()
    val data = dut.io.csrReadResp.bits.data.peek().litValue
    dut.clock.step()
    data.toLong
  }

  // ============================================================================
  // 1. 初始化和基本功能测试
  // ============================================================================

  "CSRsUnit" should "正确初始化状态" in {
    test(new CSRsUnit) { dut =>
      setDefaultInputs(dut)
      
      // 验证初始特权级为 M-mode
      verifyPrivMode(dut, PrivLevel.M)
      
      // 验证全局冲刷信号初始状态
      verifyGlobalFlush(dut, expectedFlush = false, expectedPC = 0)
      
      // 验证 mstatus 初始化
      executeCsrRead(dut, CSRAddr.MSTATUS, expectedData = 0)
    }
  }

  // ============================================================================
  // 2. CSR 读写测试
  // ============================================================================

  // 2.1 M-mode CSR 读写测试

  it should "正确读取 mstatus 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MSTATUS, expectedData = 0)
    }
  }

  it should "正确写入 mstatus 寄存器（RW 字段）" in {
    test(new CSRsUnit) { dut =>
      // 写入 MIE 位
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8)
      // 读取验证
      executeCsrRead(dut, CSRAddr.MSTATUS, expectedData = 0x8)
    }
  }

  it should "正确处理 mstatus 的 WARL 字段（MPP）" in {
    test(new CSRsUnit) { dut =>
      // 尝试写入 MPP = 2（保留值），应该被忽略或映射到有效值
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x1000)
      // 读取验证（MPP 应该不是 2）
      val readData = executeCsrReadAndGetData(dut, CSRAddr.MSTATUS)
      assert((readData & 0x1800) != 0x1000)
    }
  }

  it should "正确读取 mie 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MIE, expectedData = 0)
    }
  }

  it should "正确写入 mie 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 启用机器外部中断
      executeCsrWrite(dut, CSRAddr.MIE, data = 0x800)
      executeCsrRead(dut, CSRAddr.MIE, expectedData = 0x800)
    }
  }

  it should "正确读取 mip 寄存器（RO 字段）" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MIP, expectedData = 0)
    }
  }

  it should "正确处理 mip 的 RW 字段" in {
    test(new CSRsUnit) { dut =>
      // 写入 SEIP（软件可写）
      executeCsrWrite(dut, CSRAddr.MIP, data = 0x200)
      executeCsrRead(dut, CSRAddr.MIP, expectedData = 0x200)
    }
  }

  it should "正确读取 mtvec 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MTVEC, expectedData = 0)
    }
  }

  it should "正确写入 mtvec 寄存器（BASE 字段）" in {
    test(new CSRsUnit) { dut =>
      // 设置陷阱向量基地址为 0x8000
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000)
      executeCsrRead(dut, CSRAddr.MTVEC, expectedData = 0x8000)
    }
  }

  it should "正确处理 mtvec 的 WARL 字段（MODE）" in {
    test(new CSRsUnit) { dut =>
      // 尝试写入 MODE = 2（保留值），应该被映射到 0
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x2)
      executeCsrRead(dut, CSRAddr.MTVEC, expectedData = 0)
    }
  }

  it should "正确读取 mepc 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MEPC, expectedData = 0)
    }
  }

  it should "正确写入 mepc 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置异常返回地址
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x8000)
      executeCsrRead(dut, CSRAddr.MEPC, expectedData = 0x8000)
    }
  }

  it should "正确读取 mcause 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MCAUSE, expectedData = 0)
    }
  }

  it should "正确写入 mcause 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置异常原因
      executeCsrWrite(dut, CSRAddr.MCAUSE, data = 0x2)
      executeCsrRead(dut, CSRAddr.MCAUSE, expectedData = 0x2)
    }
  }

  it should "正确读取 mtval 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MTVAL, expectedData = 0)
    }
  }

  it should "正确写入 mtval 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置陷阱值
      executeCsrWrite(dut, CSRAddr.MTVAL, data = 0x1000)
      executeCsrRead(dut, CSRAddr.MTVAL, expectedData = 0x1000)
    }
  }

  it should "正确读取 mscratch 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MSCRATCH, expectedData = 0)
    }
  }

  it should "正确写入 mscratch 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置临时寄存器
      executeCsrWrite(dut, CSRAddr.MSCRATCH, data = 0xdeadbeefL)
      executeCsrRead(dut, CSRAddr.MSCRATCH, expectedData = 0xdeadbeefL)
    }
  }

  it should "正确读取 medeleg 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MEDELEG, expectedData = 0)
    }
  }

  it should "正确写入 medeleg 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 委托部分异常到 S-mode
      executeCsrWrite(dut, CSRAddr.MEDELEG, data = 0xffff)
      executeCsrRead(dut, CSRAddr.MEDELEG, expectedData = 0xffff)
    }
  }

  it should "正确读取 mideleg 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.MIDELEG, expectedData = 0)
    }
  }

  it should "正确写入 mideleg 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 委托部分中断到 S-mode
      executeCsrWrite(dut, CSRAddr.MIDELEG, data = 0x222)
      executeCsrRead(dut, CSRAddr.MIDELEG, expectedData = 0x222)
    }
  }

  it should "正确读取 mcycle 寄存器（RO）" in {
    test(new CSRsUnit) { dut =>
      // mcycle 应该自动递增
      val cycle1 = executeCsrReadAndGetData(dut, CSRAddr.MCYCLE)
      dut.clock.step(10)
      val cycle2 = executeCsrReadAndGetData(dut, CSRAddr.MCYCLE)
      assert(cycle2 > cycle1)
    }
  }

  it should "正确读取 minstret 寄存器（RO）" in {
    test(new CSRsUnit) { dut =>
      // minstret 应该保持不变（未实现）
      executeCsrRead(dut, CSRAddr.MINSTRET, expectedData = 0)
    }
  }

  // 2.2 S-mode CSR 读写测试

  it should "正确读取 sstatus 寄存器（mstatus 别名）" in {
    test(new CSRsUnit) { dut =>
      // 先写入 mstatus
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x2)
      // 通过 sstatus 读取
      executeCsrRead(dut, CSRAddr.SSTATUS, expectedData = 0x2) // SIE 位
    }
  }

  it should "正确写入 sstatus 寄存器（mstatus 别名）" in {
    test(new CSRsUnit) { dut =>
      // 通过 sstatus 写入
      executeCsrWrite(dut, CSRAddr.SSTATUS, data = 0x2)
      // 通过 mstatus 读取验证
      executeCsrRead(dut, CSRAddr.MSTATUS, expectedData = 0x2)
    }
  }

  it should "正确读取 sie 寄存器（mie 别名，受 mideleg 限制）" in {
    test(new CSRsUnit) { dut =>
      // 先设置 mideleg
      executeCsrWrite(dut, CSRAddr.MIDELEG, data = 0x222)
      // 读取 sie
      executeCsrRead(dut, CSRAddr.SIE, expectedData = 0)
    }
  }

  it should "正确写入 sie 寄存器（mie 别名，受 mideleg 限制）" in {
    test(new CSRsUnit) { dut =>
      // 先设置 mideleg
      executeCsrWrite(dut, CSRAddr.MIDELEG, data = 0x222)
      // 通过 sie 写入
      executeCsrWrite(dut, CSRAddr.SIE, data = 0x222)
      // 通过 mie 读取验证
      executeCsrRead(dut, CSRAddr.MIE, expectedData = 0x222)
    }
  }

  it should "正确读取 sip 寄存器（mip 别名，受 mideleg 限制）" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.SIP, expectedData = 0)
    }
  }

  it should "正确写入 sip 寄存器（mip 别名，受 mideleg 限制）" in {
    test(new CSRsUnit) { dut =>
      // 先设置 mideleg
      executeCsrWrite(dut, CSRAddr.MIDELEG, data = 0x222)
      // 通过 sip 写入
      executeCsrWrite(dut, CSRAddr.SIP, data = 0x222)
      // 通过 mip 读取验证
      executeCsrRead(dut, CSRAddr.MIP, expectedData = 0x222)
    }
  }

  it should "正确读取 satp 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.SATP, expectedData = 0)
    }
  }

  it should "正确写入 satp 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 SATP（Bare 模式）
      executeCsrWrite(dut, CSRAddr.SATP, data = 0)
      executeCsrRead(dut, CSRAddr.SATP, expectedData = 0)
    }
  }

  it should "正确处理 satp 的 WARL 字段（MODE）" in {
    test(new CSRsUnit) { dut =>
      // 尝试写入 MODE = 1（Sv32 模式）
      executeCsrWrite(dut, CSRAddr.SATP, data = 0x8000L)
      executeCsrRead(dut, CSRAddr.SATP, expectedData = 0x8000L)
    }
  }

  it should "正确读取 stvec 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.STVEC, expectedData = 0)
    }
  }

  it should "正确写入 stvec 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 S-mode 陷阱向量基地址
      executeCsrWrite(dut, CSRAddr.STVEC, data = 0x80400000L)
      executeCsrRead(dut, CSRAddr.STVEC, expectedData = 0x80400000L)
    }
  }

  it should "正确读取 sepc 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.SEPC, expectedData = 0)
    }
  }

  it should "正确写入 sepc 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 S-mode 异常返回地址
      executeCsrWrite(dut, CSRAddr.SEPC, data = 0x80400000L)
      executeCsrRead(dut, CSRAddr.SEPC, expectedData = 0x80400000L)
    }
  }

  it should "正确读取 scause 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.SCAUSE, expectedData = 0)
    }
  }

  it should "正确写入 scause 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 S-mode 异常原因
      executeCsrWrite(dut, CSRAddr.SCAUSE, data = 0x9)
      executeCsrRead(dut, CSRAddr.SCAUSE, expectedData = 0x9)
    }
  }

  it should "正确读取 stval 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.STVAL, expectedData = 0)
    }
  }

  it should "正确写入 stval 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 S-mode 陷阱值
      executeCsrWrite(dut, CSRAddr.STVAL, data = 0x2000)
      executeCsrRead(dut, CSRAddr.STVAL, expectedData = 0x2000)
    }
  }

  it should "正确读取 sscratch 寄存器" in {
    test(new CSRsUnit) { dut =>
      executeCsrRead(dut, CSRAddr.SSCRATCH, expectedData = 0)
    }
  }

  it should "正确写入 sscratch 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 S-mode 临时寄存器
      executeCsrWrite(dut, CSRAddr.SSCRATCH, data = 0xfeedfaceL)
      executeCsrRead(dut, CSRAddr.SSCRATCH, expectedData = 0xfeedfaceL)
    }
  }

  // 2.3 PMP CSR 读写测试

  it should "正确读取 pmpcfg 寄存器" in {
    test(new CSRsUnit) { dut =>
      for (i <- 0 until 16) {
        executeCsrRead(dut, CSRAddr.PMPCFG_BASE + i, expectedData = 0)
      }
    }
  }

  it should "正确写入 pmpcfg 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 pmpcfg0
      executeCsrWrite(dut, CSRAddr.PMPCFG_BASE, data = 0x0f)
      executeCsrRead(dut, CSRAddr.PMPCFG_BASE, expectedData = 0x0f)
    }
  }

  it should "正确处理 pmpcfg 的锁定功能" in {
    test(new CSRsUnit) { dut =>
      // 先设置锁定位
      executeCsrWrite(dut, CSRAddr.PMPCFG_BASE, data = 0x80)
      // 尝试修改锁定的配置，应该被忽略
      executeCsrWrite(dut, CSRAddr.PMPCFG_BASE, data = 0x00)
      executeCsrRead(dut, CSRAddr.PMPCFG_BASE, expectedData = 0x80)
    }
  }

  it should "正确读取 pmpaddr 寄存器" in {
    test(new CSRsUnit) { dut =>
      for (i <- 0 until 64) {
        executeCsrRead(dut, CSRAddr.PMPADDR_BASE + i, expectedData = 0)
      }
    }
  }

  it should "正确写入 pmpaddr 寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 pmpaddr0
      executeCsrWrite(dut, CSRAddr.PMPADDR_BASE, data = 0x1000)
      executeCsrRead(dut, CSRAddr.PMPADDR_BASE, expectedData = 0x1000)
    }
  }

  it should "正确处理 pmpaddr 的锁定功能" in {
    test(new CSRsUnit) { dut =>
      // 先锁定 pmpcfg0 的第一个配置
      executeCsrWrite(dut, CSRAddr.PMPCFG_BASE, data = 0x80)
      // 尝试修改 pmpaddr0，应该被忽略
      executeCsrWrite(dut, CSRAddr.PMPADDR_BASE, data = 0x2000)
      executeCsrRead(dut, CSRAddr.PMPADDR_BASE, expectedData = 0)
    }
  }

  // 2.4 非法 CSR 访问测试

  it should "正确处理非法 CSR 读地址" in {
    test(new CSRsUnit) { dut =>
      // 尝试读取不存在的 CSR
      setDefaultInputs(dut)
      setCsrReadReq(dut, 0xfff, valid = true)
      dut.clock.step()
      // 验证异常响应
      verifyCsrReadResp(
        dut,
        expectedData = 0,
        expectedExceptionValid = true,
        expectedExceptionCause = ExceptionCause.ILLEGAL_INSTRUCTION
      )
    }
  }

  it should "正确处理非法 CSR 写地址" in {
    test(new CSRsUnit) { dut =>
      // 尝试写入不存在的 CSR
      setDefaultInputs(dut)
      setCsrWriteReq(dut, 0xfff, data = 0x1234, valid = true)
      dut.clock.step()
      // 验证异常响应
      verifyCsrWriteResp(
        dut,
        expectedExceptionValid = true,
        expectedExceptionCause = ExceptionCause.ILLEGAL_INSTRUCTION
      )
    }
  }

  // ============================================================================
  // 3. Trap 处理测试
  // ============================================================================

  // 3.1 M-mode Trap 处理测试

  it should "正确处理 M-mode 异常进入" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8) // MIE = 1
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000) // 设置陷阱向量基地址
      
      // 触发异常
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x8000L, valid = true)
      dut.io.pc.poke(0x8000.U)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
      
      // 验证特权级切换到 M-mode
      verifyPrivMode(dut, PrivLevel.M)
      
      // 验证 mepc 被更新
      executeCsrRead(dut, CSRAddr.MEPC, expectedData = 0x8000)
      
      // 验证 mcause 被更新
      executeCsrRead(dut, CSRAddr.MCAUSE, expectedData = ExceptionCause.ILLEGAL_INSTRUCTION)
      
      // 验证 mtval 被更新
      executeCsrRead(dut, CSRAddr.MTVAL, expectedData = 0x8000)
      
      // 验证 mstatus 被更新（MPP = M, MPIE = 1, MIE = 0）
      val mstatus = executeCsrReadAndGetData(dut, CSRAddr.MSTATUS)
      assert((mstatus & 0x1888) == 0x1880) // MPP=M(11), MPIE=1, MIE=0
    }
  }

  it should "正确处理 M-mode 中断进入" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8) // MIE = 1
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000) // 设置陷阱向量基地址
      executeCsrWrite(dut, CSRAddr.MIE, data = 0x8) // MSIE = 1
      executeCsrWrite(dut, CSRAddr.MIP, data = 0x8) // MSIP = 1
          
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
      
      // 验证特权级切换到 M-mode
      verifyPrivMode(dut, PrivLevel.M)
      
      // 验证 mcause 被更新（中断位 = 1）
      executeCsrRead(dut, CSRAddr.MCAUSE, expectedData = 0x80000003L)
    }
  }

  it should "正确处理 M-mode 向量化中断" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8) // MIE = 1
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x80000001L) // MODE = 1（向量化）
      executeCsrWrite(dut, CSRAddr.MIE, data = 0x8) // MSIE = 1
      executeCsrWrite(dut, CSRAddr.MIP, data = 0x8) // MSIP = 1
           
      // 验证全局冲刷信号（PC = BASE + cause * 4）
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000000CL) // 0x80000000 + 3 * 4
    }
  }

  // 3.2 S-mode Trap 处理(委托)测试

  it should "正确处理异常委托到 S-mode" in {
    test(new CSRsUnit) { dut =>
      // 使用 MRET 从 M-mode 返回到 S-mode
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x880) // MPP = S, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x80400000L) // 设置返回地址
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.S)

      // 设置异常委托
      executeCsrWrite(dut, CSRAddr.MEDELEG, data = 0x4) // 委托非法指令异常
      
      // 触发异常（从 S-mode）
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x80400000L, valid = true)
      dut.io.pc.poke(0x80400000L.U)
      dut.clock.step()
      
      // 验证进入 S-mode 而不是 M-mode
      verifyPrivMode(dut, PrivLevel.S)
    }
  }

  it should "正确处理未委托异常进入 M-mode" in {
    test(new CSRsUnit) { dut =>
      // 使用 MRET 从 M-mode 返回到 S-mode
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x880) // MPP = S, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x80400000L) // 设置返回地址
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.S)

      // 不设置异常委托      
      // 触发异常（从 S-mode）
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x80400000L, valid = true)
      dut.io.pc.poke(0x80400000L.U)
      dut.clock.step()
      
      // 验证进入 M-mode
      verifyPrivMode(dut, PrivLevel.M)
    }
  }

  it should "正确处理中断委托到 S-mode" in {
    test(new CSRsUnit) { dut =>
      // 设置中断委托
      executeCsrWrite(dut, CSRAddr.MIDELEG, data = 0x2) // 委托软件中断

      // 使用 MRET 从 M-mode 返回到 S-mode
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x880) // MPP = S, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x80400000L) // 设置返回地址
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.S)
      
      // 触发中断（从 S-mode）
      setDefaultInputs(dut)
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x202) // MPP = S, SIE = 1
      executeCsrWrite(dut, CSRAddr.MIE, data = 0x2) // 启用软件中断
      executeCsrWrite(dut, CSRAddr.MIP, data = 0x2) // 设置软件中断
      dut.io.pc.poke(0x80400000L.U)
      dut.clock.step()
      
      // 验证进入 S-mode 而不是 M-mode
      verifyPrivMode(dut, PrivLevel.S)
    }
  }

  // TODO: 正确处理未委托中断进入 M-mode

  // ============================================================================
  // 4. 特权级切换测试
  // ============================================================================

  // 4.1 MRET 返回测试

  it should "正确处理 MRET 返回到 U-mode" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态（MPP = U）
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x80) // MPP = U, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x8000) // 设置返回地址
      
      // 触发 MRET
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
      
      // 验证特权级切换到 U-mode
      verifyPrivMode(dut, PrivLevel.U)
      
      // 验证 mstatus 被更新（MPP = U, MPIE = 1, MIE = 1）
      val mstatus = executeCsrReadAndGetData(dut, CSRAddr.MSTATUS)
      assert((mstatus & 0x1888) == 0x88) // MPP=U(00), MPIE=1, MIE=1
    }
  }

  it should "正确处理 MRET 返回到 S-mode" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态（MPP = S）
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x0880) // MPP = S, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x80400000L) // 设置返回地址
      
      // 触发 MRET
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x80400000L)
      
      // 验证特权级切换到 S-mode
      verifyPrivMode(dut, PrivLevel.S)
      
      // 验证 mstatus 被更新（MPP = U, MPIE = 1, MIE = 1）
      val mstatus = executeCsrReadAndGetData(dut, CSRAddr.MSTATUS)
      assert((mstatus & 0x1888) == 0x88) // MPP=U(00), MPIE=1, MIE=1
    }
  }

  it should "正确处理 MRET 返回到 M-mode" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态（MPP = M）
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x1800) // MPP = M, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x8000) // 设置返回地址
      
      // 触发 MRET
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
      
      // 验证特权级保持在 M-mode
      verifyPrivMode(dut, PrivLevel.M)
    }
  }

  // 4.2 SRET 返回测试

  it should "正确处理 SRET 返回到 U-mode" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态（SPP = U）
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x20) // SPP = U, SPIE = 1
      executeCsrWrite(dut, CSRAddr.SEPC, data = 0x80400000L) // 设置返回地址
      
      // 触发 SRET
      setDefaultInputs(dut)
      dut.io.sret.poke(true.B)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x80400000L)
      
      // 验证特权级切换到 U-mode
      verifyPrivMode(dut, PrivLevel.U)
      
      // 验证 mstatus 被更新（SPP = U, SPIE = 1, SIE = 1）
      val mstatus = executeCsrReadAndGetData(dut, CSRAddr.MSTATUS)
      assert((mstatus & 0x22) == 0x22) // SPP=U(0), SPIE=1, SIE=1
    }
  }

  it should "正确处理 SRET 返回到 S-mode" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态（SPP = S）
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x120) // SPP = S, SPIE = 1
      executeCsrWrite(dut, CSRAddr.SEPC, data = 0x80400000L) // 设置返回地址
      
      // 触发 SRET
      setDefaultInputs(dut)
      dut.io.sret.poke(true.B)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x80400000L)
      
      // 验证特权级保持在 S-mode
      verifyPrivMode(dut, PrivLevel.S)
      
      // 验证 mstatus 被更新（SPP = U, SPIE = 1, SIE = 1）
      val mstatus = executeCsrReadAndGetData(dut, CSRAddr.MSTATUS)
      assert((mstatus & 0x22) == 0x22) // SPP=U(0), SPIE=1, SIE=1
    }
  }

  // ============================================================================
  // 5. PMP 配置测试
  // ============================================================================

  it should "正确输出 PMP 配置寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 pmpcfg0
      executeCsrWrite(dut, CSRAddr.PMPCFG_BASE, data = 0x0f)
      
      // 验证输出
      verifyPmpConfig(dut, 0, expectedValue = 0x0f)
    }
  }

  it should "正确输出 PMP 地址寄存器" in {
    test(new CSRsUnit) { dut =>
      // 设置 pmpaddr0
      executeCsrWrite(dut, CSRAddr.PMPADDR_BASE, data = 0x1000)
      
      // 验证输出
      verifyPmpAddr(dut, 0, expectedValue = 0x1000)
    }
  }

  it should "正确处理多个 PMP 条目" in {
    test(new CSRsUnit) { dut =>
      // 设置多个 PMP 条目
      for (i <- 0 until 4) {
        executeCsrWrite(dut, CSRAddr.PMPCFG_BASE + i, data = 0x0f << (i * 8))
        executeCsrWrite(dut, CSRAddr.PMPADDR_BASE + i, data = 0x1000 * (i + 1))
      }
      
      // 验证输出
      for (i <- 0 until 4) {
        verifyPmpConfig(dut, i, expectedValue = 0x0f << (i * 8))
        verifyPmpAddr(dut, i, expectedValue = 0x1000 * (i + 1))
      }
    }
  }

  // ============================================================================
  // 6. 全局冲刷信号测试
  // ============================================================================

  it should "在异常时触发全局冲刷" in {
    test(new CSRsUnit) { dut =>
      // 触发异常
      setDefaultInputs(dut)
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000) // 设置陷阱向量基地址
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x8000L, valid = true)
      dut.io.pc.poke(0x8000.U)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
    }
  }

  it should "在中断时触发全局冲刷" in {
    test(new CSRsUnit) { dut =>
      // 设置中断
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8) // MIE = 1
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000) // 设置陷阱向量基地址
      executeCsrWrite(dut, CSRAddr.MIE, data = 0x8) // MSIE = 1
      executeCsrWrite(dut, CSRAddr.MIP, data = 0x8) // MSIP = 1
         
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
    }
  }

  it should "在 MRET 时触发全局冲刷" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x80) // MPP = U, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x8000) // 设置返回地址
      
      // 触发 MRET
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
    }
  }

  it should "在 SRET 时触发全局冲刷" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x20) // SPP = U, SPIE = 1
      executeCsrWrite(dut, CSRAddr.SEPC, data = 0x80400000L) // 设置返回地址
      
      // 触发 SRET
      setDefaultInputs(dut)
      dut.io.sret.poke(true.B)
      dut.clock.step()
      
      // 验证全局冲刷信号
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x80400000L)
    }
  }

  it should "在 CSR 写入时不触发全局冲刷" in {
    test(new CSRsUnit) { dut =>
      // 执行 CSR 写入
      setDefaultInputs(dut)
      setCsrWriteReq(dut, CSRAddr.MSTATUS, data = 0x8, valid = true)
      dut.clock.step()
      
      // 验证全局冲刷信号未被触发
      verifyGlobalFlush(dut, expectedFlush = false, expectedPC = 0)
    }
  }

  // ============================================================================
  // 7. 更新优先级测试
  // ============================================================================

  it should "异常优先级高于中断" in {
    test(new CSRsUnit) { dut =>
      // 同时设置异常和中断
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8) // MIE = 1
      executeCsrWrite(dut, CSRAddr.MIE, data = 0x800) // MSIE = 1
      executeCsrWrite(dut, CSRAddr.MIP, data = 0x800) // MSIP = 1
      
      // 触发异常和中断
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x8000L, valid = true)
      dut.io.pc.poke(0x8000.U)
      dut.clock.step()
      
      // 验证异常被处理（mcause 不包含中断位）
      executeCsrRead(dut, CSRAddr.MCAUSE, expectedData = ExceptionCause.ILLEGAL_INSTRUCTION)
    }
  }

  it should "中断优先级高于返回指令" in {
    test(new CSRsUnit) { dut =>
      // 设置中断
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8) // MIE = 1
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000) // 设置陷阱向量基地址
      executeCsrWrite(dut, CSRAddr.MIE, data = 0x8) // MSIE = 1
      executeCsrWrite(dut, CSRAddr.MIP, data = 0x8) // MSIP = 1
      
      // 同时设置 MRET
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)

      // 验证中断被处理
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
      verifyPrivMode(dut, PrivLevel.M)
    }
  }

  it should "返回指令优先级高于 CSR 读写" in {
    test(new CSRsUnit) { dut =>
      // 设置初始状态
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x80) // MPP = U, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x8000) // 设置返回地址
      
      // 同时设置 MRET 和 CSR 写入
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      setCsrWriteReq(dut, CSRAddr.MSTATUS, data = 0x8, valid = true)
      dut.clock.step()
      
      // 验证 MRET 被处理（全局冲刷被触发）
      verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
    }
  }

  // ============================================================================
  // 8. 综合场景测试
  // ============================================================================

  it should "正确处理异常-返回-异常序列" in {
    test(new CSRsUnit) { dut =>
      // 1. 触发异常
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8) // MIE = 1
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000)

      // 先返回 U-mode
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.U)

      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x80400000L, valid = true)
      dut.io.pc.poke(0x80400000L.U)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.M)
      
      // 2. 返回
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.U)
      
      // 3. 再次触发异常
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x80400004L, valid = true)
      dut.io.pc.poke(0x80400004L.U)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.M)
    }
  }

  it should "正确处理 S-mode 和 M-mode 异常切换" in {
    test(new CSRsUnit) { dut =>
      // 设置异常委托
      executeCsrWrite(dut, CSRAddr.MEDELEG, data = 0x4) // 委托非法指令异常
      
      // 使用 MRET 从 M-mode 返回到 S-mode
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x880) // MPP = S, MPIE = 1
      executeCsrWrite(dut, CSRAddr.MEPC, data = 0x80400000L) // 设置返回地址
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.S)

      // 1. 从 S-mode 触发异常（委托到 S-mode）
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x80400000L, valid = true)
      dut.io.pc.poke(0x80400000L.U)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.S)
      
      // 2. 返回
      setDefaultInputs(dut)
      dut.io.sret.poke(true.B)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.S)
      
      // 3. 触发异常（不委托），进入 M-mode
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.BREAKPOINT, tval = 0x8000L, valid = true)
      dut.io.pc.poke(0x8000.U)
      dut.clock.step()
      verifyPrivMode(dut, PrivLevel.M)
    }
  }

  it should "正确处理 CSR 读写和 Trap 混合场景" in {
    test(new CSRsUnit) { dut =>
      // 1. 写入 CSR
      executeCsrWrite(dut, CSRAddr.MSTATUS, data = 0x8)
      
      // 2. 触发异常
      setDefaultInputs(dut)
      setException(dut, cause = ExceptionCause.ILLEGAL_INSTRUCTION, tval = 0x8000L, valid = true)
      dut.io.pc.poke(0x8000.U)
      dut.clock.step()
      
      // 3. 在异常处理程序中读取 CSR
      executeCsrRead(dut, CSRAddr.MEPC, expectedData = 0x8000)
      executeCsrRead(dut, CSRAddr.MCAUSE, expectedData = ExceptionCause.ILLEGAL_INSTRUCTION)
      
      // 4. 返回
      setDefaultInputs(dut)
      dut.io.mret.poke(true.B)
      dut.clock.step()
      
      // 5. 验证 CSR 状态
      val mstatus = executeCsrReadAndGetData(dut, CSRAddr.MSTATUS)
      assert((mstatus & 0x8) == 0x8) // MIE 应该被恢复
    }
  }

  // ============================================================================
  // 9. 边界情况测试
  // ============================================================================

  it should "正确处理所有异常类型" in {
    test(new CSRsUnit) { dut =>
      // 测试所有异常类型
      val exceptionCauses = Seq(
        ExceptionCause.ILLEGAL_INSTRUCTION,
        ExceptionCause.BREAKPOINT,
        ExceptionCause.LOAD_ADDRESS_MISALIGNED,
        ExceptionCause.STORE_ADDRESS_MISALIGNED,
        ExceptionCause.ECALL_U,
        ExceptionCause.ECALL_S,
        ExceptionCause.ECALL_M
      )
      
      executeCsrWrite(dut, CSRAddr.MTVEC, data = 0x8000) // 设置陷阱向量基地址  

      for (cause <- exceptionCauses) {
        // 触发异常
        setDefaultInputs(dut)
        setException(dut, cause = cause, tval = 0x7000L, valid = true)
        dut.io.pc.poke(0x6000.U)
        dut.clock.step()
        
        // 验证异常被处理
        verifyGlobalFlush(dut, expectedFlush = true, expectedPC = 0x8000)
        executeCsrRead(dut, CSRAddr.MCAUSE, expectedData = cause)
      }
    }
  }

  it should "正确处理连续的 CSR 读写操作" in {
    test(new CSRsUnit) { dut =>
      // 连续写入多个 CSR
      val csrWrites = Seq(
        (CSRAddr.MSTATUS, 0x8L),
        (CSRAddr.MIE, 0x800L),
        (CSRAddr.MTVEC, 0x8000L),
        (CSRAddr.MEPC, 0x80400000L),
        (CSRAddr.MCAUSE, 0x2L),
        (CSRAddr.MTVAL, 0x1000L)
      )
      
      for ((addr, data) <- csrWrites) {
        executeCsrWrite(dut, addr, data)
      }
      
      // 连续读取验证
      for ((addr, data) <- csrWrites) {
        executeCsrRead(dut, addr, data)
      }
    }
  }
}
