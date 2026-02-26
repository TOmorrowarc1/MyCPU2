package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ROBTest extends AnyFlatSpec with ChiselScalatestTester {

  // ============================================================================
  // 辅助函数
  // ============================================================================

  // 设置默认输入信号
  def setDefaultInputs(dut: ROB): Unit = {
    dut.io.controlInit.valid.poke(false.B)
    dut.io.dataInit.valid.poke(false.B)
    dut.io.cdb.valid.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.branchOH.poke(0.U)
    dut.io.branchRobId.poke(0.U)
    dut.io.redirectPC.poke(0.U)
    dut.io.globalFlush.poke(false.B)
    dut.io.commitRAT.ready.poke(true.B)
  }

  // 设置指令入队请求（Decoder 侧）
  def setControlInit(
      dut: ROB,
      pc: Long = 0x80000000L,
      predictionTaken: Boolean = false,
      predictionTarget: Long = 0x80000004L,
      exceptionValid: Boolean = false,
      exceptionCause: Int = 0,
      exceptionTval: Long = 0,
      specialInstr: SpecialInstr.Type = SpecialInstr.NONE
  ): Unit = {
    dut.io.controlInit.valid.poke(true.B)
    dut.io.controlInit.bits.pc.poke(pc.U)
    dut.io.controlInit.bits.prediction.taken.poke(predictionTaken.B)
    dut.io.controlInit.bits.prediction.targetPC.poke(predictionTarget.U)
    dut.io.controlInit.bits.exception.valid.poke(exceptionValid.B)
    dut.io.controlInit.bits.exception.cause.poke(exceptionCause.U)
    dut.io.controlInit.bits.exception.tval.poke(exceptionTval.U)
    dut.io.controlInit.bits.specialInstr.poke(specialInstr)
  }

  // 设置指令入队请求（RAT 侧）
  def setDataInit(
      dut: ROB,
      archRd: Int = 1,
      phyRd: Int = 32,
      phyOld: Int = 1,
      branchMask: Int = 0
  ): Unit = {
    dut.io.dataInit.valid.poke(true.B)
    dut.io.dataInit.bits.archRd.poke(archRd.U)
    dut.io.dataInit.bits.phyRd.poke(phyRd.U)
    dut.io.dataInit.bits.phyOld.poke(phyOld.U)
    dut.io.dataInit.bits.branchMask.poke(branchMask.U)
  }

  // 设置 CDB 消息（指令完成）
  def setCDB(
      dut: ROB,
      robId: Int,
      phyRd: Int = 0,
      data: Long = 0,
      hasSideEffect: Boolean = false,
      exceptionValid: Boolean = false,
      exceptionCause: Int = 0,
      exceptionTval: Long = 0
  ): Unit = {
    dut.io.cdb.valid.poke(true.B)
    dut.io.cdb.bits.robId.poke(robId.U)
    dut.io.cdb.bits.phyRd.poke(phyRd.U)
    dut.io.cdb.bits.data.poke(data.U)
    dut.io.cdb.bits.hasSideEffect.poke(hasSideEffect.B)
    dut.io.cdb.bits.exception.valid.poke(exceptionValid.B)
    dut.io.cdb.bits.exception.cause.poke(exceptionCause.U)
    dut.io.cdb.bits.exception.tval.poke(exceptionTval.U)
  }

  // ============================================================================
  // 1. 初始化和基本功能测试
  // ============================================================================

  "ROB" should "正确初始化状态" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 检查初始状态
      dut.io.csrPending.expect(false.B)
      dut.io.iEpoch.expect(0.U)
      dut.io.dEpoch.expect(0.U)
      dut.io.freeRobID.expect(
        0.U
      ) // 初始时不能入队（因为 canEnqueue = !full && !needFlush，但此时 full 为 false）

      // 验证 ready 信号（初始应该为 true，因为 ROB 未满）
      dut.io.controlInit.ready.expect(true.B)
      dut.io.dataInit.ready.expect(true.B)
      dut.io.cdb.ready.expect(true.B)
    }
  }

  it should "正确处理普通指令入队" in {
    test(new ROB) { dut =>

      // ADD x1, x2, x3 (PC = 0x80000000)
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)

      // 验证握手成功
      dut.io.controlInit.ready.expect(true.B)
      dut.io.dataInit.ready.expect(true.B)
      dut.io.freeRobID.expect(0.U) // 分配 robId = 0

      dut.clock.step()

      // 验证第一条指令已入队
      // 尝试入队第二条指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000004L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 2, phyRd = 33, phyOld = 2, branchMask = 0)
      dut.io.freeRobID.expect(1.U) // 分配 robId = 1
    }
  }

  it should "正确处理指令完成" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 指令完成（robId = 0）
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // 验证指令已完成（通过观察 commitRAT 输出来判断）
      // 在下一个周期，队头指令应该可以提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.commitRAT.bits.archRd.expect(1.U)
      dut.io.commitRAT.bits.phyRd.expect(32.U)
      dut.io.commitRAT.bits.preRd.expect(1.U)
    }
  }

  it should "正确处理指令提交" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条指令
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // 指令提交
      setDefaultInputs(dut)
      dut.clock.step()

      // 验证提交后的状态
      // 队头应该已经前进，可以入队新指令
      setControlInit(dut, pc = 0x80000004L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 2, phyRd = 33, phyOld = 2, branchMask = 0)
      dut.io.freeRobID.expect(1.U) // 分配 robId = 1
    }
  }

  it should "正确处理 x0 寄存器指令" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // ADD x0, x1, x2 (x0 作为目标寄存器)
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // 验证提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.commitRAT.bits.archRd.expect(0.U)
      dut.io.commitRAT.bits.phyRd.expect(0.U)
      dut.io.commitRAT.bits.preRd.expect(0.U)
    }
  }

  // ============================================================================
  // 2. 队列管理测试
  // ============================================================================

  it should "在满队列时拒绝入队" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 32 条指令填满队列
      for (i <- 0 until 32) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i % 31 + 1),
          phyRd = (32 + i),
          phyOld = (i % 31 + 1),
          branchMask = 0
        )
        dut.io.controlInit.ready.expect(true.B)
        dut.io.dataInit.ready.expect(true.B)
        dut.clock.step()
      }

      // 尝试入队第 33 条指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000080L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 64, phyOld = 1, branchMask = 0)
      dut.io.controlInit.ready.expect(false.B)
      dut.io.dataInit.ready.expect(false.B)
    }
  }

  it should "正确处理空队列状态" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 空队列时，没有指令可以提交
      dut.io.commitRAT.valid.expect(false.B)
      dut.io.storeEnable.expect(false.B)
      dut.io.csrEnable.expect(false.B)
      dut.io.mret.expect(false.B)
      dut.io.sret.expect(false.B)
      dut.io.fenceI.expect(false.B)
      dut.io.sfenceVma.valid.expect(false.B)
    }
  }

  it should "正确管理队列指针" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i + 1),
          phyRd = (32 + i),
          phyOld = (i + 1),
          branchMask = 0
        )
        dut.io.freeRobID.expect(i.U)
        dut.clock.step()
      }

      // 完成并提交第一条指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // 提交
      setDefaultInputs(dut)
      dut.clock.step()

      // 验证队列指针正确
      // 下一条指令应该分配 robId = 3
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x8000000cL, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 4, phyRd = 35, phyOld = 4, branchMask = 0)
      dut.io.freeRobID.expect(3.U)
    }
  }

  it should "正确处理队列回绕" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 31 条指令
      for (i <- 0 until 32) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i % 31 + 1),
          phyRd = (32 + i),
          phyOld = (i % 31 + 1),
          branchMask = 0
        )
        dut.io.freeRobID.expect(i.U)
        dut.io.dataInit.ready.expect(true.B)
        dut.clock.step()
      }

      // 完成并提交所有指令
      for (i <- 0 until 31) {
        setDefaultInputs(dut)
        setCDB(dut, robId = i, phyRd = (32 + i), data = 0x100)
        dut.clock.step()
        setDefaultInputs(dut)
        dut.clock.step()
      }

      // 验证队列已空
      // 下一条指令应该分配 robId = 0（回绕）
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x8000007cL, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.io.freeRobID.expect(0.U)
    }
  }

  // ============================================================================
  // 3. 特殊指令测试
  // ============================================================================

  it should "正确处理 Store 指令序列化" in {
    test(new ROB) { dut =>

      // 入队一条 Store 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.STORE)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // Store 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // IDLE -> WAIT
      setDefaultInputs(dut)
      // Store 指令应该拉高 storeEnable 信号
      dut.io.storeEnable.expect(true.B)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      // Store 指令应该提交
      dut.io.commitRAT.valid.expect(true.B)
      // 信号应该被重置
      dut.io.storeEnable.expect(false.B)
      dut.clock.step()

      setDefaultInputs(dut)
      dut.io.commitRAT.valid.expect(false.B)
    }
  }

  it should "正确处理 CSR 指令序列化" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 CSR 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.CSR)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 信号被拉高
      dut.io.csrPending.expect(true.B)

      // CSR 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // IDLE -> WAIT
      setDefaultInputs(dut)
      // CSR 指令应该拉高 csrEnable 信号
      dut.io.csrEnable.expect(true.B)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      // CSR 指令应该提交并触发全局冲刷
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.isCSR.expect(true.B)
      dut.io.csrEnable.expect(false.B)
      dut.clock.step()

      setDefaultInputs(dut)
      dut.io.commitRAT.valid.expect(false.B)
      dut.io.csrPending.expect(false.B)
    }
  }

  it should "正确处理 MRET 指令" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 MRET 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.MRET)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 信号被拉高
      dut.io.csrPending.expect(true.B)

      // MRET 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // MRET 指令应该拉高 mret 信号并立即提交
      dut.io.mret.expect(true.B)
      dut.io.commitRAT.valid.expect(true.B)

      dut.clock.step()
      dut.io.csrPending.expect(false.B)
    }
  }

  it should "正确处理 SRET 指令" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 SRET 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.SRET)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 信号被拉高
      dut.io.csrPending.expect(true.B)

      // SRET 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // SRET 指令应该拉高 sret 信号并立即提交
      dut.io.sret.expect(true.B)
      dut.io.commitRAT.valid.expect(true.B)

      dut.clock.step()
      dut.io.csrPending.expect(false.B)
    }
  }

  it should "正确处理 FENCE.I 指令" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 FENCE.I 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.FENCEI)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 信号被拉高
      dut.io.csrPending.expect(true.B)

      // FENCE.I 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // IDLE -> WAIT
      setDefaultInputs(dut)
      // FENCE.I 指令应该拉高 fenceI 信号
      dut.io.fenceI.expect(true.B)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      // FENCE.I 指令应该提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.fenceI.expect(false.B)
      dut.clock.step()

      setDefaultInputs(dut)
      dut.io.commitRAT.valid.expect(false.B)
      dut.io.csrPending.expect(false.B)
    }
  }

  it should "正确处理 SFENCE.VMA 指令" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 SFENCE.VMA 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.SFENCE)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 信号被拉高
      dut.io.csrPending.expect(true.B)

      // SFENCE.VMA 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // IDLE -> WAIT
      setDefaultInputs(dut)
      // SFENCE.VMA 指令应该拉高 sfenceVma 信号
      dut.io.sfenceVma.valid.expect(true.B)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      // SFENCE.VMA 指令应该提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.sfenceVma.valid.expect(false.B)
      dut.clock.step()

      setDefaultInputs(dut)
      dut.io.commitRAT.valid.expect(false.B)
      dut.io.csrPending.expect(false.B)
    }
  }

  it should "正确处理 WFI 指令（当作 NOP）" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 WFI 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.WFI)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // WFI 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // WFI 指令应该直接提交（当作 NOP）
      dut.io.commitRAT.valid.expect(true.B)
    }
  }

  // ============================================================================
  // 4. 分支冲刷测试
  // ============================================================================

  it should "正确处理分支预测错误冲刷" in {
    test(new ROB) { dut =>

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i + 1),
          phyRd = (32 + i),
          phyOld = (i + 1),
          branchMask = (1 << i)
        )
        dut.clock.step()
      }

      // 分支预测错误，冲刷第一条指令（branchMask = 1）
      setDefaultInputs(dut)
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.io.branchRobId.poke(0.U)
      dut.clock.step()

      // 验证 iEpoch 已更新
      dut.io.iEpoch.expect(1.U)

      // 验证第二条指令的 branchMask 已更新（移除 branchOH）
      // 由于第一条指令被冲刷，第二条指令应该可以提交
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 33, data = 0x200)
      dut.clock.step()

      // 验证提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.commitRAT.bits.archRd.expect(1.U)
      dut.clock.step()

      setDefaultInputs(dut)
      dut.io.commitRAT.valid.expect(false.B) // 指令耗尽
    }
  }

  it should "正确处理分支预测正确更新" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条分支指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.BRANCH)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 1)
      dut.clock.step()

      // 分支预测正确，不冲刷
      setDefaultInputs(dut)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(1.U)
      dut.io.branchRobId.poke(0.U)
      dut.clock.step()

      // 验证 iEpoch 未更新
      dut.io.iEpoch.expect(0.U)

      // 验证 branchMask 已移除
      // 由于 branchMask 被移除，指令应该可以提交
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // 验证提交
      dut.io.commitRAT.valid.expect(true.B)
    }
  }

  it should "正确管理分支掩码" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 3 条指令，设置不同的 branchMask
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 1)
      dut.clock.step()

      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000004L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 2, phyRd = 33, phyOld = 2, branchMask = 3)
      dut.clock.step()

      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000008L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 3, phyRd = 34, phyOld = 3, branchMask = 7)
      dut.clock.step()

      // 分支预测正确，移除第一个分支的掩码
      setDefaultInputs(dut)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(1.U)
      dut.io.branchRobId.poke(0.U)
      dut.clock.step()

      // 完成并提交第一条指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // 验证提交
      dut.io.commitRAT.valid.expect(true.B)
    }
  }

  // ============================================================================
  // 5. 全局冲刷测试
  // ============================================================================

  it should "正确执行 Global Flush 清空队列" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i + 1),
          phyRd = (32 + i),
          phyOld = (i + 1),
          branchMask = 0
        )
        dut.clock.step()
      }

      // Global Flush
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证所有输出被清空
      dut.io.commitRAT.valid.expect(false.B)
      dut.io.storeEnable.expect(false.B)
      dut.io.csrEnable.expect(false.B)
      dut.io.exception.valid.expect(false.B)

      // 验证纪元已更新
      dut.io.iEpoch.expect(1.U)
      dut.io.dEpoch.expect(1.U)

      // 验证 CSRPending 被重置
      dut.io.csrPending.expect(false.B)

      // 取消冲刷
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(false.B)
      dut.clock.step()

      // 验证可以重新入队
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.io.controlInit.ready.expect(true.B)
      dut.io.dataInit.ready.expect(true.B)
      dut.io.freeRobID.expect(0.U)
    }
  }

  it should "在 Global Flush 时更新纪元" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 初始纪元
      dut.io.iEpoch.expect(0.U)
      dut.io.dEpoch.expect(0.U)

      // Global Flush
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证纪元已更新
      dut.io.iEpoch.expect(1.U)
      dut.io.dEpoch.expect(1.U)

      // 再次 Global Flush
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证纪元继续更新
      dut.io.iEpoch.expect(2.U)
      dut.io.dEpoch.expect(2.U)
    }
  }

  it should "在 Global Flush 时重置状态机" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 Store 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.STORE)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // Store 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // Store 指令应该拉高 storeEnable 信号
      dut.io.storeEnable.expect(true.B)

      // Global Flush
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证状态机被重置
      dut.io.storeEnable.expect(false.B)
      dut.io.commitRAT.valid.expect(false.B)
    }
  }

  it should "Global Flush 优先于 branchFlush" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i + 1),
          phyRd = (32 + i),
          phyOld = (i + 1),
          branchMask = (1 << i)
        )
        dut.clock.step()
      }

      // 同时设置 Global Flush 和 branchFlush
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(true.B)
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.io.branchRobId.poke(0.U)
      dut.clock.step()

      // 验证 Global Flush 优先（iEpoch 和 dEpoch 都更新）
      dut.io.iEpoch.expect(1.U)
      dut.io.dEpoch.expect(1.U)
    }
  }

  // ============================================================================
  // 6. 异常处理测试
  // ============================================================================

  it should "正确处理异常指令提交" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条异常指令
      setDefaultInputs(dut)
      setControlInit(
        dut,
        pc = 0x80000000L,
        exceptionValid = true,
        exceptionCause = 2, // ILLEGAL_INSTRUCTION
        exceptionTval = 0x80000000L,
        specialInstr = SpecialInstr.NONE
      )
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 异常指令应该被标记为已完成
      // 可以直接提交
      dut.io.commitRAT.valid.expect(true.B)

      // 验证异常信息被正确输出
      dut.io.exception.valid.expect(true.B)
      dut.io.exception.cause.expect(2.U)
      dut.io.exception.tval.expect(0x80000000L.U)
    }
  }

  it should "正确处理执行阶段异常" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条普通指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 执行阶段产生异常
      setDefaultInputs(dut)
      setCDB(
        dut,
        robId = 0,
        phyRd = 32,
        data = 0,
        exceptionValid = true,
        exceptionCause = 5, // LOAD_ADDRESS_MISALIGNED
        exceptionTval = 0x1000L
      )
      dut.clock.step()

      // 验证异常信息被更新
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.exception.valid.expect(true.B)
      dut.io.exception.cause.expect(5.U)
      dut.io.exception.tval.expect(0x1000L.U)
    }
  }

  it should "正确处理精确异常" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 3 条指令
      for (i <- 0 until 3) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i + 1),
          phyRd = (32 + i),
          phyOld = (i + 1),
          branchMask = 0
        )
        dut.clock.step()
      }

      // 完成第二条指令（产生异常）
      setDefaultInputs(dut)
      setCDB(
        dut,
        robId = 1,
        phyRd = 33,
        data = 0,
        exceptionValid = true,
        exceptionCause = 2,
        exceptionTval = 0x80000004L
      )
      dut.clock.step()

      // 完成第三条指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 2, phyRd = 34, data = 0x300)
      dut.clock.step()

      // 第一条指令未完成，无法提交
      // 第二条指令虽然完成，但不是队头，无法提交
      dut.io.commitRAT.valid.expect(false.B)
      dut.io.exception.valid.expect(false.B)

      // 完成第一条指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // 第一条指令可以提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.exception.valid.expect(false.B)

      // 提交第一条指令后，第二条指令成为队头
      setDefaultInputs(dut)
      dut.clock.step()

      // 第二条指令可以提交并输出异常
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.exception.valid.expect(true.B)
      dut.io.exception.cause.expect(2.U)
      dut.io.exception.tval.expect(0x80000004L.U)
    }
  }

  // ============================================================================
  // 7. CSRPending 信号测试
  // ============================================================================

  it should "在 CSR 指令入队时拉高 CSRPending" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 CSR 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.CSR)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 被拉高
      dut.io.csrPending.expect(true.B)
    }
  }

  it should "在 CSR 指令提交时拉低 CSRPending" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 CSR 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.CSR)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 被拉高
      dut.io.csrPending.expect(true.B)

      // CSR 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // CSR 指令应该拉高 csrEnable 信号
      dut.io.csrEnable.expect(true.B)

      // 模拟 ZICSRU 确认
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      dut.clock.step()
      // 验证 CSRPending 被拉低
      dut.io.csrPending.expect(false.B)
    }
  }

  it should "在多条 CSR 指令入队时保持 CSRPending" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队第一条 CSR 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.CSR)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 被拉高
      dut.io.csrPending.expect(true.B)

      // 入队第二条 CSR 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000004L, specialInstr = SpecialInstr.CSR)
      setDataInit(dut, archRd = 2, phyRd = 33, phyOld = 2, branchMask = 0)
      dut.clock.step()

      // 验证 CSRPending 保持高电平
      dut.io.csrPending.expect(true.B)

      // 完成并提交第一条 CSR 指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      dut.io.csrEnable.expect(true.B)

      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // 验证 CSRPending 保持高电平（因为还有第二条 CSR 指令）
      dut.io.csrPending.expect(true.B)
    }
  }

  // ============================================================================
  // 8. 纪元管理测试
  // ============================================================================

  it should "在分支冲刷时更新 iEpoch" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 初始纪元
      dut.io.iEpoch.expect(0.U)
      dut.io.dEpoch.expect(0.U)

      // 分支冲刷
      setDefaultInputs(dut)
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.io.branchRobId.poke(0.U)
      dut.clock.step()

      // 验证 iEpoch 已更新，dEpoch 未更新
      dut.io.iEpoch.expect(1.U)
      dut.io.dEpoch.expect(0.U)
    }
  }

  it should "在全局冲刷时更新 iEpoch 和 dEpoch" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 初始纪元
      dut.io.iEpoch.expect(0.U)
      dut.io.dEpoch.expect(0.U)

      // 全局冲刷
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证 iEpoch 和 dEpoch 都已更新
      dut.io.iEpoch.expect(1.U)
      dut.io.dEpoch.expect(1.U)
    }
  }

  it should "正确纪元回绕" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 连续触发 4 次全局冲刷
      for (i <- 0 until 4) {
        setDefaultInputs(dut)
        dut.io.globalFlush.poke(true.B)
        dut.clock.step()
        setDefaultInputs(dut)
        dut.io.globalFlush.poke(false.B)
        dut.clock.step()
      }

      // 验证纪元回绕（2-bit 纪元，4 次后回到 0）
      dut.io.iEpoch.expect(0.U)
      dut.io.dEpoch.expect(0.U)
    }
  }

  // ============================================================================
  // 9. 综合场景测试
  // ============================================================================

  it should "正确处理复杂的入队、完成、提交流程" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队 5 条指令
      for (i <- 0 until 5) {
        setDefaultInputs(dut)
        setControlInit(
          dut,
          pc = (0x80000000L + i * 4).toLong,
          specialInstr = SpecialInstr.NONE
        )
        setDataInit(
          dut,
          archRd = (i + 1),
          phyRd = (32 + i),
          phyOld = (i + 1),
          branchMask = 0
        )
        dut.clock.step()
      }

      // 乱序完成指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 2, phyRd = 34, data = 0x300)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 4, phyRd = 32, data = 0x100)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 36, data = 0x500)
      dut.clock.step()

      // 只有第一条指令可以提交（队头）
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.commitRAT.bits.archRd.expect(1.U)

      // 提交第一条指令
      setDefaultInputs(dut)
      dut.clock.step()

      // 第二条指令仍未完成，无法提交
      dut.io.commitRAT.valid.expect(false.B)

      // 完成第二条指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 1, phyRd = 33, data = 0x200)
      dut.clock.step()

      // 第二条指令可以提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.commitRAT.bits.archRd.expect(2.U)
      dut.clock.step()

      // 第三条指令已完成，可以提交
      setDefaultInputs(dut)
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.commitRAT.bits.archRd.expect(3.U)
    }
  }

  it should "正确处理混合特殊指令场景" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条普通指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.clock.step()

      // 入队一条 Store 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000004L, specialInstr = SpecialInstr.STORE)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // 入队一条 CSR 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000008L, specialInstr = SpecialInstr.CSR)
      setDataInit(dut, archRd = 2, phyRd = 33, phyOld = 2, branchMask = 0)
      dut.clock.step()

      // 完成所有指令
      setDefaultInputs(dut)
      setCDB(dut, robId = 1, phyRd = 0, data = 0)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 2, phyRd = 33, data = 0x200)
      dut.clock.step()

      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 32, data = 0x100)
      dut.clock.step()

      // 提交第一条指令
      setDefaultInputs(dut)
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.commitRAT.bits.archRd.expect(1.U)
      dut.clock.step()

      // Store 指令应该拉高 storeEnable
      setDefaultInputs(dut)
      dut.io.storeEnable.expect(true.B)
      dut.clock.step()

      // 模拟 LSU 确认
      setDefaultInputs(dut)
      setCDB(dut, robId = 1, phyRd = 0, data = 0)
      // Store 指令应该提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.storeEnable.expect(false.B)

      dut.clock.step()
      setDefaultInputs(dut)
      // CSR 指令应该拉高 csrEnable
      dut.io.csrEnable.expect(true.B)

      dut.clock.step()
      // 模拟 ZICSRU 确认
      setDefaultInputs(dut)
      setCDB(dut, robId = 2, phyRd = 33, data = 0x200)
      // CSR 指令应该提交
      dut.io.commitRAT.valid.expect(true.B)
      dut.io.csrEnable.expect(false.B)
      dut.clock.step()


      dut.io.csrPending.expect(false.B)
    }
  }

  it should "正确处理分支冲刷与特殊指令混合场景" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条普通指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 1)
      dut.clock.step()

      // 入队一条 CSR 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000004L, specialInstr = SpecialInstr.CSR)
      setDataInit(dut, archRd = 2, phyRd = 33, phyOld = 2, branchMask = 1)
      dut.clock.step()

      // 分支预测错误，冲刷所有指令
      setDefaultInputs(dut)
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.io.branchRobId.poke(0.U)
      dut.clock.step()

      setDefaultInputs(dut)
      // 验证 iEpoch 已更新
      dut.io.iEpoch.expect(1.U)
      // 验证 CSRPending 被重置
      dut.io.csrPending.expect(false.B)

      // 验证可以重新入队
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.NONE)
      setDataInit(dut, archRd = 1, phyRd = 32, phyOld = 1, branchMask = 0)
      dut.io.controlInit.ready.expect(true.B)
      dut.io.dataInit.ready.expect(true.B)
    }
  }

  it should "正确处理全局冲刷与特殊指令混合场景" in {
    test(new ROB) { dut =>
      setDefaultInputs(dut)

      // 入队一条 Store 指令
      setDefaultInputs(dut)
      setControlInit(dut, pc = 0x80000000L, specialInstr = SpecialInstr.STORE)
      setDataInit(dut, archRd = 0, phyRd = 0, phyOld = 0, branchMask = 0)
      dut.clock.step()

      // Store 指令完成
      setDefaultInputs(dut)
      setCDB(dut, robId = 0, phyRd = 0, data = 0)
      dut.clock.step()

      // Store 指令应该拉高 storeEnable
      dut.io.storeEnable.expect(true.B)

      // 全局冲刷
      setDefaultInputs(dut)
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证状态机被重置
      dut.io.storeEnable.expect(false.B)
      dut.io.commitRAT.valid.expect(false.B)

      // 验证纪元已更新
      dut.io.iEpoch.expect(1.U)
      dut.io.dEpoch.expect(1.U)
    }
  }
}
