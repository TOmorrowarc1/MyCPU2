package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BRUTest extends AnyFlatSpec with ChiselScalatestTester {

  // 辅助函数：设置默认输入信号
  def setDefaultInputs(dut: BRU): Unit = {
    dut.io.globalFlush.poke(false.B)
    dut.io.out.ready.poke(true.B)
  }

  // 辅助函数：设置 BRU 请求包
  def setBruPacket(
      dut: BRU,
      bruOp: BRUOp.Type,
      robId: Int = 0,
      phyRd: Int = 0,
      snapshotOH: Int = 0,
      predictionTaken: Boolean = false,
      predictionTargetPC: Long = 0,
      src1Sel: Src1Sel.Type = Src1Sel.REG,
      src2Sel: Src2Sel.Type = Src2Sel.REG,
      imm: Long = 0,
      pc: Long = 0,
      rdata1: Long = 0,
      rdata2: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.bruReq.bruOp.poke(bruOp)
    dut.io.in.bits.bruReq.snapshotOH.poke(snapshotOH.U)
    dut.io.in.bits.bruReq.prediction.taken.poke(predictionTaken.B)
    dut.io.in.bits.bruReq.prediction.targetPC
      .poke((predictionTargetPC & mask).U)
    dut.io.in.bits.bruReq.meta.robId.poke(robId.U)
    dut.io.in.bits.bruReq.meta.phyRd.poke(phyRd.U)
    dut.io.in.bits.bruReq.data.src1Sel.poke(src1Sel)
    dut.io.in.bits.bruReq.data.src2Sel.poke(src2Sel)
    dut.io.in.bits.bruReq.data.imm.poke((imm & mask).U)
    dut.io.in.bits.bruReq.data.pc.poke((pc & mask).U)
    dut.io.in.bits.prfData.rdata1.poke((rdata1 & mask).U)
    dut.io.in.bits.prfData.rdata2.poke((rdata2 & mask).U)
  }

  // 辅助函数：执行单条分支指令并验证结果
  def executeAndVerifyBranch(
      dut: BRU,
      bruOp: BRUOp.Type,
      src1Sel: Src1Sel.Type = Src1Sel.REG,
      src2Sel: Src2Sel.Type = Src2Sel.REG,
      imm: Long = 0,
      pc: Long = 0,
      rdata1: Long = 0,
      rdata2: Long = 0,
      expectedReturnAddr: Long = 0,
      robId: Int = 0,
      phyRd: Int = 0,
      snapshotOH: Int = 0,
      predictionTaken: Boolean = false,
      predictionTargetPC: Long = 0,
      expectedBranchFlush: Boolean = false,
      expectedBranchPC: Long = 0
  ): Unit = {
    setDefaultInputs(dut)
    setBruPacket(
      dut,
      bruOp,
      robId,
      phyRd,
      snapshotOH,
      predictionTaken,
      predictionTargetPC,
      src1Sel,
      src2Sel,
      imm,
      pc,
      rdata1,
      rdata2
    )

    // 第一个周期：发送请求
    dut.io.in.ready.expect(true.B)
    dut.io.out.valid.expect(false.B)
    dut.clock.step()

    // 第二个周期：结果输出
    dut.io.in.valid.poke(false.B)
    dut.io.in.ready.expect((!expectedBranchFlush).B)
    dut.io.out.valid.expect((!expectedBranchFlush).B)
    dut.io.out.bits.robId.expect(robId.U)
    dut.io.out.bits.phyRd.expect(phyRd.U)
    dut.io.out.bits.data.expect((expectedReturnAddr & 0xffffffffL).U)
    dut.io.out.bits.hasSideEffect.expect(0.U)
    dut.io.out.bits.exception.valid.expect(false.B)

    // 验证分支决议信号
    dut.io.branchFlush.expect(expectedBranchFlush.B)
    dut.io.branchOH.expect(snapshotOH.U)
    if (expectedBranchFlush) {
      dut.io.branchPC.expect((expectedBranchPC & 0xffffffffL).U)
    } else {
      dut.io.branchPC.expect(0.U)
    }
    dut.clock.step()

    dut.io.in.ready.expect(true.B)
    dut.io.out.valid.expect(expectedBranchFlush.B)
    dut.io.branchFlush.expect(false.B)
    dut.io.branchOH.expect(0.U)
    dut.io.branchPC.expect(0.U)
  }

  // ============================================================================
  // 1. 分支操作正确性测试
  // ============================================================================

  "BRU" should "正确执行 BEQ 操作（相等则分支）" in {
    test(new BRU) { dut =>
      // 相等，预测正确（taken=true, targetPC正确）
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        rdata1 = 100,
        rdata2 = 100,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BEQ 操作（不相等）" in {
    test(new BRU) { dut =>
      // 不相等，预测正确（taken=false）
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        rdata1 = 100,
        rdata2 = 200,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = false,
        predictionTargetPC = 0x80000004L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BNE 操作（不相等则分支）" in {
    test(new BRU) { dut =>
      // 不相等，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.BNE,
        rdata1 = 100,
        rdata2 = 200,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BNE 操作（相等）" in {
    test(new BRU) { dut =>
      // 相等，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.BNE,
        rdata1 = 100,
        rdata2 = 100,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = false,
        predictionTargetPC = 0x80000004L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BLT 操作（有符号小于）" in {
    test(new BRU) { dut =>
      // 100 < 200，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.BLT,
        rdata1 = 100,
        rdata2 = 200,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BLT 操作（负数比较）" in {
    test(new BRU) { dut =>
      // -100 < -50，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.BLT,
        rdata1 = 0xffffff9cL, // -100
        rdata2 = 0xffffffceL, // -50
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BGE 操作（有符号大于等于）" in {
    test(new BRU) { dut =>
      // 200 >= 100，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.BGE,
        rdata1 = 200,
        rdata2 = 100,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BLTU 操作（无符号小于）" in {
    test(new BRU) { dut =>
      // 100 < 200，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.BLTU,
        rdata1 = 100,
        rdata2 = 200,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BLTU 操作（无符号大数比较）" in {
    test(new BRU) { dut =>
      // 0x80000000 > 0x7fffffff（无符号），预测正确（不跳转）
      executeAndVerifyBranch(
        dut,
        BRUOp.BLTU,
        rdata1 = 0x80000000L,
        rdata2 = 0x7fffffffL,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = false,
        predictionTargetPC = 0x80000004L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 BGEU 操作（无符号大于等于）" in {
    test(new BRU) { dut =>
      // 0x80000000 >= 0x7fffffff（无符号），预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.BGEU,
        rdata1 = 0x80000000L,
        rdata2 = 0x7fffffffL,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 JAL 操作（跳转并链接）" in {
    test(new BRU) { dut =>
      // JAL 总是跳转，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.JAL,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 JALR 操作（跳转并链接寄存器）" in {
    test(new BRU) { dut =>
      // JALR 总是跳转，目标 = (src1 + imm) & ~1
      val target = (0x80001000L + 0x100L) & 0xfffffffeL
      executeAndVerifyBranch(
        dut,
        BRUOp.JALR,
        src1Sel = Src1Sel.REG,
        rdata1 = 0x80001000L,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = target,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 JALR 操作（最低位清除逻辑）" in {
    test(new BRU) { dut =>
      // 测试最低位清除：奇数地址应被清除为偶数
      val target = (0x80001001L + 0x100L) & 0xfffffffeL
      executeAndVerifyBranch(
        dut,
        BRUOp.JALR,
        src1Sel = Src1Sel.REG,
        rdata1 = 0x80001001L,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = target,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确执行 NOP 操作" in {
    test(new BRU) { dut =>
      // NOP 不跳转，预测正确
      executeAndVerifyBranch(
        dut,
        BRUOp.NOP,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = false,
        predictionTargetPC = 0x80000004L,
        expectedBranchFlush = false
      )
    }
  }

  // ============================================================================
  // 2. 预测错误测试
  // ============================================================================

  it should "正确处理 BEQ 预测错误（预测不跳转但实际跳转）" in {
    test(new BRU) { dut =>
      // 相等，预测不跳转但实际应该跳转
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        rdata1 = 100,
        rdata2 = 100,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        snapshotOH = 1,
        predictionTaken = false, // 预测不跳转
        predictionTargetPC = 0x80000004L, // 预测的目标地址（PC+4）
        expectedBranchFlush = true, // 实际跳转，需要冲刷
        expectedBranchPC = 0x80000100L // 正确目标
      )
    }
  }

  it should "正确处理 BEQ 预测错误（预测跳转但实际不跳转）" in {
    test(new BRU) { dut =>
      // 不相等，预测跳转但实际不跳转
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        rdata1 = 100,
        rdata2 = 200,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        snapshotOH = 2,
        predictionTaken = true, // 预测跳转
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = true, // 实际不跳转，需要冲刷
        expectedBranchPC = 0x80000004L // 正确目标（PC+4）
      )
    }
  }

  it should "正确处理 BLT 预测错误（预测方向错误）" in {
    test(new BRU) { dut =>
      // 100 < 200，应该跳转，但预测不跳转
      executeAndVerifyBranch(
        dut,
        BRUOp.BLT,
        rdata1 = 100,
        rdata2 = 200,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        snapshotOH = 3,
        predictionTaken = false,
        predictionTargetPC = 0x80000004L, // 预测的目标地址（PC+4）
        expectedBranchFlush = true,
        expectedBranchPC = 0x80000100L
      )
    }
  }

  it should "正确处理 BLTU 预测错误（预测方向错误）" in {
    test(new BRU) { dut =>
      // 100 < 200（无符号），应该跳转，但预测不跳转
      executeAndVerifyBranch(
        dut,
        BRUOp.BLTU,
        rdata1 = 100,
        rdata2 = 200,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        snapshotOH = 1,
        predictionTaken = false,
        predictionTargetPC = 0x80000004L, // 预测的目标地址（PC+4）
        expectedBranchFlush = true,
        expectedBranchPC = 0x80000100L
      )
    }
  }

  it should "正确处理 JAL 预测错误（目标地址错误）" in {
    test(new BRU) { dut =>
      // JAL 总是跳转，但预测目标错误
      executeAndVerifyBranch(
        dut,
        BRUOp.JAL,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        snapshotOH = 2,
        predictionTaken = true,
        predictionTargetPC = 0x80000200L, // 错误目标
        expectedBranchFlush = true,
        expectedBranchPC = 0x80000100L // 正确目标
      )
    }
  }

  it should "正确处理 JALR 预测错误（目标地址错误）" in {
    test(new BRU) { dut =>
      // JALR 总是跳转，但预测目标错误
      val correctTarget = (0x80001000L + 0x100L) & 0xfffffffeL
      executeAndVerifyBranch(
        dut,
        BRUOp.JALR,
        src1Sel = Src1Sel.REG,
        rdata1 = 0x80001000L,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        snapshotOH = 3,
        predictionTaken = true,
        predictionTargetPC = 0x80000200L, // 错误目标
        expectedBranchFlush = true,
        expectedBranchPC = correctTarget // 正确目标
      )
    }
  }

  // ============================================================================
  // 3. 边界情况测试
  // ============================================================================

  it should "正确处理 BEQ 边界情况（0x7FFFFFFF + 1 溢出）" in {
    test(new BRU) { dut =>
      // 测试 PC + imm 溢出
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        rdata1 = 100,
        rdata2 = 100,
        imm = 0x80000000L,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x0L, // 溢出回绕
        expectedBranchFlush = false
      )
    }
  }

  it should "正确处理 BLT 边界情况（最大负数比较）" in {
    test(new BRU) { dut =>
      // 0x80000000 (-2147483648) < 0
      executeAndVerifyBranch(
        dut,
        BRUOp.BLT,
        rdata1 = 0x80000000L,
        rdata2 = 0,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L, // pc + imm = 0x80000000 + 0x100
        expectedBranchFlush = false
      )
    }
  }

  it should "正确处理 BLTU 边界情况（无符号最大值比较）" in {
    test(new BRU) { dut =>
      // 0xffffffff < 0（无符号，0xffffffff 是最大值）
      executeAndVerifyBranch(
        dut,
        BRUOp.BLTU,
        rdata1 = 0xffffffffL,
        rdata2 = 0,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = false, // 0xffffffff > 0（无符号）
        predictionTargetPC = 0x80000004L, // 预测不跳转，PC+4
        expectedBranchFlush = false
      )
    }
  }

  it should "正确处理 JALR 边界情况（地址计算溢出）" in {
    test(new BRU) { dut =>
      // 0xffffffff + 1 = 0（溢出）
      val target = (0xffffffffL + 1L) & 0xfffffffeL
      executeAndVerifyBranch(
        dut,
        BRUOp.JALR,
        src1Sel = Src1Sel.REG,
        rdata1 = 0xffffffffL,
        imm = 1,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = target,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确处理 JALR 最低位清除（奇数地址）" in {
    test(new BRU) { dut =>
      // 测试各种奇数地址
      val testCases = Seq(
        (0x80000001L, 0L, 0x80000000L),
        (0x80001003L, 0L, 0x80001002L),
        (0x80001005L, 0L, 0x80001004L),
        (0x80001001L, 0x100L, 0x80001100L)
      )

      for ((rdata1, imm, expectedTarget) <- testCases) {
        val target = (rdata1 + imm) & 0xfffffffeL
        executeAndVerifyBranch(
          dut,
          BRUOp.JALR,
          src1Sel = Src1Sel.REG,
          rdata1 = rdata1,
          imm = imm,
          pc = 0x80000000L,
          expectedReturnAddr = 0x80000004L,
          predictionTaken = true,
          predictionTargetPC = target,
          expectedBranchFlush = false
        )
      }
    }
  }

  // ============================================================================
  // 4. 操作数选择测试
  // ============================================================================

  it should "正确选择 Src1Sel.REG（使用 rdata1）" in {
    test(new BRU) { dut =>
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        src1Sel = Src1Sel.REG,
        rdata1 = 100,
        rdata2 = 100,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确选择 Src1Sel.PC（使用 pc）" in {
    test(new BRU) { dut =>
      // 使用 PC 作为第一个操作数
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        src1Sel = Src1Sel.PC,
        pc = 0x80000000L,
        rdata2 = 0x80000000L,
        imm = 0x100,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确选择 Src1Sel.ZERO（使用 0）" in {
    test(new BRU) { dut =>
      // 使用 0 作为第一个操作数
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        src1Sel = Src1Sel.ZERO,
        rdata1 = 100, // 应该被忽略
        rdata2 = 0,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确选择 Src2Sel.REG（使用 rdata2）" in {
    test(new BRU) { dut =>
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        src2Sel = Src2Sel.REG,
        rdata1 = 100,
        rdata2 = 100,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  it should "正确选择 Src2Sel.IMM（使用 imm）" in {
    test(new BRU) { dut =>
      // 使用立即数作为第二个操作数
      // BEQ: rdata1=100, src2=imm=100 → 100==100 → 应该跳转
      // 实际目标 = pc + imm = 0x80000000 + 100 = 0x80000064
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        src2Sel = Src2Sel.IMM,
        rdata1 = 100,
        imm = 100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000064L, // PC + imm = 0x80000000 + 100
        expectedBranchFlush = false
      )
    }
  }

  it should "正确选择 Src2Sel.FOUR（使用 4）" in {
    test(new BRU) { dut =>
      // 使用 4 作为第二个操作数
      executeAndVerifyBranch(
        dut,
        BRUOp.BEQ,
        src2Sel = Src2Sel.FOUR,
        rdata1 = 4,
        imm = 0x100,
        pc = 0x80000000L,
        expectedReturnAddr = 0x80000004L,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        expectedBranchFlush = false
      )
    }
  }

  // ============================================================================
  // 5. 流水线控制测试
  // ============================================================================

  it should "正确处理 valid 和 ready 握手" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      // 初始状态：BRU 空闲，ready 为 true
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)

      // 发送请求
      setBruPacket(
        dut,
        BRUOp.BEQ,
        rdata1 = 100,
        rdata2 = 100,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L,
        pc = 0x80000000L,
        imm = 0x100
      )
      dut.clock.step()

      // BRU 忙碌，ready 为 false
      dut.io.out.ready.poke(false.B)
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()

      // BRU 再次空闲
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()

      // BRU 再次空闲
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理 globalFlush 信号" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      // 发送请求
      setBruPacket(
        dut,
        BRUOp.BEQ,
        robId = 0,
        phyRd = 0,
        snapshotOH = 1,
        rdata1 = 100,
        rdata2 = 100,
        pc = 0x80000000L,
        imm = 0x100,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L
      )
      dut.io.out.ready.poke(false.B)
      dut.clock.step()

      // BRU 忙碌
      dut.io.in.valid.poke(false.B)
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)

      // 触发 globalFlush
      dut.io.globalFlush.poke(true.B)
      // Flush 时：ready = false, valid = false, branchFlush = false, branchOH = 0, branchPC = 0
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(false.B)
      dut.io.branchFlush.expect(false.B)
      dut.io.branchOH.expect(0.U)
      dut.io.branchPC.expect(0.U)
      dut.clock.step()

      // 取消 flush
      dut.io.globalFlush.poke(false.B)
      // BRU 应该可以接收新指令
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理 globalFlush 信号（在空闲时）" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      // BRU 空闲时触发 flush
      dut.io.globalFlush.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()

      // 取消 flush
      dut.io.globalFlush.poke(false.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "正确处理输出 ready 为 false 的情况" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      // 发送请求
      setBruPacket(
        dut,
        BRUOp.BEQ,
        robId = 0,
        phyRd = 0,
        snapshotOH = 1,
        rdata1 = 100,
        rdata2 = 100,
        pc = 0x80000000L,
        imm = 0x100,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L
      )
      dut.io.out.ready.poke(false.B)
      dut.clock.step()

      // BRU 忙碌，输出有效，但不会 fire
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()

      // 设置输出 ready 为 true
      dut.io.out.ready.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()

      // BRU 空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理输入 valid 为 false 的情况" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      // 输入 valid 为 false
      dut.io.in.valid.poke(false.B)
      dut.clock.step()

      // BRU 空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)

      // 多个周期保持空闲
      dut.clock.step(5)

      // BRU 仍然空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  // ============================================================================
  // 6. 综合测试
  // ============================================================================

  it should "正确执行分支指令序列" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      // 指令1: BEQ x1, x2, offset
      setBruPacket(
        dut,
        BRUOp.BEQ,
        robId = 0,
        phyRd = 1,
        snapshotOH = 1,
        rdata1 = 100,
        rdata2 = 100,
        pc = 0x80000000L,
        imm = 0x100,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L
      )
      dut.clock.step()

      // 指令2: BNE x3, x4, offset
      setBruPacket(
        dut,
        BRUOp.BNE,
        robId = 1,
        phyRd = 4,
        snapshotOH = 2,
        rdata1 = 100,
        rdata2 = 200,
        pc = 0x80000104L,
        imm = 0xfc,
        predictionTaken = true,
        predictionTargetPC = 0x80000200L
      )
      // 验证指令1结果
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.robId.expect(0.U)
      dut.io.out.bits.phyRd.expect(1.U)
      dut.io.out.bits.data.expect(0x80000004L.U)
      dut.io.branchFlush.expect(false.B)
      dut.io.branchOH.expect(1.U)
      dut.clock.step()

      // 验证指令2结果
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.robId.expect(1.U)
      dut.io.out.bits.phyRd.expect(4.U)
      dut.io.branchFlush.expect(false.B)
      dut.io.branchOH.expect(2.U)
      dut.clock.step()

      // BRU 空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理所有 BRU 操作" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      val testCases = Seq(
        (BRUOp.BEQ, 100L, 100L, 0x100L, true, 0x80000100L),
        (BRUOp.BNE, 100L, 200L, 0x100L, true, 0x80000100L),
        (BRUOp.BLT, 100L, 200L, 0x100L, true, 0x80000100L),
        (BRUOp.BGE, 200L, 100L, 0x100L, true, 0x80000100L),
        (BRUOp.BLTU, 100L, 200L, 0x100L, true, 0x80000100L),
        (BRUOp.BGEU, 200L, 100L, 0x100L, true, 0x80000100L),
        (BRUOp.JAL, 0L, 0L, 0x100L, true, 0x80000100L),
        (
          BRUOp.JALR,
          0x80001000L,
          0L,
          0x100L,
          true,
          (0x80001000L + 0x100L) & 0xfffffffeL
        ),
      )

      for (((op, r1, r2, imm, taken, target), i) <- testCases.zipWithIndex) {
        val pc = 0x80000000L + i * 0x10L
        val expectedReturnAddr = pc + 4L
        val expectedTarget = if (op == BRUOp.JALR) target else pc + imm

        setBruPacket(
          dut,
          op,
          robId = i,
          phyRd = i,
          snapshotOH = i % 4,
          rdata1 = r1,
          rdata2 = r2,
          imm = imm,
          pc = pc,
          predictionTaken = taken,
          predictionTargetPC = expectedTarget
        )
        dut.clock.step()

        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.robId.expect(i.U)
        dut.io.out.bits.phyRd.expect(i.U)
        dut.io.out.bits.data.expect((expectedReturnAddr & 0xffffffffL).U)
        dut.io.branchFlush.expect(false.B)
        dut.io.branchOH.expect((i % 4).U)
        dut.clock.step()
      }
    }
  }

  it should "正确处理混合预测正确和错误的指令序列" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      // 指令1: BEQ（预测正确）
      setBruPacket(
        dut,
        BRUOp.BEQ,
        robId = 0,
        phyRd = 1,
        snapshotOH = 1,
        rdata1 = 100,
        rdata2 = 100,
        pc = 0x80000000L,
        imm = 0x100,
        predictionTaken = true,
        predictionTargetPC = 0x80000100L
      )
      dut.clock.step()

      // 验证指令1结果（预测正确，不冲刷）
      dut.io.out.valid.expect(true.B)
      dut.io.branchFlush.expect(false.B)
      dut.io.branchOH.expect(1.U)
      dut.clock.step()

      // 指令2: BNE（预测错误）
      setBruPacket(
        dut,
        BRUOp.BNE,
        robId = 1,
        phyRd = 4,
        snapshotOH = 2,
        rdata1 = 100,
        rdata2 = 100,
        pc = 0x80000010L,
        predictionTaken = true, // 预测跳转
        predictionTargetPC = 0x80000200L
      )
      dut.clock.step()

      // 验证指令2结果（预测错误，需要冲刷）
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.io.branchFlush.expect(true.B)
      dut.io.branchOH.expect(2.U)
      dut.io.branchPC.expect(0x80000014L.U) // PC+4
      dut.clock.step()

      // 指令3: JAL（预测正确）
      setBruPacket(
        dut,
        BRUOp.JAL,
        robId = 2,
        phyRd = 5,
        snapshotOH = 3,
        pc = 0x80000010L,
        imm = 0x100,
        predictionTaken = true,
        predictionTargetPC = 0x80000110L
      )
      dut.clock.step()

      // 验证指令3结果（预测正确，不冲刷）
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.branchFlush.expect(false.B)
      dut.io.branchOH.expect(3.U)
      dut.clock.step()
    }
  }

  it should "正确验证返回地址计算（PC + 4）" in {
    test(new BRU) { dut =>
      setDefaultInputs(dut)

      val testCases = Seq(
        (0x0L, 0x4L),
        (0x80000000L, 0x80000004L),
        (0xfffffffcL, 0x0L),
        (0x12345678L, 0x1234567cL)
      )

      for ((pc, expectedReturnAddr) <- testCases) {
        executeAndVerifyBranch(
          dut,
          BRUOp.JAL,
          pc = pc,
          imm = 0x100,
          expectedReturnAddr = expectedReturnAddr,
          predictionTaken = true,
          predictionTargetPC = pc + 0x100L,
          expectedBranchFlush = false
        )
      }
    }
  }
}
