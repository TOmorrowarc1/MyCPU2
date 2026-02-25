package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ZicsrUTest extends AnyFlatSpec with ChiselScalatestTester {

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

  // ============================================================================
  // 1. 基本功能测试 - RW 操作（CSRRW/CSRRWI）
  // ============================================================================

  "ZicsrU" should "正确执行 RW 操作（CSRRW：新值 = RS1，返回旧值）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRW 指令：CSR 原值为 0x12345678，写入 0xABCDEF00
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300, // mstatus
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段，发送 CSR 读请求和 PRF 读请求
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.io.prfReq.valid.expect(true.B)
      dut.io.prfReq.bits.raddr1.expect(5.U)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 第四个周期：写回阶段，发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0xABCDEF00L)
      dut.clock.step()

      // 第五个周期：回到 IDLE
      setCsrWriteResp(dut)
      dut.io.zicsrReq.ready.expect(true.B)
      dut.io.cdb.valid.expect(false.B)
    }
  }

  it should "正确执行 RW 操作（CSRRWI：新值 = Imm，返回旧值）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRWI 指令：CSR 原值为 0x12345678，写入立即数 0x100
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0x100
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setCsrReadResp(dut, data = 0x12345678L)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 第四个周期：写回阶段，发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0x100L)
      dut.clock.step()
    }
  }

  // ============================================================================
  // 2. 基本功能测试 - RS 操作（CSRRS/CSRRSI）
  // ============================================================================

  it should "正确执行 RS 操作（CSRRS：新值 = 旧值 | RS1，返回旧值）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRS 指令：CSR 原值为 0x12345678，RS1 = 0x000000FF
      // 新值 = 0x12345678 | 0x000000FF = 0x123456FF
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RS,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0x000000FFL)
      setCsrReadResp(dut, data = 0x12345678L)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 第四个周期：写回阶段，发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0x123456FFL)
      dut.clock.step()
    }
  }

  it should "正确执行 RS 操作（CSRRSI：新值 = 旧值 | Imm，返回旧值）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRSI 指令：CSR 原值为 0x12345600，立即数 = 0x000000FF
      // 新值 = 0x12345600 | 0x000000FF = 0x123456FF
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RS,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0x000000FFL
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setCsrReadResp(dut, data = 0x12345600L)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345600L)
      dut.clock.step()

      // 第四个周期：写回阶段，发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0x123456FFL)
      dut.clock.step()
    }
  }

  // ============================================================================
  // 3. 基本功能测试 - RC 操作（CSRRC/CSRRCI）
  // ============================================================================

  it should "正确执行 RC 操作（CSRRC：新值 = 旧值 & ~RS1，返回旧值）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRC 指令：CSR 原值为 0x123456FF，RS1 = 0x000000FF
      // 新值 = 0x123456FF & ~0x000000FF = 0x123456FF & 0xFFFFFF00 = 0x12345600
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RC,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0x000000FFL)
      setCsrReadResp(dut, data = 0x123456FFL)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x123456FFL)
      dut.clock.step()

      // 第四个周期：写回阶段，发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0x12345600L)
      dut.clock.step()
    }
  }

  it should "正确执行 RC 操作（CSRRCI：新值 = 旧值 & ~Imm，返回旧值）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRCI 指令：CSR 原值为 0x123456FF，立即数 = 0x000000FF
      // 新值 = 0x123456FF & ~0x000000FF = 0x12345600
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RC,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0x000000FFL
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setCsrReadResp(dut, data = 0x123456FFL)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x123456FFL)
      dut.clock.step()

      // 第四个周期：写回阶段，发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0x12345600L)
      dut.clock.step()
    }
  }

  // ============================================================================
  // 4. 边界条件测试 - 副作用规避（rd == x0）
  // ============================================================================

  it should "正确处理 RW 操作的副作用规避（rd == x0 时不读 CSR）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRW 指令，但 rd == x0
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 0, // rd == x0
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段，不应该发送 CSR 读请求
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      
      dut.io.csrReadReq.valid.expect(false.B) // 不应该读 CSR
      dut.io.prfReq.valid.expect(true.B)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播的旧值应该为 0
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 0, expectedData = 0L)
      dut.clock.step()

      // 第四个周期：写回阶段，应该发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0xABCDEF00L)
      dut.clock.step()
    }
  }

  it should "正确处理 RS 操作的副作用规避（rs1 == x0 时不写 CSR）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRS 指令，但 rs1 == x0（通过 Src1Sel.ZERO 和 imm == 0）
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RS,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0 // rs1 == x0 或 imm == 0
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段，应该发送 CSR 读请求
      dut.io.zicsrReq.valid.poke(false.B)
      setCsrReadResp(dut, data = 0x12345678L)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 第四个周期：写回阶段，不应该发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      dut.io.csrWriteReq.valid.expect(false.B) // 不应该写 CSR
      dut.clock.step()
    }
  }

  it should "正确处理 RC 操作的副作用规避（rs1 == x0 时不写 CSR）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 CSRRC 指令，但 rs1 == x0（通过 Src1Sel.ZERO 和 imm == 0）
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RC,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.ZERO,
        imm = 0 // rs1 == x0 或 imm == 0
      )
      
      // 第一个周期：接收请求
      dut.io.zicsrReq.ready.expect(true.B)
      dut.clock.step()

      // 第二个周期：计算阶段，应该发送 CSR 读请求
      dut.io.zicsrReq.valid.poke(false.B)
      setCsrReadResp(dut, data = 0x12345678L)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()

      // 第三个周期：等待 ROB 头部，广播旧值
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 第四个周期：写回阶段，不应该发送 CSR 写请求
      dut.io.commitReady.poke(false.B)
      
      dut.io.csrWriteReq.valid.expect(false.B) // 不应该写 CSR
      dut.clock.step()
    }
  }

  // ============================================================================
  // 5. 状态机测试
  // ============================================================================

  it should "正确执行状态机转换：IDLE → WAIT_OPERANDS → WAIT_ROB_HEAD → IDLE" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 初始状态：IDLE，ready 为 true
      dut.io.zicsrReq.ready.expect(true.B)
      dut.io.cdb.valid.expect(false.B)

      // 发送请求，状态转换为 WAIT_OPERANDS
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 状态：WAIT_OPERANDS，ready 为 false
      dut.io.zicsrReq.valid.poke(false.B)
      dut.io.zicsrReq.ready.expect(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      dut.clock.step()

      // 状态：WAIT_ROB_HEAD，等待 commitReady
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      dut.io.cdb.valid.expect(true.B)
      dut.clock.step()

      // 状态：回到 IDLE
      dut.io.commitReady.poke(false.B)
      setCsrWriteResp(dut)
      dut.io.zicsrReq.ready.expect(true.B)
      dut.io.cdb.valid.expect(false.B)
    }
  }

  it should "正确处理操作数未就绪的情况（等待 src1Ready）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送请求，但操作数未就绪
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = false // 操作数未就绪
      )
      dut.clock.step()

      // 状态：WAIT_OPERANDS，等待操作数就绪
      dut.io.zicsrReq.valid.poke(false.B)
      dut.io.zicsrReq.ready.expect(false.B)
      
      // 操作数未就绪，不应该发送 CSR 读请求
      dut.io.csrReadReq.valid.expect(false.B)
      dut.clock.step()

      // 操作数就绪
      dut.io.zicsrReq.bits.data.src1Ready.poke(true.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      
      // 现在应该发送 CSR 读请求
      verifyCsrReadReq(dut, expectedCsrAddr = 0x300)
      dut.clock.step()
    }
  }

  // ============================================================================
  // 6. 冲刷处理测试
  // ============================================================================

  it should "正确处理分支冲刷（在 WAIT_OPERANDS 状态）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送请求
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        branchMask = 5,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 状态：WAIT_OPERANDS
      dut.io.zicsrReq.valid.poke(false.B)
      dut.io.zicsrReq.ready.expect(false.B)
      dut.clock.step()

      // 触发分支冲刷，branchOH 匹配
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(5.U)
      dut.clock.step()

      // 应该回到 IDLE 状态
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(0.U)
      dut.io.zicsrReq.ready.expect(true.B)
      dut.io.cdb.valid.expect(false.B)
    }
  }

  it should "正确处理分支冲刷（在 WAIT_ROB_HEAD 状态）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送请求并执行到 WAIT_ROB_HEAD 状态
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        branchMask = 5,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      dut.clock.step()

      // 状态：WAIT_ROB_HEAD
      dut.io.prfData.valid.poke(false.B)
      dut.io.cdb.valid.expect(true.B)
      dut.clock.step()

      // 触发分支冲刷，branchOH 匹配
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(5.U)
      dut.clock.step()

      // 应该回到 IDLE 状态
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(0.U)
      dut.io.zicsrReq.ready.expect(true.B)
      dut.io.cdb.valid.expect(false.B)
    }
  }

  it should "正确处理分支冲刷（branchOH 不匹配时不冲刷）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送请求
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        branchMask = 5,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 状态：WAIT_OPERANDS
      dut.io.zicsrReq.valid.poke(false.B)
      dut.io.zicsrReq.ready.expect(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      dut.clock.step()

      // 触发分支冲刷，但 branchOH 不匹配
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke("b10".U) // 不匹配 branchMask = 5 (b0101)
      dut.clock.step()

      // 不应该冲刷，继续正常执行
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(0.U)
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()
    }
  }

  it should "正确处理分支依赖移除（branchFlush 为 false 时）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送请求，设置 branchMask = 7 (b0111)
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        branchMask = 7,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 状态：WAIT_OPERANDS
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      dut.clock.step()

      // branchOH = 2 (b0010)，branchFlush = false，只移除对应的分支依赖
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(2.U)
      dut.clock.step()

      // 应该继续执行，但 branchMask 被更新
      // branchMask = 7 & ~2 = 7 & 0b1101 = 5 (b0101)
      // 由于 branchMask 不为 0，指令继续执行
      dut.io.branchOH.poke(0.U)
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()
    }
  }

  // ============================================================================
  // 7. 异常处理测试
  // ============================================================================

  it should "正确处理 CSR 读异常（不发送写请求）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 计算阶段，CSR 读返回异常
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(
        dut,
        data = 0x12345678L,
        exceptionValid = true,
        exceptionCause = 2, // ILLEGAL_INSTRUCTION
        exceptionTval = 0x80000000L
      )
      dut.clock.step()

      // 等待 ROB 头部，广播异常信息
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      
      verifyCDBMessage(
        dut,
        expectedRobId = 0,
        expectedPhyRd = 1,
        expectedData = 0x12345678L,
        expectedExceptionValid = true,
        expectedExceptionCause = 2,
        expectedExceptionTval = 0x80000000L
      )
      dut.clock.step()

      // 写回阶段，不应该发送 CSR 写请求（因为有异常）
      dut.io.commitReady.poke(false.B)
      
      dut.io.csrWriteReq.valid.expect(false.B) // 不应该写 CSR
      dut.clock.step()
    }
  }

  it should "正确处理 CSR 写异常（保留读异常）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 RW 指令
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 计算阶段，CSR 读返回异常
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(
        dut,
        data = 0x12345678L,
        exceptionValid = true,
        exceptionCause = 2,
        exceptionTval = 0x80000000L
      )
      dut.clock.step()

      // 等待 ROB 头部
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      dut.clock.step()

      // 写回阶段，CSR 写也返回异常，但应该保留读异常
      dut.io.commitReady.poke(false.B)
      setCsrWriteResp(
        dut,
        exceptionValid = true,
        exceptionCause = 5, // LOAD_ACCESS_FAULT
        exceptionTval = 0x80000004L
      )
      dut.clock.step()

      // 异常信息应该保留读异常（cause = 2）
      verifyCDBMessage(
        dut,
        expectedRobId = 0,
        expectedPhyRd = 0, // writeBack 后 phyRd 为 0
        expectedData = 0L,
        expectedExceptionValid = true,
        expectedExceptionCause = 2, // 应该是读异常的 cause
        expectedExceptionTval = 0x80000000L
      )
      dut.clock.step()
    }
  }

  // ============================================================================
  // 8. 综合测试
  // ============================================================================

  it should "正确执行多条 CSR 指令序列化执行" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 指令1: CSRRW x1, mstatus, x5
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 指令1 计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      dut.clock.step()

      // 指令1 等待 ROB 头部
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 指令1 写回阶段
      dut.io.commitReady.poke(false.B)
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0xABCDEF00L)
      dut.clock.step()

      // 指令1 完成，回到 IDLE
      setCsrWriteResp(dut)
      dut.clock.step()

      // 指令2: CSRRS x2, mstatus, x6
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RS,
        csrAddr = 0x300,
        robId = 1,
        phyRd = 2,
        src1Sel = Src1Sel.REG,
        src1Tag = 6,
        src1Ready = true
      )
      dut.clock.step()

      // 指令2 计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0x000000FFL)
      setCsrReadResp(dut, data = 0xABCDEF00L)
      dut.clock.step()

      // 指令2 等待 ROB 头部
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 2, expectedData = 0xABCDEF00L)
      dut.clock.step()

      // 指令2 写回阶段
      dut.io.commitReady.poke(false.B)
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0xABCDEFFFL)
      dut.clock.step()
    }
  }

  it should "正确处理 CDB 反压（输出 ready 为 false）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送请求
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      dut.clock.step()

      // 等待 ROB 头部，但 CDB ready 为 false
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      dut.io.cdb.ready.poke(false.B)
      
      dut.io.cdb.valid.expect(true.B)
      dut.io.cdb.ready.expect(false.B) // CDB ready 为 false
      dut.clock.step()

      // 设置 CDB ready 为 true
      dut.io.cdb.ready.poke(true.B)
      
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()
    }
  }

  it should "正确处理所有 ZicsrOp 类型的指令" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      val testCases = Seq(
        (ZicsrOp.RW, 0x12345678L, 0xABCDEF00L, 0xABCDEF00L), // RW: 新值 = RS1
        (ZicsrOp.RS, 0x12345678L, 0x000000FFL, 0x123456FFL), // RS: 新值 = 旧值 | RS1
        (ZicsrOp.RC, 0x123456FFL, 0x000000FFL, 0x12345600L)  // RC: 新值 = 旧值 & ~RS1
      )

      for ((op, oldValue, operand, expectedNewValue) <- testCases) {
        // 发送请求
        setZicsrDispatch(
          dut,
          zicsrOp = op,
          csrAddr = 0x300,
          robId = 0,
          phyRd = 1,
          src1Sel = Src1Sel.REG,
          src1Tag = 5,
          src1Ready = true
        )
        dut.clock.step()

        // 计算阶段
        dut.io.zicsrReq.valid.poke(false.B)
        setPrfData(dut, rdata1 = operand)
        setCsrReadResp(dut, data = oldValue)
        dut.clock.step()

        // 等待 ROB 头部
        dut.io.prfData.valid.poke(false.B)
        dut.io.commitReady.poke(true.B)
        verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = oldValue)
        dut.clock.step()

        // 写回阶段
        dut.io.commitReady.poke(false.B)
        if (op != ZicsrOp.NOP) {
          verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = expectedNewValue)
        }
        dut.clock.step()

        // 回到 IDLE
        setCsrWriteResp(dut)
        setDefaultInputs(dut)
        dut.clock.step()
      }
    }
  }

  it should "正确处理边界值（CSR 地址、数据、robId、phyRd）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 测试边界值
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0xFFF, // 最大 CSR 地址
        robId = 31,     // 最大 robId
        phyRd = 127,    // 最大 phyRd
        src1Sel = Src1Sel.REG,
        src1Tag = 127,
        src1Ready = true
      )
      dut.clock.step()

      // 计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xFFFFFFFFL)
      setCsrReadResp(dut, data = 0x00000000L)
      
      verifyCsrReadReq(dut, expectedCsrAddr = 0xFFF)
      dut.clock.step()

      // 等待 ROB 头部
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      verifyCDBMessage(dut, expectedRobId = 31, expectedPhyRd = 127, expectedData = 0L)
      dut.clock.step()

      // 写回阶段
      dut.io.commitReady.poke(false.B)
      verifyCsrWriteReq(dut, expectedCsrAddr = 0xFFF, expectedData = 0xFFFFFFFFL)
      dut.clock.step()
    }
  }

  it should "正确处理不同特权级模式（U/S/M）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      val privModes = Seq(PrivMode.U, PrivMode.S, PrivMode.M)

      for (privMode <- privModes) {
        // 发送请求
        setZicsrDispatch(
          dut,
          zicsrOp = ZicsrOp.RW,
          csrAddr = 0x300,
          robId = 0,
          phyRd = 1,
          privMode = privMode,
          src1Sel = Src1Sel.REG,
          src1Tag = 5,
          src1Ready = true
        )
        dut.clock.step()

        // 计算阶段
        dut.io.zicsrReq.valid.poke(false.B)
        setPrfData(dut, rdata1 = 0xABCDEF00L)
        setCsrReadResp(dut, data = 0x12345678L)
        
        verifyCsrReadReq(dut, expectedCsrAddr = 0x300, expectedPrivMode = privMode)
        dut.clock.step()

        // 等待 ROB 头部
        dut.io.prfData.valid.poke(false.B)
        dut.io.commitReady.poke(true.B)
        verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
        dut.clock.step()

        // 写回阶段
        dut.io.commitReady.poke(false.B)
        verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedPrivMode = privMode, expectedData = 0xABCDEF00L)
        dut.clock.step()

        // 回到 IDLE
        setCsrWriteResp(dut)
        setDefaultInputs(dut)
        dut.clock.step()
      }
    }
  }

  it should "正确处理 NOP 操作（无操作）" in {
    test(new ZicsrU) { dut =>
      setDefaultInputs(dut)

      // 发送 NOP 操作
      setZicsrDispatch(
        dut,
        zicsrOp = ZicsrOp.NOP,
        csrAddr = 0x300,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src1Tag = 5,
        src1Ready = true
      )
      dut.clock.step()

      // 计算阶段
      dut.io.zicsrReq.valid.poke(false.B)
      setPrfData(dut, rdata1 = 0xABCDEF00L)
      setCsrReadResp(dut, data = 0x12345678L)
      dut.clock.step()

      // 等待 ROB 头部
      dut.io.prfData.valid.poke(false.B)
      dut.io.commitReady.poke(true.B)
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 写回阶段，新值应该等于旧值（NOP 不改变值）
      dut.io.commitReady.poke(false.B)
      verifyCsrWriteReq(dut, expectedCsrAddr = 0x300, expectedData = 0x12345678L)
      dut.clock.step()
    }
  }
}
