package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import os.group.set

class RSTest extends AnyFlatSpec with ChiselScalatestTester {

  // ============================================================================
  // Dispatcher 测试用例
  // ============================================================================

  // 辅助函数：设置 Dispatcher 默认输入信号
  def setDispatcherDefaults(dut: Dispatcher): Unit = {
    dut.io.globalFlush.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.aluRS.ready.poke(true.B)
    dut.io.bruRS.ready.poke(true.B)
    dut.io.lsu.ready.poke(true.B)
    dut.io.zicsr.ready.poke(true.B)
  }

  // 辅助函数：设置 Decoder 输入
  def setDecoderInput(
      dut: Dispatcher,
      robId: Int = 0,
      aluOp: ALUOp.Type = ALUOp.NOP,
      bruOp: BRUOp.Type = BRUOp.NOP,
      lsuOp: LSUOp.Type = LSUOp.NOP,
      lsuWidth: LSUWidth.Type = LSUWidth.WORD,
      lsuSign: LSUsign.Type = LSUsign.SIGNED,
      zicsrOp: ZicsrOp.Type = ZicsrOp.NOP,
      op1Src: Src1Sel.Type = Src1Sel.REG,
      op2Src: Src2Sel.Type = Src2Sel.REG,
      pc: Long = 0x80000000L,
      imm: Long = 0L,
      csrAddr: Int = 0,
      privMode: PrivMode.Type = PrivMode.M
  ): Unit = {
    dut.io.decoder.valid.poke(true.B)
    dut.io.decoder.bits.robId.poke(robId.U)
    dut.io.decoder.bits.microOp.aluOp.poke(aluOp)
    dut.io.decoder.bits.microOp.bruOp.poke(bruOp)
    dut.io.decoder.bits.microOp.lsuOp.poke(lsuOp)
    dut.io.decoder.bits.microOp.lsuWidth.poke(lsuWidth)
    dut.io.decoder.bits.microOp.lsuSign.poke(lsuSign)
    dut.io.decoder.bits.microOp.zicsrOp.poke(zicsrOp)
    dut.io.decoder.bits.microOp.op1Src.poke(op1Src)
    dut.io.decoder.bits.microOp.op2Src.poke(op2Src)
    dut.io.decoder.bits.pc.poke(pc.U)
    dut.io.decoder.bits.imm.poke(imm.U)
    dut.io.decoder.bits.csrAddr.poke(csrAddr.U)
    dut.io.decoder.bits.privMode.poke(privMode)
    dut.io.decoder.bits.prediction.taken.poke(false.B)
    dut.io.decoder.bits.prediction.targetPC.poke((pc + 4).U)
    dut.io.decoder.bits.exception.valid.poke(false.B)
  }

  // 辅助函数：设置 RAT 输入
  def setRATInput(
      dut: Dispatcher,
      phyRs1: Int = 0,
      rs1Ready: Boolean = true,
      phyRs2: Int = 1,
      rs2Ready: Boolean = true,
      phyRd: Int = 2,
      snapshotOH: Int = 0,
      branchMask: Int = 0
  ): Unit = {
    dut.io.rat.valid.poke(true.B)
    dut.io.rat.bits.phyRs1.poke(phyRs1.U)
    dut.io.rat.bits.rs1Ready.poke(rs1Ready.B)
    dut.io.rat.bits.phyRs2.poke(phyRs2.U)
    dut.io.rat.bits.rs2Ready.poke(rs2Ready.B)
    dut.io.rat.bits.phyRd.poke(phyRd.U)
    dut.io.rat.bits.snapshotOH.poke(snapshotOH.U)
    dut.io.rat.bits.branchMask.poke(branchMask.U)
  }

  "Dispatcher" should "正确分派 ALU 指令到 AluRS" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)
      setDecoderInput(dut, aluOp = ALUOp.ADD)
      setRATInput(dut, phyRs1 = 5, phyRs2 = 6, phyRd = 7)

      // 验证握手成功
      dut.io.decoder.ready.expect(true.B)
      dut.io.rat.ready.expect(true.B)

      // 验证 ALU 指令分派
      dut.io.aluRS.valid.expect(true.B)
      dut.io.aluRS.bits.aluOp.expect(ALUOp.ADD)
      dut.io.aluRS.bits.data.src1Tag.expect(5.U)
      dut.io.aluRS.bits.data.src2Tag.expect(6.U)
      dut.io.aluRS.bits.data.src1Ready.expect(true.B)
      dut.io.aluRS.bits.data.src2Ready.expect(true.B)
      dut.io.aluRS.bits.robId.expect(0.U)
      dut.io.aluRS.bits.phyRd.expect(7.U)

      // 验证其他 RS 不接收指令
      dut.io.bruRS.valid.expect(false.B)
      dut.io.lsu.valid.expect(false.B)
      dut.io.zicsr.valid.expect(false.B)
    }
  }

  it should "正确分派 BRU 指令到 BruRS" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)
      setDecoderInput(dut, aluOp = ALUOp.NOP, bruOp = BRUOp.BEQ)
      setRATInput(dut, phyRs1 = 5, phyRs2 = 6, phyRd = 7)

      // 验证 BRU 指令分派
      dut.io.bruRS.valid.expect(true.B)
      dut.io.bruRS.bits.bruOp.expect(BRUOp.BEQ)
      dut.io.bruRS.bits.data.src1Tag.expect(5.U)

      // 验证其他 RS 不接收指令
      dut.io.aluRS.valid.expect(false.B)
      dut.io.lsu.valid.expect(false.B)
      dut.io.zicsr.valid.expect(false.B)
    }
  }

  it should "正确分派 LSU 指令到 LSU" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)
      setDecoderInput(
        dut,
        aluOp = ALUOp.NOP,
        lsuOp = LSUOp.LOAD,
        lsuWidth = LSUWidth.WORD,
        lsuSign = LSUsign.SIGNED
      )
      setRATInput(dut, phyRs1 = 5, phyRs2 = 6, phyRd = 7)

      // 验证 LSU 指令分派
      dut.io.lsu.valid.expect(true.B)
      dut.io.lsu.bits.opcode.expect(LSUOp.LOAD)
      dut.io.lsu.bits.data.src1Tag.expect(5.U)

      // 验证其他 RS 不接收指令
      dut.io.aluRS.valid.expect(false.B)
      dut.io.bruRS.valid.expect(false.B)
      dut.io.zicsr.valid.expect(false.B)
    }
  }

  it should "正确分派 Zicsr 指令到 ZicsrU" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)
      setDecoderInput(
        dut,
        aluOp = ALUOp.NOP,
        zicsrOp = ZicsrOp.RW,
        csrAddr = 0x300
      )
      setRATInput(dut, phyRs1 = 5, phyRs2 = 6, phyRd = 7)

      // 验证 Zicsr 指令分派
      dut.io.zicsr.valid.expect(true.B)
      dut.io.zicsr.bits.zicsrOp.expect(ZicsrOp.RW)
      dut.io.zicsr.bits.csrAddr.expect(0x300.U)

      // 验证其他 RS 不接收指令
      dut.io.aluRS.valid.expect(false.B)
      dut.io.bruRS.valid.expect(false.B)
      dut.io.lsu.valid.expect(false.B)
    }
  }

  it should "正确处理握手控制：downstream not ready" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)
      setDecoderInput(dut, aluOp = ALUOp.ADD)
      setRATInput(dut)

      // 设置 AluRS 不 ready
      dut.io.aluRS.ready.poke(false.B)

      // 验证握手失败
      dut.io.decoder.ready.expect(false.B)
      dut.io.rat.ready.expect(false.B)
      dut.io.aluRS.valid.expect(true.B)
      dut.clock.step()

      dut.io.aluRS.ready.poke(true.B)
      dut.io.decoder.ready.expect(true.B)
      dut.io.rat.ready.expect(true.B)
      dut.io.aluRS.valid.expect(true.B)
      dut.clock.step()

      dut.io.decoder.valid.poke(false.B)
      setRATInput(dut)
      dut.io.aluRS.ready.poke(true.B)
      dut.io.decoder.ready.expect(true.B)
      dut.io.rat.ready.expect(true.B)
      dut.io.aluRS.valid.expect(false.B)
    }
  }

  it should "正确处理 globalFlush 信号" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)
      setDecoderInput(dut, aluOp = ALUOp.ADD)
      setRATInput(dut)

      // 设置 globalFlush
      dut.io.globalFlush.poke(true.B)

      // 验证所有输出无效
      dut.io.decoder.ready.expect(false.B)
      dut.io.rat.ready.expect(false.B)
      dut.io.aluRS.valid.expect(false.B)
      dut.io.bruRS.valid.expect(false.B)
      dut.io.lsu.valid.expect(false.B)
      dut.io.zicsr.valid.expect(false.B)
    }
  }

  it should "正确处理 branchFlush 信号" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)
      setDecoderInput(dut, aluOp = ALUOp.ADD)
      setRATInput(dut)

      // 设置 branchFlush
      dut.io.branchFlush.poke(true.B)

      // 验证所有输出无效
      dut.io.decoder.ready.expect(false.B)
      dut.io.rat.ready.expect(false.B)
      dut.io.aluRS.valid.expect(false.B)
      dut.io.bruRS.valid.expect(false.B)
      dut.io.lsu.valid.expect(false.B)
      dut.io.zicsr.valid.expect(false.B)
    }
  }

  it should "正确处理输入无效的情况" in {
    test(new Dispatcher) { dut =>
      setDispatcherDefaults(dut)

      // 设置 decoder 输入无效
      dut.io.decoder.valid.poke(false.B)
      setRATInput(dut)

      // 验证所有输出无效
      dut.io.aluRS.valid.expect(false.B)
      dut.io.bruRS.valid.expect(false.B)
      dut.io.lsu.valid.expect(false.B)
      dut.io.zicsr.valid.expect(false.B)
    }
  }

  // ============================================================================
  // AluRS 测试用例
  // ============================================================================

  // 辅助函数：设置 AluRS 默认输入信号
  def setAluRSDefaults(dut: AluRS): Unit = {
    dut.io.globalFlush.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.branchOH.poke(0.U)
    dut.io.cdb.valid.poke(false.B)
    dut.io.prfRead.ready.poke(true.B)
    dut.io.aluReq.ready.poke(true.B)
  }

  // 辅助函数：设置 AluRS 入队请求
  def setAluRSRequest(
      dut: AluRS,
      aluOp: ALUOp.Type = ALUOp.ADD,
      robId: Int = 0,
      phyRd: Int = 10,
      src1Tag: Int = 1,
      src2Tag: Int = 2,
      src1Sel: Src1Sel.Type = Src1Sel.REG,
      src2Sel: Src2Sel.Type = Src2Sel.REG,
      src1Ready: Boolean = true,
      src2Ready: Boolean = true,
      imm: Long = 0L,
      pc: Long = 0x80000000L,
      branchMask: Int = 0
  ): Unit = {
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.aluOp.poke(aluOp)
    dut.io.req.bits.robId.poke(robId.U)
    dut.io.req.bits.phyRd.poke(phyRd.U)
    dut.io.req.bits.data.src1Tag.poke(src1Tag.U)
    dut.io.req.bits.data.src2Tag.poke(src2Tag.U)
    dut.io.req.bits.data.src1Sel.poke(src1Sel)
    dut.io.req.bits.data.src2Sel.poke(src2Sel)
    dut.io.req.bits.data.src1Ready.poke(src1Ready.B)
    dut.io.req.bits.data.src2Ready.poke(src2Ready.B)
    dut.io.req.bits.data.imm.poke(imm.U)
    dut.io.req.bits.data.pc.poke(pc.U)
    dut.io.req.bits.branchMask.poke(branchMask.U)
    dut.io.req.bits.exception.valid.poke(false.B)
  }

  "AluRS" should "成功入队一条指令" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)
      setAluRSRequest(dut, aluOp = ALUOp.ADD, robId = 0, phyRd = 10)

      // 验证入队成功
      dut.io.req.ready.expect(true.B)

      // 推进时钟
      dut.clock.step()

      // 验证指令已入队并可以发射
      dut.io.aluReq.valid.expect(true.B)
      dut.io.aluReq.bits.aluOp.expect(ALUOp.ADD)
      dut.io.aluReq.bits.meta.robId.expect(0.U)
      dut.io.aluReq.bits.meta.phyRd.expect(10.U)
      dut.io.prfRead.valid.expect(true.B)
      dut.io.prfRead.bits.raddr1.expect(1.U)
      dut.io.prfRead.bits.raddr2.expect(2.U)
    }
  }

  it should "正确处理满队列情况" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)
      dut.io.aluReq.ready.poke(false.B) // 模拟下游不 ready 导致队列满

      // 入队 8 条指令填满队列
      for (i <- 0 until 8) {
        setAluRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.aluReq.ready.poke(false.B)
        dut.io.req.ready.expect(true.B)
        dut.clock.step()
      }

      // 尝试入队第 9 条指令
      setAluRSRequest(dut, robId = 8, phyRd = 18)
      dut.io.req.ready.expect(false.B)
    }
  }

  it should "正确处理空队列情况" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 验证空队列时无法发射
      dut.io.aluReq.valid.expect(false.B)
      dut.io.prfRead.valid.expect(false.B)
    }
  }

  it should "通过 CDB 唤醒依赖指令" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队一条 src1 未就绪的指令
      setAluRSRequest(
        dut,
        aluOp = ALUOp.ADD,
        robId = 0,
        phyRd = 10,
        src1Tag = 5,
        src2Tag = 6,
        src1Ready = false,
        src2Ready = true
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 验证指令未就绪
      dut.io.aluReq.valid.expect(false.B)

      // 通过 CDB 唤醒 src1
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(5.U)
      dut.clock.step()

      // 验证指令已就绪
      dut.io.aluReq.valid.expect(true.B)
    }
  }

  it should "正确处理全局冲刷" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setAluRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.req.ready.expect(true.B)
        dut.clock.step()
      }

      // 触发全局冲刷
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证所有指令被清空
      dut.io.aluReq.valid.expect(false.B)
      dut.io.prfRead.valid.expect(false.B)

      // 取消冲刷
      dut.io.globalFlush.poke(false.B)
      dut.io.aluReq.valid.expect(false.B)
    }
  }

  it should "正确处理分支冲刷" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队 3 条指令，设置不同的 branchMask
      setAluRSRequest(dut, robId = 0, phyRd = 10, branchMask = 1)
      dut.io.aluReq.ready.poke(false.B)
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      setAluRSRequest(dut, robId = 1, phyRd = 11, branchMask = 2)
      dut.io.aluReq.ready.poke(false.B)
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      setAluRSRequest(dut, robId = 2, phyRd = 12, branchMask = 3)
      dut.io.aluReq.ready.poke(false.B)
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 触发分支冲刷，清除 branchMask = 1 的指令
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()

      // 验证第一条和第三条指令被清除
      // 第二条指令应该被发射
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(0.U)
      dut.io.aluReq.valid.expect(true.B)
      dut.io.aluReq.bits.meta.robId.expect(1.U)
    }
  }

  it should "正确处理 CDB 连续广播" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队 3 条指令，都依赖不同的源寄存器
      setAluRSRequest(
        dut,
        robId = 0,
        phyRd = 10,
        src1Tag = 5,
        src2Tag = 6,
        src1Ready = false,
        src2Ready = false
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      setAluRSRequest(
        dut,
        robId = 1,
        phyRd = 11,
        src1Tag = 7,
        src2Tag = 8,
        src1Ready = false,
        src2Ready = false
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      setAluRSRequest(
        dut,
        robId = 2,
        phyRd = 12,
        src1Tag = 9,
        src2Tag = 10,
        src1Ready = false,
        src2Ready = false
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 连续广播 CDB 唤醒所有指令
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(5.U)
      dut.clock.step()

      dut.io.cdb.bits.phyRd.poke(6.U)
      dut.clock.step()

      // 验证第一条指令已就绪
      dut.io.aluReq.valid.expect(true.B)
      dut.io.aluReq.bits.meta.robId.expect(0.U)
    }
  }

  it should "正确处理自依赖指令" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队一条自依赖指令（src1Tag == phyRd）
      setAluRSRequest(
        dut,
        aluOp = ALUOp.ADD,
        robId = 0,
        phyRd = 10,
        src1Tag = 10,
        src2Tag = 6,
        src1Ready = false,
        src2Ready = true
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 验证指令未就绪
      dut.io.aluReq.valid.expect(false.B)

      // 通过 CDB 唤醒
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(10.U)
      dut.clock.step()

      // 验证指令已就绪
      dut.io.aluReq.valid.expect(true.B)
    }
  }

  it should "正确处理非寄存器操作数" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队一条使用立即数的指令
      // 注意：AluRS 的 src1Sel 和 src2Sel 由 Dispatcher 设置
      // 这里我们测试基本的入队和发射功能
      setAluRSRequest(
        dut,
        aluOp = ALUOp.ADD,
        src1Tag = 5,
        src2Tag = 6,
        src1Ready = true,
        src2Ready = false,
        imm = 0x100
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 验证指令无法发射
      dut.io.aluReq.valid.expect(false.B)
      dut.io.aluReq.bits.data.imm.expect(0x0.U)
      dut.clock.step()

      setAluRSRequest(
        dut,
        aluOp = ALUOp.ADD,
        src1Tag = 5,
        src2Tag = 6,
        src1Ready = true,
        src2Ready = false,
        src1Sel = Src1Sel.REG,
        src2Sel = Src2Sel.IMM,
        imm = 0x100
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 验证指令可以发射
      dut.io.aluReq.valid.expect(true.B)
      dut.io.aluReq.bits.data.imm.expect(0x100.U)
    }
  }

  it should "在冲刷期间停止入队和发射" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队一条指令
      setAluRSRequest(dut, robId = 0, phyRd = 10)
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 触发全局冲刷
      dut.io.globalFlush.poke(true.B)

      // 尝试入队新指令
      setAluRSRequest(dut, robId = 1, phyRd = 11)
      dut.io.req.ready.expect(false.B)

      // 验证无法发射
      dut.io.aluReq.valid.expect(false.B)
    }
  }

  // ============================================================================
  // BruRS 测试用例
  // ============================================================================

  // 辅助函数：设置 BruRS 默认输入信号
  def setBruRSDefaults(dut: BruRS): Unit = {
    dut.io.globalFlush.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.branchOH.poke(0.U)
    dut.io.cdb.valid.poke(false.B)
    dut.io.prfRead.ready.poke(true.B)
    dut.io.bruReq.ready.poke(true.B)
  }

  // 辅助函数：设置 BruRS 入队请求
  def setBruRSRequest(
      dut: BruRS,
      bruOp: BRUOp.Type = BRUOp.BEQ,
      robId: Int = 0,
      phyRd: Int = 10,
      src1Sel: Src1Sel.Type = Src1Sel.REG,
      src2Sel: Src2Sel.Type = Src2Sel.REG,
      src1Tag: Int = 1,
      src2Tag: Int = 2,
      src1Ready: Boolean = true,
      src2Ready: Boolean = true,
      imm: Long = 0L,
      pc: Long = 0x80000000L,
      snapshotOH: Int = 0,
      branchMask: Int = 0,
      predictionTaken: Boolean = false,
      predictionTarget: Long = 0x80000004L
  ): Unit = {
    dut.io.enq.valid.poke(true.B)
    dut.io.enq.bits.bruOp.poke(bruOp)
    dut.io.enq.bits.robId.poke(robId.U)
    dut.io.enq.bits.phyRd.poke(phyRd.U)
    dut.io.enq.bits.data.src1Sel.poke(src1Sel)
    dut.io.enq.bits.data.src2Sel.poke(src2Sel)
    dut.io.enq.bits.data.src1Tag.poke(src1Tag.U)
    dut.io.enq.bits.data.src2Tag.poke(src2Tag.U)
    dut.io.enq.bits.data.src1Ready.poke(src1Ready.B)
    dut.io.enq.bits.data.src2Ready.poke(src2Ready.B)
    dut.io.enq.bits.data.imm.poke(imm.U)
    dut.io.enq.bits.data.pc.poke(pc.U)
    dut.io.enq.bits.snapshotOH.poke(snapshotOH.U)
    dut.io.enq.bits.branchMask.poke(branchMask.U)
    dut.io.enq.bits.prediction.taken.poke(predictionTaken.B)
    dut.io.enq.bits.prediction.targetPC.poke(predictionTarget.U)
    dut.io.enq.bits.exception.valid.poke(false.B)
  }

  "BruRS" should "成功入队一条指令" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)
      setBruRSRequest(
        dut,
        bruOp = BRUOp.BEQ,
        robId = 0,
        phyRd = 10,
        snapshotOH = 1
      )

      // 验证入队成功
      dut.io.enq.ready.expect(true.B)

      // 推进时钟
      dut.clock.step()

      // 验证指令已入队并可以发射
      dut.io.bruReq.valid.expect(true.B)
      dut.io.bruReq.bits.bruOp.expect(BRUOp.BEQ)
      dut.io.bruReq.bits.meta.robId.expect(0.U)
      dut.io.bruReq.bits.meta.phyRd.expect(10.U)
      dut.io.prfRead.valid.expect(true.B)
      dut.io.prfRead.bits.raddr1.expect(1.U)
      dut.io.prfRead.bits.raddr2.expect(2.U)
    }
  }

  it should "正确处理满队列情况" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队 4 条指令填满队列
      for (i <- 0 until 4) {
        setBruRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.bruReq.ready.poke(false.B)
        dut.io.enq.ready.expect(true.B)
        dut.clock.step()
      }

      // 尝试入队第 5 条指令
      setBruRSRequest(dut, robId = 4, phyRd = 14)
      dut.io.bruReq.ready.poke(false.B)
      dut.io.enq.ready.expect(false.B)
    }
  }

  it should "正确处理空队列情况" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 验证空队列时无法发射
      dut.io.bruReq.valid.expect(false.B)
      dut.io.prfRead.valid.expect(false.B)
    }
  }

  it should "通过 CDB 唤醒依赖指令" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队一条 src1 未就绪的指令
      setBruRSRequest(
        dut,
        bruOp = BRUOp.BEQ,
        robId = 0,
        phyRd = 10,
        src1Tag = 5,
        src2Tag = 6,
        src1Ready = false,
        src2Ready = true
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 验证指令未就绪
      dut.io.bruReq.valid.expect(false.B)

      // 通过 CDB 唤醒 src1
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(5.U)
      dut.clock.step()

      // 验证指令已就绪
      dut.io.bruReq.valid.expect(true.B)
    }
  }

  it should "正确处理全局冲刷" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setBruRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.enq.ready.expect(true.B)
        dut.clock.step()
      }

      // 触发全局冲刷
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证所有指令被清空
      dut.io.bruReq.valid.expect(false.B)
      dut.io.prfRead.valid.expect(false.B)

      // 取消冲刷
      dut.io.globalFlush.poke(false.B)
      dut.io.bruReq.valid.expect(false.B)
    }
  }

  it should "正确处理分支冲刷" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)
      dut.io.bruReq.ready.poke(false.B)

      // 入队 3 条指令，设置不同的 branchMask
      setBruRSRequest(dut, robId = 0, phyRd = 10, branchMask = 1)
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      setBruRSRequest(dut, robId = 1, phyRd = 11, branchMask = 2)
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      setBruRSRequest(dut, robId = 2, phyRd = 12, branchMask = 3)
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 触发分支冲刷，清除 branchMask = 1 的指令
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()

      // 验证第一条和第三条指令被清除
      // 第二条指令应该被发射
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(0.U)
      dut.io.bruReq.valid.expect(true.B)
      dut.io.bruReq.bits.meta.robId.expect(1.U)
    }
  }

  it should "正确处理 snapshotOH 字段" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队一条带有 snapshotOH 的指令
      setBruRSRequest(
        dut,
        bruOp = BRUOp.BEQ,
        robId = 0,
        phyRd = 10,
        snapshotOH = 1
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 验证指令可以发射
      dut.io.bruReq.valid.expect(true.B)
    }
  }

  it should "正确处理 prediction 字段" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队一条带有预测的指令
      setBruRSRequest(
        dut,
        bruOp = BRUOp.BEQ,
        robId = 0,
        phyRd = 10,
        predictionTaken = true,
        predictionTarget = 0x80000100L
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 验证预测信息被正确传递
      dut.io.bruReq.valid.expect(true.B)
      dut.io.bruReq.bits.prediction.taken.expect(true.B)
      dut.io.bruReq.bits.prediction.targetPC.expect(0x80000100L.U)
    }
  }

  it should "正确处理操作数选择逻辑" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队一条使用 PC 的指令
      setBruRSRequest(
        dut,
        bruOp = BRUOp.JAL,
        robId = 0,
        phyRd = 10,
        src1Sel = Src1Sel.PC,
        src2Sel = Src2Sel.IMM,
        src1Tag = 0,
        src2Tag = 0,
        src1Ready = false,
        src2Ready = false,
        imm = 0x100,
        pc = 0x80000000L
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 验证指令已就绪（src1 是 PC，不需要等待）
      dut.io.bruReq.valid.expect(true.B)
      dut.io.bruReq.bits.data.src1Sel.expect(Src1Sel.PC)
      dut.io.bruReq.bits.data.src2Sel.expect(Src2Sel.IMM)
      dut.io.bruReq.bits.data.imm.expect(0x100.U)
      dut.io.bruReq.bits.data.pc.expect(0x80000000L.U)
    }
  }

  it should "正确处理 CDB 连续广播" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队 3 条指令，都依赖不同的源寄存器
      setBruRSRequest(
        dut,
        robId = 0,
        phyRd = 10,
        src1Tag = 5,
        src2Tag = 6,
        src1Ready = false,
        src2Ready = false
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      setBruRSRequest(
        dut,
        robId = 1,
        phyRd = 11,
        src1Tag = 7,
        src2Tag = 8,
        src1Ready = false,
        src2Ready = false
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      setBruRSRequest(
        dut,
        robId = 2,
        phyRd = 12,
        src1Tag = 9,
        src2Tag = 10,
        src1Ready = false,
        src2Ready = false
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 连续广播 CDB 唤醒所有指令
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(5.U)
      dut.clock.step()

      dut.io.cdb.bits.phyRd.poke(6.U)
      dut.clock.step()

      // 验证第一条指令已就绪
      dut.io.bruReq.valid.expect(true.B)
      dut.io.bruReq.bits.meta.robId.expect(0.U)
    }
  }

  it should "正确处理自依赖指令" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队一条自依赖指令（src1Tag == phyRd）
      setBruRSRequest(
        dut,
        bruOp = BRUOp.BEQ,
        robId = 0,
        phyRd = 10,
        src1Tag = 10,
        src2Tag = 6,
        src1Ready = false,
        src2Ready = true
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 验证指令未就绪
      dut.io.bruReq.valid.expect(false.B)

      // 通过 CDB 唤醒
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(10.U)
      dut.clock.step()

      // 验证指令已就绪
      dut.io.bruReq.valid.expect(true.B)
    }
  }

  it should "在冲刷期间停止入队和发射" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队一条指令
      setBruRSRequest(dut, robId = 0, phyRd = 10)
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 触发全局冲刷
      dut.io.globalFlush.poke(true.B)

      // 尝试入队新指令
      setBruRSRequest(dut, robId = 1, phyRd = 11)
      dut.io.enq.ready.expect(false.B)

      // 验证无法发射
      dut.io.bruReq.valid.expect(false.B)
    }
  }

  // ============================================================================
  // 综合测试用例
  // ============================================================================

  "综合测试" should "同时处理 globalFlush 和 branchFlush" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setAluRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.req.ready.expect(true.B)
        dut.clock.step()
      }

      // 同时触发全局冲刷和分支冲刷
      dut.io.globalFlush.poke(true.B)
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()

      // 验证所有指令被清空
      dut.io.aluReq.valid.expect(false.B)
      dut.io.prfRead.valid.expect(false.B)
    }
  }

  it should "正确处理异常指令" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队一条带异常的指令
      setAluRSRequest(dut, robId = 0, phyRd = 10)
      dut.io.req.bits.exception.valid.poke(true.B)
      dut.io.req.bits.exception.cause.poke(
        ExceptionCause.ILLEGAL_INSTRUCTION
      )
      dut.io.req.bits.exception.tval.poke(0x80000000L.U)
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 验证指令可以发射
      dut.io.aluReq.valid.expect(true.B)
      dut.io.aluReq.bits.meta.exception.valid.expect(true.B)
      dut.io.aluReq.bits.meta.exception.cause.expect(
        ExceptionCause.ILLEGAL_INSTRUCTION
      )
    }
  }

  it should "正确处理边界条件组合" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队 7 条指令（接近满）
      for (i <- 0 until 7) {
        setAluRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.req.ready.expect(true.B)
        dut.clock.step()
      }

      // 发射一些指令
      for (_ <- 0 until 3) {
        dut.io.aluReq.valid.expect(true.B)
        dut.clock.step()
      }

      // 再入队一些指令
      for (i <- 7 until 8) {
        setAluRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.req.ready.expect(true.B)
        dut.clock.step()
      }

      // 验证队列状态
      dut.io.aluReq.valid.expect(true.B)
    }
  }

  it should "正确处理快速连续的入队和发射" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 快速连续入队和发射
      for (i <- 0 until 5) {
        setAluRSRequest(dut, robId = i, phyRd = 10 + i)
        dut.io.req.ready.expect(true.B)
        dut.clock.step()

        dut.io.aluReq.valid.expect(true.B)
        dut.clock.step()
      }
    }
  }

  it should "正确处理 CDB 唤醒后立即发射" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 入队一条未就绪的指令
      setAluRSRequest(
        dut,
        robId = 0,
        phyRd = 10,
        src1Tag = 5,
        src2Tag = 6,
        src1Ready = false,
        src2Ready = true
      )
      dut.io.req.ready.expect(true.B)
      dut.clock.step()

      // 验证指令未就绪
      dut.io.aluReq.valid.expect(false.B)
      dut.clock.step()

      // 通过 CDB 唤醒
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(5.U)
      dut.clock.step()

      // 验证指令已就绪并立即发射
      dut.io.aluReq.valid.expect(true.B)
    }
  }

  it should "正确处理 BruRS 的分支预测信息" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 入队一条带有分支预测的指令
      setBruRSRequest(
        dut,
        bruOp = BRUOp.BEQ,
        robId = 0,
        phyRd = 10,
        predictionTaken = true,
        predictionTarget = 0x80000100L,
        snapshotOH = 1
      )
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()

      // 验证预测信息被正确传递
      dut.io.bruReq.valid.expect(true.B)
      dut.io.bruReq.bits.prediction.taken.expect(true.B)
      dut.io.bruReq.bits.prediction.targetPC.expect(0x80000100L.U)
    }
  }

  it should "正确处理 AluRS 的不同操作类型" in {
    test(new AluRS) { dut =>
      setAluRSDefaults(dut)

      // 测试不同的 ALU 操作
      val aluOps = Seq(
        ALUOp.ADD,
        ALUOp.SUB,
        ALUOp.AND,
        ALUOp.OR,
        ALUOp.XOR,
        ALUOp.SLL,
        ALUOp.SRL,
        ALUOp.SRA,
        ALUOp.SLT,
        ALUOp.SLTU
      )

      for ((op, i) <- aluOps.zipWithIndex) {
        setAluRSRequest(dut, aluOp = op, robId = i, phyRd = 10 + i)
        dut.io.req.ready.expect(true.B)
        dut.clock.step()

        dut.io.aluReq.valid.expect(true.B)
        dut.io.aluReq.bits.aluOp.expect(op)
        dut.clock.step()
      }
    }
  }

  it should "正确处理 BruRS 的不同分支类型" in {
    test(new BruRS) { dut =>
      setBruRSDefaults(dut)

      // 测试不同的 BRU 操作
      val bruOps = Seq(
        BRUOp.BEQ,
        BRUOp.BNE,
        BRUOp.BLT,
        BRUOp.BGE,
        BRUOp.BLTU,
        BRUOp.BGEU,
        BRUOp.JAL,
        BRUOp.JALR
      )

      for ((op, i) <- bruOps.zipWithIndex) {
        setBruRSRequest(dut, bruOp = op, robId = i, phyRd = 10 + i)
        dut.io.enq.ready.expect(true.B)
        dut.clock.step()

        dut.io.bruReq.valid.expect(true.B)
        dut.io.bruReq.bits.bruOp.expect(op)
        dut.clock.step()
      }
    }
  }
}
