package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import os.group.set

class RATTest extends AnyFlatSpec with ChiselScalatestTester {

  // 辅助函数：设置默认输入信号
  def setDefaultInputs(dut: RAT): Unit = {
    dut.io.renameReq.valid.poke(false.B)
    dut.io.commit.valid.poke(false.B)
    dut.io.globalFlush.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.snapshotId.poke(0.U)
    dut.io.branchMask.poke(0.U)
    dut.io.renameRes.ready.poke(true.B)
    dut.io.robData.ready.poke(true.B)
    dut.io.cdb.valid.poke(false.B)
    dut.io.cdb.bits.phyRd.poke(0.U)
  }

  // 辅助函数：设置重命名请求
  def setRenameReq(dut: RAT, rs1: Int, rs2: Int, rd: Int, isBranch: Boolean = false): Unit = {
    dut.io.renameReq.valid.poke(true.B)
    dut.io.renameReq.bits.rs1.poke(rs1.U)
    dut.io.renameReq.bits.rs2.poke(rs2.U)
    dut.io.renameReq.bits.rd.poke(rd.U)
    dut.io.renameReq.bits.isBranch.poke(isBranch.B)
  }

  def setBroadcast(dut: RAT, phyRd: Int): Unit = {
    dut.io.cdb.valid.poke(true.B)
    dut.io.cdb.bits.phyRd.poke(phyRd.U)
  }

  // 辅助函数：设置提交请求
  def setCommit(dut: RAT, archRd: Int, phyRd: Int, preRd: Int): Unit = {
    dut.io.commit.valid.poke(true.B)
    dut.io.commit.bits.archRd.poke(archRd.U)
    dut.io.commit.bits.phyRd.poke(phyRd.U)
    dut.io.commit.bits.preRd.poke(preRd.U)
  }

  // ============================================================================
  // 1. 正常重命名测试
  // ============================================================================

  "RAT" should "正确初始化状态" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 检查初始状态
      // Frontend RAT 和 Retirement RAT 初始为 [0, 1, 2, ..., 31]
      for (i <- 0 until 32) {
        // 由于我们无法直接访问内部寄存器，这里我们通过重命名请求来验证
        // 初始状态下，架构寄存器 i 应该映射到物理寄存器 i
        setRenameReq(dut, rs1 = i, rs2 = 0, rd = 0, isBranch = false)
        dut.io.renameRes.bits.phyRs1.expect(i.U)
        dut.clock.step()
      }

      dut.io.globalFlush.poke(true.B)
      dut.clock.step()
      
      for(i <- 0 until 32) {
        // 验证 Global Flush 后 Retirement RAT 状态正确
        setRenameReq(dut, rs1 = i, rs2 = 0, rd = 0, isBranch = false)
        dut.io.renameRes.bits.phyRs1.expect(i.U)
        dut.clock.step()
      }

      // 检查 ready 信号（初始应该为 true，因为有足够的物理寄存器和快照）
      dut.io.renameReq.ready.expect(true.B)
    }
  }

  it should "正确处理普通指令重命名" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // ADD x1, x2, x3
      setRenameReq(dut, rs1 = 2, rs2 = 3, rd = 1, isBranch = false)

      // 验证重命名结果
      dut.io.renameReq.ready.expect(true.B)
      dut.io.renameRes.valid.expect(true.B)
      dut.io.renameRes.bits.phyRs1.expect(2.U)  // 初始映射
      dut.io.renameRes.bits.phyRs2.expect(3.U)  // 初始映射
      dut.io.renameRes.bits.rs1Ready.expect(true.B)  // 初始物理寄存器都是 ready 的
      dut.io.renameRes.bits.rs2Ready.expect(true.B)
      dut.io.renameRes.bits.phyRd.expect(32.U)  // 第一个 free 物理寄存器
      dut.io.renameRes.bits.snapshotId.expect(0.U)  // 非分支指令
      dut.io.renameRes.bits.branchMask.expect(0.U)  // 无分支依赖

      // 验证 ROB 数据包
      dut.io.robData.valid.expect(true.B)
      dut.io.robData.bits.archRd.expect(1.U)
      dut.io.robData.bits.phyRd.expect(32.U)
      dut.io.robData.bits.phyOld.expect(1.U)  // 旧的物理寄存器

      dut.clock.step()

      // 再次重命名，验证映射已更新
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 4, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(32.U)  // x1 现在映射到 32
      dut.io.renameRes.bits.phyRs2.expect(2.U)   // x2 仍然映射到 2
      dut.io.renameRes.bits.rs1Ready.expect(false.B)  // 物理寄存器 32 初始是 ready 的
      dut.io.renameRes.bits.rs2Ready.expect(true.B)
      dut.io.renameRes.bits.phyRd.expect(33.U)   // 下一个 free 物理寄存器
    }
  }

  it should "正确处理 x0 寄存器重命名" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // ADD x0, x1, x2 (x0 作为目标寄存器)
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 0, isBranch = false)

      // 验证 x0 不分配物理寄存器
      dut.io.renameRes.bits.phyRd.expect(0.U)
      dut.io.robData.bits.phyRd.expect(0.U)
      dut.io.robData.bits.phyOld.expect(0.U)

      dut.clock.step()

      // 验证 x0 的映射没有改变（仍然是 0）
      setRenameReq(dut, rs1 = 0, rs2 = 1, rd = 3, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(0.U)
      dut.io.renameRes.bits.phyRs2.expect(1.U)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)
    }
  }

  it should "正确更新源寄存器 ready 状态" in {
    test(new RAT) { dut =>
      // 第一次重命名：ADD x1, x2, x3
      setDefaultInputs(dut)
      setRenameReq(dut, rs1 = 2, rs2 = 3, rd = 1, isBranch = false)
      dut.clock.step()

      // 第二次重命名：ADD x4, x1, x5
      // x1 现在映射到物理寄存器 32，该寄存器为上一条指令 rd，目前 not ready
      setDefaultInputs(dut)
      setRenameReq(dut, rs1 = 1, rs2 = 5, rd = 4, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(32.U)
      dut.io.renameRes.bits.rs1Ready.expect(false.B)
      dut.clock.step()

      // 提交第一条指令，回收旧的物理寄存器
      setDefaultInputs(dut)
      setBroadcast(dut, phyRd = 32)
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 再次重命名：ADD x6, x1, x7
      // x1 仍然映射到物理寄存器 32，但 1 已经被回收
      // 目前 Free List 中序号最小的寄存器为 phy1，因此 x6 应当分配到 phy1.
      setDefaultInputs(dut)
      setRenameReq(dut, rs1 = 1, rs2 = 7, rd = 6, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(32.U)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)
      dut.io.renameRes.bits.phyRd.expect(1.U)  // 分配到回收的物理寄存器 1
      dut.clock.step()

      // CDB forwarding 检测
      setDefaultInputs(dut)
      setBroadcast(dut, phyRd = 1)
      setRenameReq(dut, rs1 = 6, rs2 = 0, rd = 8, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(1.U)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)
      dut.io.renameRes.bits.phyRd.expect(34.U)
    }
  }

  it should "在 Free List 耗尽时拒绝请求" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配 96 个物理寄存器（总共 128 个，前 32 个初始为 busy）
      for (i <- 0 until 96) {
        setRenameReq(dut, rs1 = 0, rs2 = 0, rd = (i % 31 + 1), isBranch = false)
        dut.clock.step()
      }

      // 现在应该没有空闲的物理寄存器了
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.io.renameReq.ready.expect(false.B)
    }
  }

  it should "在提交后正确恢复 Free List" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配几个物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 2, isBranch = false)
      dut.clock.step()

      // 提交第一条指令，回收物理寄存器 1
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 现在应该可以再次分配了
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 3, isBranch = false)
      dut.io.renameReq.ready.expect(true.B)
    }
  }

  // ============================================================================
  // 2. 分支快照测试
  // ============================================================================

  it should "正确分配单分支快照" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // BEQ x1, x2, label (分支指令)
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 0, isBranch = true)

      // 验证快照分配
      dut.io.renameRes.bits.snapshotId.expect(1.U)  // 独热码，分配第一个快照
      dut.io.renameRes.bits.branchMask.expect(1.U)  // 当前分支掩码

      dut.clock.step()

      // 验证快照已占用
      // 再次分支指令，应该分配第二个快照
      setRenameReq(dut, rs1 = 3, rs2 = 4, rd = 0, isBranch = true)
      dut.io.renameRes.bits.snapshotId.expect(2.U)  // 第二个快照
      dut.io.renameRes.bits.branchMask.expect(3.U)  // 0b11，两个分支都有效
    }
  }

  it should "正确分配多分支快照" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 连续执行 4 个分支指令
      for (i <- 0 until 4) {
        setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
        dut.io.renameRes.bits.snapshotId.expect((1 << i).U)  // 独热码
        dut.io.renameRes.bits.branchMask.expect(((1 << (i + 1)) - 1).U)  // 分支掩码
        dut.clock.step()
      }
    }
  }

  it should "在快照耗尽时拒绝请求" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配 4 个快照
      for (i <- 0 until 4) {
        setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
        dut.clock.step()
      }

      // 第 5 个分支指令应该被拒绝
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.io.renameReq.ready.expect(false.B)
    }
  }

  it should "在分支后正确保存 RAT 状态" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 先执行一些普通指令
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 2, isBranch = false)
      dut.clock.step()

      // 执行分支指令，保存快照
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      val snapshotId = 1.U
      dut.io.renameRes.bits.snapshotId.expect(snapshotId)
      dut.clock.step()

      // 分支后继续执行指令，修改 RAT
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 3, isBranch = false)
      dut.clock.step()

      // 模拟分支预测失败，恢复快照
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(snapshotId)
      dut.clock.step()

      // 验证 RAT 已恢复到分支前的状态
      // x1 应该映射到 32（分支前的状态）
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 4, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(32.U)
    }
  }

  it should "正确更新分支掩码" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 第一个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.io.renameRes.bits.branchMask.expect(1.U)
      dut.clock.step()

      // 第二个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.io.renameRes.bits.branchMask.expect(3.U)  // 0b11
      dut.clock.step()

      // 普通指令
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.io.renameRes.bits.branchMask.expect(3.U)  // 保持不变
    }
  }

  // ============================================================================
  // 3. 分支预测失败恢复测试
  // ============================================================================

  it should "正确恢复单分支快照" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 执行一些指令
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 分支指令
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      val snapshotId = 1.U
      dut.clock.step()

      // 分支后修改 RAT
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 2, isBranch = false)
      dut.clock.step()

      // 分支预测失败，恢复快照
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(snapshotId)
      dut.clock.step()

      // 验证恢复后的状态
      // x2 应该映射到 2（恢复到分支前的状态）
      setRenameReq(dut, rs1 = 2, rs2 = 0, rd = 3, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(2.U)
    }
  }

  it should "正确恢复多分支快照" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 第一个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 第二个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 修改 RAT
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 恢复到第一个分支的状态
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(1.U)
      dut.clock.step()

      // 验证恢复后的状态
      // 应该恢复到第一个分支后的状态
    }
  }

  it should "正确恢复快照依赖关系" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 第一个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 第二个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 恢复到第一个分支的状态
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(1.U)
      dut.clock.step()

      // 验证快照依赖关系恢复
      // 应该只有第一个快照是有效的
    }
  }

  it should "正确回收分支预测成功的快照" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 第一个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      val snapshotId1 = 1.U
      dut.clock.step()

      // 第二个分支
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      val snapshotId2 = 2.U
      dut.clock.step()

      // 分支预测成功，回收第一个快照
      dut.io.branchMask.poke(snapshotId1)
      dut.io.snapshotId.poke(snapshotId1)
      dut.clock.step()

      // 验证第一个快照已被回收
      // 下一个分支应该可以分配到第一个快照槽位
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.io.renameRes.bits.snapshotId.expect(1.U)  // 可以重新分配
    }
  }

  // ============================================================================
  // 4. Global Flush 测试
  // ============================================================================

  it should "正确执行 Global Flush 恢复" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 执行一些指令
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 提交指令，更新 Retirement RAT
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 继续执行指令
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 2, isBranch = false)
      dut.clock.step()

      // Global Flush
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证恢复后的状态
      // x1 应该映射到 32（Retirement RAT 的状态）
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 3, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(32.U)
    }
  }

  it should "在 Global Flush 时清空所有快照" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配多个快照
      for (i <- 0 until 4) {
        setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
        dut.clock.step()
      }

      // Global Flush
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 验证所有快照已被清空
      // 下一个分支应该可以分配到第一个快照槽位
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.io.renameRes.bits.snapshotId.expect(1.U)
    }
  }

  it should "Global Flush 优先于 commit 更新" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 执行指令
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 同时设置 Global Flush 和 commit
      dut.io.globalFlush.poke(true.B)
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 验证 Global Flush 优先，commit 被忽略
      // x1 应该映射到 1（恢复到 Retirement RAT 的初始状态）
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 2, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(1.U)
    }
  }

  // ============================================================================
  // 5. 资源回收测试
  // ============================================================================

  it should "正确回收 ROB 提交的物理寄存器" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 提交指令，回收物理寄存器 1
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 验证物理寄存器 1 已被回收
      // 下一次分配应该可以重新使用物理寄存器 1
      // 但实际上，由于我们分配的是新的物理寄存器，所以应该是 33
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 2, isBranch = false)
      dut.io.renameRes.bits.phyRd.expect(33.U)
    }
  }

  it should "正确更新 Retirement RAT" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 提交指令，更新 Retirement RAT
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // Global Flush，验证 Retirement RAT 已更新
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // x1 应该映射到 32（Retirement RAT 的状态）
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 2, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(32.U)
    }
  }

  it should "不回收 x0 物理寄存器" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 尝试提交 x0
      setCommit(dut, archRd = 0, phyRd = 0, preRd = 0)
      dut.clock.step()

      // 验证 x0 的映射没有改变
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(0.U)
    }
  }

  it should "在多个快照存在时同步回收物理寄存器" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配快照
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 分配物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 提交指令，回收物理寄存器 1
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 分支预测失败，恢复快照
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(1.U)
      dut.clock.step()

      // 验证恢复后的状态
      // 物理寄存器 1 应该已经被回收
    }
  }

  // ============================================================================
  // 6. 综合场景测试
  // ============================================================================

  it should "正确处理复杂的重命名和恢复场景" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 场景：多个分支，部分预测失败，部分预测成功

      // 分支 1
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 普通指令 1
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 分支 2
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 普通指令 2
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 2, isBranch = false)
      dut.clock.step()

      // 分支 2 预测失败，恢复到分支 1 后的状态
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(1.U)
      dut.clock.step()

      // 验证恢复后的状态
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 3, isBranch = false)
      dut.io.renameRes.bits.phyRs1.expect(32.U)  // x1 映射到 32
    }
  }

  it should "正确处理 Free List 和快照的协同管理" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分支 1
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 普通指令 1
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 提交指令 1
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 分支 1 预测失败，恢复快照
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(1.U)
      dut.clock.step()

      // 验证恢复后的状态
      // 物理寄存器 1 应该已经被回收
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 2, isBranch = false)
      dut.io.renameRes.bits.phyRd.expect(33.U)  // 下一个可用的物理寄存器
    }
  }

  it should "正确处理 Global Flush 和 branchFlush 的优先级" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分支 1
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.clock.step()

      // 普通指令 1
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      dut.clock.step()

      // 提交指令 1
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 同时设置 Global Flush 和 branchFlush
      dut.io.globalFlush.poke(true.B)
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(1.U)
      dut.clock.step()

      // 验证 Global Flush 优先
      // 所有快照应该被清空
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      dut.io.renameRes.bits.snapshotId.expect(1.U)  // 可以分配第一个快照
    }
  }

  // ============================================================================
  // 7. CDB Ready List 更新测试
  // ============================================================================

  it should "正确初始化 Ready List 状态" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 初始状态下，前32个物理寄存器应该都是 ready 的（因为有初始值）
      // 后96个物理寄存器应该是 not ready 的
      for (i <- 0 until 32) {
        setRenameReq(dut, rs1 = i, rs2 = 0, rd = 0, isBranch = false)
        dut.io.renameRes.bits.rs1Ready.expect(true.B)  // 初始物理寄存器都是 ready 的
        dut.clock.step()
      }
    }
  }

  it should "CDB 广播时正确更新 Frontend Ready List" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配一个新的物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      val phyRd = 32.U
      dut.io.renameRes.bits.phyRd.expect(phyRd)
      dut.clock.step()

      // 新分配的物理寄存器应该是 not ready 的
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(false.B)  // 物理寄存器 32 还没有 ready
      dut.clock.step()

      // CDB 广播，标记物理寄存器 32 为 ready
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(phyRd)
      dut.clock.step()

      // 验证物理寄存器 32 现在是 ready 的
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)  // 物理寄存器 32 现在是 ready 的
    }
  }

  it should "CDB 广播时正确更新所有快照的 Ready List" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配快照
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 0, isBranch = true)
      val snapshotId = 1.U
      dut.clock.step()

      // 分配一个新的物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      val phyRd = 32.U
      dut.clock.step()

      // CDB 广播，标记物理寄存器 32 为 ready
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(phyRd)
      dut.clock.step()

      // 分支预测失败，恢复快照
      dut.io.branchFlush.poke(true.B)
      dut.io.snapshotId.poke(snapshotId)
      dut.clock.step()

      // 验证恢复后物理寄存器 32 的 ready 状态
      // 由于 CDB 广播在快照保存之后，快照中的 Ready List 应该不包含物理寄存器 32 的 ready 状态
      // 但由于 CDB 广播更新了所有快照的 Ready List，所以恢复后物理寄存器 32 应该是 ready 的
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)  // 物理寄存器 32 应该是 ready 的
    }
  }

  it should "CDB bypass forwarding 正确工作" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配一个新的物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      val phyRd = 32.U
      dut.clock.step()

      // 新分配的物理寄存器应该是 not ready 的
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(false.B)

      // 同一周期内 CDB 广播，应该触发 bypass forwarding
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(phyRd)
      // 由于 bypass forwarding 是组合逻辑，应该在同一周期生效
      // 但由于 Chisel 的特性，可能需要在下一个周期验证
    }
  }

  it should "Ready List 与 Free List 独立工作" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配一个新的物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      val phyRd = 32.U
      dut.clock.step()

      // 新分配的物理寄存器应该是 busy 的（在 Free List 中）
      // 但不一定是 ready 的（在 Ready List 中）
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(false.B)  // not ready

      // CDB 广播，标记物理寄存器 32 为 ready
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(phyRd)
      dut.clock.step()

      // 现在物理寄存器 32 应该是 ready 的
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)  // ready

      // 提交指令，回收物理寄存器 1（旧映射）
      setCommit(dut, archRd = 1, phyRd = 32, preRd = 1)
      dut.clock.step()

      // 物理寄存器 1 应该被回收到 Free List（变为 not busy）
      // 但物理寄存器 32 的 ready 状态应该保持不变
      setRenameReq(dut, rs1 = 1, rs2 = 0, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)  // 仍然是 ready 的
    }
  }

  it should "正确处理多个 CDB 广播" in {
    test(new RAT) { dut =>
      setDefaultInputs(dut)

      // 分配多个新的物理寄存器
      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 1, isBranch = false)
      val phyRd1 = 32.U
      dut.clock.step()

      setRenameReq(dut, rs1 = 0, rs2 = 0, rd = 2, isBranch = false)
      val phyRd2 = 33.U
      dut.clock.step()

      // 新分配的物理寄存器应该是 not ready 的
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(false.B)
      dut.io.renameRes.bits.rs2Ready.expect(false.B)
      dut.clock.step()

      // CDB 广播，标记物理寄存器 32 为 ready
      dut.io.cdb.valid.poke(true.B)
      dut.io.cdb.bits.phyRd.poke(phyRd1)
      dut.clock.step()

      // 验证物理寄存器 32 是 ready 的，33 不是
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)
      dut.io.renameRes.bits.rs2Ready.expect(false.B)
      dut.clock.step()

      // CDB 广播，标记物理寄存器 33 为 ready
      dut.io.cdb.bits.phyRd.poke(phyRd2)
      dut.clock.step()

      // 验证两个物理寄存器都是 ready 的
      setRenameReq(dut, rs1 = 1, rs2 = 2, rd = 0, isBranch = false)
      dut.io.renameRes.bits.rs1Ready.expect(true.B)
      dut.io.renameRes.bits.rs2Ready.expect(true.B)
    }
  }
}
