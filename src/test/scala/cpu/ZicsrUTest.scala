package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ZicsrUTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "ZicsrU"

  // ============================================================================
  // 辅助函数
  // ============================================================================

  // 设置默认输入信号
  def setDefaultInputs(dut: ZicsrU): Unit = {
    dut.io.zicsrReq.valid.poke(false.B)
    dut.io.prfData.valid.poke(false.B)
    dut.io.commitReady.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.branchOH.poke(0.U)
    dut.io.cdb.ready.poke(true.B)
    dut.io.cdbIn.valid.poke(false.B)

    // 默认 CSR 响应
    dut.io.csrReadResp.data.poke(0.U)
    dut.io.csrReadResp.exception.valid.poke(false.B)
    dut.io.csrReadResp.exception.cause.poke(0.U)
    dut.io.csrReadResp.exception.tval.poke(0.U)
    dut.io.csrWriteResp.exception.valid.poke(false.B)
    dut.io.csrWriteResp.exception.cause.poke(0.U)
    dut.io.csrWriteResp.exception.tval.poke(0.U)
  }

  // 设置 ZicsrDispatch 请求
  def setZicsrDispatch(
      dut: ZicsrU,
      zicsrOp: ZicsrOp.Type,
      csrAddr: Int = 0,
      robId: Int = 0,
      phyRd: Int = 1,
      branchMask: Int = 0,
      privMode: PrivMode.Type = PrivMode.M,
      src1Sel: Src1Sel.Type = Src1Sel.REG,
      src1Tag: Int = 0,
      src1Ready: Boolean = true,
      imm: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.zicsrReq.valid.poke(true.B)
    dut.io.zicsrReq.bits.zicsrOp.poke(zicsrOp)
    dut.io.zicsrReq.bits.csrAddr.poke(csrAddr.U)
    dut.io.zicsrReq.bits.robId.poke(robId.U)
    dut.io.zicsrReq.bits.phyRd.poke(phyRd.U)
    dut.io.zicsrReq.bits.branchMask.poke(branchMask.U)
    dut.io.zicsrReq.bits.privMode.poke(privMode)
    dut.io.zicsrReq.bits.data.src1Sel.poke(src1Sel)
    dut.io.zicsrReq.bits.data.src1Tag.poke(src1Tag.U)
    dut.io.zicsrReq.bits.data.src1Ready.poke(src1Ready.B)
    dut.io.zicsrReq.bits.data.imm.poke((imm & mask).U)
  }

  // 设置 PRF 数据响应
  def setPrfData(dut: ZicsrU, rdata1: Long = 0, rdata2: Long = 0): Unit = {
    val mask = 0xffffffffL
    dut.io.prfData.valid.poke(true.B)
    dut.io.prfData.bits.rdata1.poke((rdata1 & mask).U)
    dut.io.prfData.bits.rdata2.poke((rdata2 & mask).U)
  }

  // 设置 CSR 读响应
  def setCsrReadResp(
      dut: ZicsrU,
      data: Long = 0,
      exceptionValid: Boolean = false,
      exceptionCause: Int = 0,
      exceptionTval: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.csrReadResp.data.poke((data & mask).U)
    dut.io.csrReadResp.exception.valid.poke(exceptionValid.B)
    dut.io.csrReadResp.exception.cause.poke(exceptionCause.U)
    dut.io.csrReadResp.exception.tval.poke((exceptionTval & mask).U)
  }

  // 设置 CSR 写响应
  def setCsrWriteResp(
      dut: ZicsrU,
      exceptionValid: Boolean = false,
      exceptionCause: Int = 0,
      exceptionTval: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.csrWriteResp.exception.valid.poke(exceptionValid.B)
    dut.io.csrWriteResp.exception.cause.poke(exceptionCause.U)
    dut.io.csrWriteResp.exception.tval.poke((exceptionTval & mask).U)
  }

  // 验证 CDB 消息
  def verifyCDBMessage(
      dut: ZicsrU,
      expectedRobId: Int = 0,
      expectedPhyRd: Int = 0,
      expectedData: Long = 0,
      expectedHasSideEffect: Boolean = false,
      expectedExceptionValid: Boolean = false,
      expectedExceptionCause: Int = 0,
      expectedExceptionTval: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.cdb.valid.expect(true.B)
    dut.io.cdb.bits.robId.expect(expectedRobId.U)
    dut.io.cdb.bits.phyRd.expect(expectedPhyRd.U)
    dut.io.cdb.bits.data.expect((expectedData & mask).U)
    dut.io.cdb.bits.hasSideEffect.expect(expectedHasSideEffect)
    dut.io.cdb.bits.exception.valid.expect(expectedExceptionValid.B)
    if (expectedExceptionValid) {
      dut.io.cdb.bits.exception.cause.expect(expectedExceptionCause.U)
      dut.io.cdb.bits.exception.tval.expect((expectedExceptionTval & mask).U)
    }
  }

  // 验证 CSR 读请求
  def verifyCsrReadReq(
      dut: ZicsrU,
      expectedCsrAddr: Int = 0,
      expectedPrivMode: PrivMode.Type = PrivMode.M
  ): Unit = {
    dut.io.csrReadReq.valid.expect(true.B)
    dut.io.csrReadReq.bits.csrAddr.expect(expectedCsrAddr.U)
    dut.io.csrReadReq.bits.privMode.expect(expectedPrivMode)
  }

  // 验证 CSR 写请求
  def verifyCsrWriteReq(
      dut: ZicsrU,
      expectedCsrAddr: Int = 0,
      expectedPrivMode: PrivMode.Type = PrivMode.M,
      expectedData: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.csrWriteReq.valid.expect(true.B)
    dut.io.csrWriteReq.bits.csrAddr.expect(expectedCsrAddr.U)
    dut.io.csrWriteReq.bits.privMode.expect(expectedPrivMode)
    dut.io.csrWriteReq.bits.data.expect((expectedData & mask).U)
  }

  // 验证 CSR 读请求无效
  def verifyCsrReadReqInvalid(dut: ZicsrU): Unit = {
    dut.io.csrReadReq.valid.expect(false.B)
  }

  // 验证 CSR 写请求无效
  def verifyCsrWriteReqInvalid(dut: ZicsrU): Unit = {
    dut.io.csrWriteReq.valid.expect(false.B)
  }

  // ============================================================================
  // 基本功能测试
  // ============================================================================

  it should "correctly execute RW operation with register operand" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（src1Sel = REG, src1Ready = true）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc00,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc00,
        expectedPrivMode = PrivMode.M
      )

      // 设置 PRF 数据和 CSR 读响应
      setPrfData(dut, rdata1 = 0xaabbccdd)
      setCsrReadResp(dut, data = 0x12345678)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 0,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true
      dut.io.commitReady.poke(true.B)
      // 验证 CSR 写请求
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc00,
        expectedPrivMode = PrivMode.M,
        expectedData = 0xaabbccdd
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 0,
        expectedPhyRd = 1,
        expectedData = 0x12345678
      )
      dut.io.cdb.ready.poke(true.B)
    }
  }

  it should "correctly execute RW operation with immediate operand" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（src1Sel = ZERO, imm = 0x11223344）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc01,
        robId = 1,
        phyRd = 2,
        src1Sel = Src1Sel.ZERO,
        imm = 0x11223344
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc01,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x55667788)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 1,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true
      dut.io.commitReady.poke(true.B)
      // 验证 CSR 写请求
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc01,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x11223344
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 1,
        expectedPhyRd = 2,
        expectedData = 0x55667788
      )
    }
  }

  it should "correctly execute RS operation with register operand" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RS 指令（src1Sel = REG, src1Ready = true）
      setZicsrDispatch(
        dut,
        ZicsrOp.RS,
        csrAddr = 0xc02,
        robId = 2,
        phyRd = 3,
        src1Sel = Src1Sel.REG,
        src1Tag = 6,
        src1Ready = true
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc02,
        expectedPrivMode = PrivMode.M
      )

      // 设置 PRF 数据和 CSR 读响应
      setPrfData(dut, rdata1 = 0x0000ff00)
      setCsrReadResp(dut, data = 0x0000ffff)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 2,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true
      dut.io.commitReady.poke(true.B)
      // 验证 CSR 写请求（newValue = 0x0000FFFF | 0x0000FF00 = 0x0000FFFF）
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc02,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x0000ffff
      )
      dut.clock.step()
      setDefaultInputs(dut)
      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 2,
        expectedPhyRd = 3,
        expectedData = 0x0000ffff
      )
    }
  }

  it should "correctly execute RS operation with immediate operand" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RS 指令（src1Sel = ZERO, imm = 0x000000FF）
      setZicsrDispatch(
        dut,
        ZicsrOp.RS,
        csrAddr = 0xc03,
        robId = 3,
        phyRd = 4,
        src1Sel = Src1Sel.ZERO,
        imm = 0x000000ff
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc03,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0xffff0000)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 3,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true
      dut.io.commitReady.poke(true.B)
      // 验证 CSR 写请求（newValue = 0xFFFF0000 | 0x000000FF = 0xFFFF00FF）
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc03,
        expectedPrivMode = PrivMode.M,
        expectedData = 0xffff00ff
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 3,
        expectedPhyRd = 4,
        expectedData = 0xffff0000
      )
    }
  }

  it should "correctly execute RC operation with register operand" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RC 指令（src1Sel = REG, src1Ready = true）
      setZicsrDispatch(
        dut,
        ZicsrOp.RC,
        csrAddr = 0xc04,
        robId = 4,
        phyRd = 5,
        src1Sel = Src1Sel.REG,
        src1Tag = 7,
        src1Ready = true
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc04,
        expectedPrivMode = PrivMode.M
      )

      // 设置 PRF 数据和 CSR 读响应
      setPrfData(dut, rdata1 = 0x0000ffff)
      setCsrReadResp(dut, data = 0xffffffff)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 4,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true
      dut.io.commitReady.poke(true.B)

      // 验证 CSR 写请求（newValue = 0xFFFFFFFF & ~0x0000FFFF = 0xFFFF0000）
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc04,
        expectedPrivMode = PrivMode.M,
        expectedData = 0xffff0000
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 4,
        expectedPhyRd = 5,
        expectedData = 0xffffffff
      )
    }
  }

  it should "correctly execute RC operation with immediate operand" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RC 指令（src1Sel = ZERO, imm = 0x0000FF00）
      setZicsrDispatch(
        dut,
        ZicsrOp.RC,
        csrAddr = 0xc05,
        robId = 5,
        phyRd = 6,
        src1Sel = Src1Sel.ZERO,
        imm = 0x0000ff00
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc05,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0xffffffff)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 5,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求（newValue = 0xFFFFFFFF & ~0x0000FF00 = 0xFFFF00FF）
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc05,
        expectedPrivMode = PrivMode.M,
        expectedData = 0xffff00ff
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 5,
        expectedPhyRd = 6,
        expectedData = 0xffffffff
      )
    }
  }

  // ============================================================================
  // 副作用规避测试
  // ============================================================================

  it should "skip read operation when rd == x0 in RW operation" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（phyRd = 0, src1Sel = ZERO, imm = 0x11223344）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc10,
        robId = 6,
        phyRd = 0,
        src1Sel = Src1Sel.ZERO,
        imm = 0x11223344
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求无效（csrReadReq.valid = false）
      verifyCsrReadReqInvalid(dut)
      dut.clock.step()

      // 验证第一次 CDB 广播（读完成，phyRd = 0）
      verifyCDBMessage(
        dut,
        expectedRobId = 6,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc10,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x11223344
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成，phyRd = 0）
      verifyCDBMessage(
        dut,
        expectedRobId = 6,
        expectedPhyRd = 0,
        expectedData = 0
      )
    }
  }

  it should "skip write operation when rs1 == x0 in RS operation" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RS 指令（src1Sel = REG, src1Ready = true, src1Tag = 0）
      setZicsrDispatch(
        dut,
        ZicsrOp.RS,
        csrAddr = 0xc11,
        robId = 7,
        phyRd = 8,
        src1Sel = Src1Sel.REG,
        src1Tag = 0,
        src1Ready = true
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc11,
        expectedPrivMode = PrivMode.M
      )

      // 设置 PRF 数据和 CSR 读响应
      setPrfData(dut, rdata1 = 0)
      setCsrReadResp(dut, data = 0x12345678)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 7,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求无效（csrWriteReq.valid = false）
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReqInvalid(dut)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 7,
        expectedPhyRd = 8,
        expectedData = 0x12345678
      )
    }
  }

  it should "skip write operation when immediate is 0 in RS operation" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RS 指令（src1Sel = ZERO, imm = 0）
      setZicsrDispatch(
        dut,
        ZicsrOp.RS,
        csrAddr = 0xc12,
        robId = 8,
        phyRd = 9,
        src1Sel = Src1Sel.ZERO,
        imm = 0
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc12,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x87654321)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 8,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求无效（csrWriteReq.valid = false）
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReqInvalid(dut)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 8,
        expectedPhyRd = 9,
        expectedData = 0x87654321
      )
    }
  }

  it should "skip write operation when rs1 == x0 in RC operation" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RC 指令（src1Sel = REG, src1Ready = true, src1Tag = 0）
      setZicsrDispatch(
        dut,
        ZicsrOp.RC,
        csrAddr = 0xc13,
        robId = 9,
        phyRd = 10,
        src1Sel = Src1Sel.REG,
        src1Tag = 0,
        src1Ready = true
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc13,
        expectedPrivMode = PrivMode.M
      )

      // 设置 PRF 数据和 CSR 读响应
      setPrfData(dut, rdata1 = 0)
      setCsrReadResp(dut, data = 0xabcdef00)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 9,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求无效（csrWriteReq.valid = false）
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReqInvalid(dut)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 9,
        expectedPhyRd = 10,
        expectedData = 0xabcdef00
      )
    }
  }

  it should "skip write operation when immediate is 0 in RC operation" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RC 指令（src1Sel = ZERO, imm = 0）
      setZicsrDispatch(
        dut,
        ZicsrOp.RC,
        csrAddr = 0xc14,
        robId = 10,
        phyRd = 11,
        src1Sel = Src1Sel.ZERO,
        imm = 0
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc14,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0xfedcba09)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 10,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求无效（csrWriteReq.valid = false）
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReqInvalid(dut)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 10,
        expectedPhyRd = 11,
        expectedData = 0xfedcba09
      )
    }
  }

  // ============================================================================
  // 序列化执行测试
  // ============================================================================

  it should "wait for ROB head signal before writing CSR" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc20,
        robId = 11,
        phyRd = 12,
        src1Sel = Src1Sel.ZERO,
        imm = 0x11111111
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc20,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x11111111)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 11,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 验证在 commitReady = false 时，不执行 CSR 写操作
      verifyCsrWriteReqInvalid(dut)
      dut.clock.step()
      verifyCsrWriteReqInvalid(dut)

      // 设置 commitReady = true 并验证 CSR 写请求正确
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc20,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x11111111
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 11,
        expectedPhyRd = 12,
        expectedData = 0x11111111
      )
    }
  }

  it should "perform two broadcasts correctly" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc21,
        robId = 12,
        phyRd = 13,
        src1Sel = Src1Sel.ZERO,
        imm = 0x22222222
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc21,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x33333333)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成，phyRd = 0）
      verifyCDBMessage(
        dut,
        expectedRobId = 12,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc21,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x22222222
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成，phyRd = 13, data = 0x33333333）
      verifyCDBMessage(
        dut,
        expectedRobId = 12,
        expectedPhyRd = 13,
        expectedData = 0x33333333
      )
    }
  }

  // ============================================================================
  // 异常处理测试
  // ============================================================================

  it should "handle CSR read exception correctly" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc30,
        robId = 13,
        phyRd = 14,
        src1Sel = Src1Sel.ZERO,
        imm = 0x44444444
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc30,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应（包含异常）
      setCsrReadResp(
        dut,
        data = 0,
        exceptionValid = true,
        exceptionCause = 2,
        exceptionTval = 0xc00
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（包含异常信息）
      verifyCDBMessage(
        dut,
        expectedRobId = 13,
        expectedPhyRd = 0,
        expectedData = 0,
        expectedExceptionValid = true,
        expectedExceptionCause = 2,
        expectedExceptionTval = 0xc00
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求（csrAddr = 0, data = 0）
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0,
        expectedPrivMode = PrivMode.M,
        expectedData = 0
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（包含异常信息）
      verifyCDBMessage(
        dut,
        expectedRobId = 13,
        expectedPhyRd = 14,
        expectedData = 0,
        expectedExceptionValid = true,
        expectedExceptionCause = 2,
        expectedExceptionTval = 0xc00
      )
    }
  }

  it should "handle CSR write exception correctly" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc31,
        robId = 14,
        phyRd = 15,
        src1Sel = Src1Sel.ZERO,
        imm = 0x55555555
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc31,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x12345678)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 14,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 和 CSR 写响应（包含异常）并验证 CSR 写请求
      dut.io.commitReady.poke(true.B)
      setCsrWriteResp(
        dut,
        exceptionValid = true,
        exceptionCause = 3,
        exceptionTval = 0xc01
      )
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc31,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x55555555
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（包含异常信息）
      verifyCDBMessage(
        dut,
        expectedRobId = 14,
        expectedPhyRd = 15,
        expectedData = 0x12345678,
        expectedExceptionValid = true,
        expectedExceptionCause = 3,
        expectedExceptionTval = 0xc01
      )
    }
  }

  // ============================================================================
  // 冲刷处理测试
  // ============================================================================

  it should "flush correctly in WAIT_READ state" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（branchMask = 1）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc40,
        robId = 15,
        phyRd = 16,
        branchMask = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0x66666666
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 设置分支冲刷（branchOH = 1 匹配 branchMask）
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证状态重置为 IDLE（可以接收新的指令）
      dut.io.zicsrReq.ready.expect(true.B)

      // 发送新的指令验证可以接收
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc41,
        robId = 16,
        phyRd = 17,
        src1Sel = Src1Sel.ZERO,
        imm = 0x77777777
      )
      dut.clock.step()
      setDefaultInputs(dut)
    }
  }

  it should "flush correctly in WAIT_CDB1 state" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（branchMask = 1）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc42,
        robId = 17,
        phyRd = 18,
        branchMask = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0x88888888
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc42,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x12345678)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 17,
        expectedPhyRd = 0,
        expectedData = 0
      )

      // 设置分支冲刷（branchOH = 1 匹配 branchMask）
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证状态重置为 IDLE（可以接收新的指令）
      dut.io.zicsrReq.ready.expect(true.B)

      // 发送新的指令验证可以接收
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc43,
        robId = 18,
        phyRd = 19,
        src1Sel = Src1Sel.ZERO,
        imm = 0x99999999
      )
      dut.clock.step()
      setDefaultInputs(dut)
    }
  }

  it should "flush correctly in WAIT_HEAD state" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（branchMask = 1）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc44,
        robId = 19,
        phyRd = 20,
        branchMask = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0xaaaaaaaa
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc44,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x12345678)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 19,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置分支冲刷（branchOH = 1 匹配 branchMask）
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证状态重置为 IDLE（可以接收新的指令）
      dut.io.zicsrReq.ready.expect(true.B)

      // 发送新的指令验证可以接收
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc45,
        robId = 20,
        phyRd = 21,
        src1Sel = Src1Sel.ZERO,
        imm = 0xbbbbbbbb
      )
      dut.clock.step()
      setDefaultInputs(dut)
    }
  }

  it should "flush correctly in WAIT_CDB2 state" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（branchMask = 1）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc46,
        robId = 21,
        phyRd = 22,
        branchMask = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0xcccccccc
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc46,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x12345678)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 21,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true
      dut.io.commitReady.poke(true.B)
      // 验证 CSR 写请求
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc46,
        expectedPrivMode = PrivMode.M,
        expectedData = 0xcccccccc
      )
      dut.clock.step()

      // 设置分支冲刷（branchOH = 1 匹配 branchMask）
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证状态重置为 IDLE（可以接收新的指令）
      dut.io.zicsrReq.ready.expect(true.B)

      // 发送新的指令验证可以接收
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc47,
        robId = 22,
        phyRd = 23,
        src1Sel = Src1Sel.ZERO,
        imm = 0xdddddddd
      )
      dut.clock.step()
      setDefaultInputs(dut)
    }
  }

  it should "update branch mask when branchOH does not match" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（branchMask = 5）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc48,
        robId = 23,
        phyRd = 24,
        branchMask = 5,
        src1Sel = Src1Sel.ZERO,
        imm = 0x11111111
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc48,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x12345678)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 23,
        expectedPhyRd = 0,
        expectedData = 0
      )

      // 设置分支冲刷（branchOH = 2 不匹配 branchMask）
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(2.U)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 branchMask 更新为 5 & ~2 = 5
      // 验证状态不重置（仍然在 WAIT_CDB1 状态，不能接收新的指令）
      dut.io.zicsrReq.ready.expect(false.B)
    }
  }

  // ============================================================================
  // 边界情况测试
  // ============================================================================

  it should "wake up operand through CDB" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（src1Sel = REG, src1Ready = false, src1Tag = 5）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc50,
        robId = 24,
        phyRd = 25,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = false
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证在 src1Ready = false 时，不执行 CSR 读操作
      verifyCsrReadReqInvalid(dut)

      // 设置 CDB 广播（phyRd = 5）
      dut.io.cdbIn.valid.poke(true.B)
      dut.io.cdbIn.bits.phyRd.poke(5.U)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证操作数就绪后，执行 CSR 读操作
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc50,
        expectedPrivMode = PrivMode.M
      )

      // 设置 PRF 数据和 CSR 读响应
      setPrfData(dut, rdata1 = 0x99998888)
      setCsrReadResp(dut, data = 0x77776666)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 24,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc50,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x99998888
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 24,
        expectedPhyRd = 25,
        expectedData = 0x77776666
      )
    }
  }

  it should "correctly handle privilege mode" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（privMode = PrivMode.S）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc51,
        robId = 25,
        phyRd = 26,
        privMode = PrivMode.S,
        src1Sel = Src1Sel.ZERO,
        imm = 0x22222222
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求的特权级为 S
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc51,
        expectedPrivMode = PrivMode.S
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x33333333)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 25,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求的特权级为 S
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc51,
        expectedPrivMode = PrivMode.S,
        expectedData = 0x22222222
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 25,
        expectedPhyRd = 26,
        expectedData = 0x33333333
      )
    }
  }

  it should "correctly handle CSR address boundaries" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（csrAddr = 0）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0,
        robId = 26,
        phyRd = 27,
        src1Sel = Src1Sel.ZERO,
        imm = 0x11111111
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求的地址为 0
      verifyCsrReadReq(dut, expectedCsrAddr = 0, expectedPrivMode = PrivMode.M)

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x11111111)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 26,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x11111111
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 26,
        expectedPhyRd = 27,
        expectedData = 0x11111111
      )
    }

    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（csrAddr = 0xFFF）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xfff,
        robId = 27,
        phyRd = 28,
        src1Sel = Src1Sel.ZERO,
        imm = 0x22222222
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求的地址为 0xFFF
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xfff,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x33333333)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 27,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xfff,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x22222222
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 27,
        expectedPhyRd = 28,
        expectedData = 0x33333333
      )
    }
  }

  it should "correctly handle ROB ID and physical register ID" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令（robId = 31, phyRd = 127）
      setZicsrDispatch(
        dut,
        ZicsrOp.RW,
        csrAddr = 0xc52,
        robId = 31,
        phyRd = 127,
        src1Sel = Src1Sel.ZERO,
        imm = 0x44444444
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证 CSR 读请求正确
      verifyCsrReadReq(
        dut,
        expectedCsrAddr = 0xc52,
        expectedPrivMode = PrivMode.M
      )

      // 设置 CSR 读响应
      setCsrReadResp(dut, data = 0x55555555)
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第一次 CDB 广播（读完成）
      verifyCDBMessage(
        dut,
        expectedRobId = 31,
        expectedPhyRd = 0,
        expectedData = 0
      )
      dut.io.cdb.ready.poke(true.B)
      dut.clock.step()

      // 设置 commitReady = true 并验证 CSR 写请求
      dut.io.commitReady.poke(true.B)
      verifyCsrWriteReq(
        dut,
        expectedCsrAddr = 0xc52,
        expectedPrivMode = PrivMode.M,
        expectedData = 0x44444444
      )
      dut.clock.step()
      setDefaultInputs(dut)

      // 验证第二次 CDB 广播（写完成，robId = 31, phyRd = 127）
      verifyCDBMessage(
        dut,
        expectedRobId = 31,
        expectedPhyRd = 127,
        expectedData = 0x55555555
      )
    }
  }
}
