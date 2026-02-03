package cpu

import chisel3._
import chisel3.util._

class RAT extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 来自 Decoder 的重命名请求
    val renameReq = Flipped(Decoupled(new RenameReq))

    // 来自 ROB 的提交信息
    val commit = Flipped(Decoupled(new CommitRAT))

    // 全局冲刷信号
    val globalFlush = Input(Bool())

    // 来自 BRU 的分支决议
    val branchFlush = Input(Bool())
    val snapshotId = Input(UInt(4.W)) // 现在是独热码掩码或全 0
    val branchMask = Input(UInt(4.W))

    // CDB 广播
    val cdb = Flipped(Decoupled(new Bundle {
      val phyRd = PhyTag
    }))

    // 输出到 RS 的重命名结果
    val renameRes = Decoupled(new RenameRes)

    // 输出到 ROB 的数据包
    val robData = Decoupled(new ROBinitDataPacket)
  })

  // 1. 维护信息
  // Frontend RAT: 32 * PhyTag 大小的映射表，表示推测状态，供重命名使用
  val frontendRat = RegInit(
    VecInit(Seq.tabulate(32)(i => i.U(PhyRegIdWidth.W)))
  )
  // Retirement RAT: 提交状态，用于全局冲刷恢复
  val retirementRat = RegInit(
    VecInit(Seq.tabulate(32)(i => i.U(PhyRegIdWidth.W)))
  )

  // Frontend Free List: 位矢量表示 128 个物理寄存器的 busy 状态
  // 初始化: 前 32 个物理寄存器为 busy，其余为 free
  val frontendFreeList = RegInit("hFFFFFFFF".U(128.W))
  // Retirement Free List: ROB 提交的正被占据的物理寄存器对应 busy 位矢量
  val retirementFreeList = RegInit("hFFFFFFFF".U(128.W))

  // Frontend Ready List: 位矢量表示 128 个物理寄存器的 ready 状态
  // 1 表示数据已准备好，0 表示数据未准备好
  val frontendReadyList = RegInit("h00000000".U(128.W))

  // Snapshot: 保存分支指令时的 RAT、Free List 和 Ready List 状态
  class Snapshot extends Bundle with CPUConfig {
    val rat = Vec(32, PhyTag)
    val freeList = UInt(128.W)
    val readyList = UInt(128.W) // Frontend Ready List 的快照
    val snapshotsBusy = UInt(4.W) // 记录拍下快照时刻的依赖关系
  }

  // Snapshots: 4 个快照
  val snapshots = Reg(Vec(4, new Snapshot))
  // 快照槽位占用状态（独热码）
  val snapshotsBusy = RegInit(0.U(4.W))
  val snapshotsBusyAfterAlloc = WireDefault(snapshotsBusy)
  val snapshotsBusyAfterCommit = WireDefault(snapshotsBusy)

  // 中间组合逻辑变量
  val retirementFreeListAfterCommit = WireDefault(retirementFreeList)
  val frontendFreeListAfterAlloc = WireDefault(frontendFreeList)
  val frontendFreeListAfterCommit = WireDefault(frontendFreeListAfterAlloc)
  val frontendReadyListAfterAlloc = WireDefault(frontendReadyList)
  val frontendReadyListAfterBroadcast = WireDefault(frontendReadyListAfterAlloc)
  val snapshotsFreeListsAfterCommit = Wire(Vec(4, UInt(128.W)))
  val snapshotsReadyListsAfterBroadcast = Wire(Vec(4, UInt(128.W)))
  for (i <- 0 until 4) {
    snapshotsFreeListsAfterCommit(i) := snapshots(i).freeList
    snapshotsReadyListsAfterBroadcast(i) := snapshots(i).readyList
  }
  val retirementRatAfterCommit = WireDefault(retirementRat)

  // 2. 重命名与分配逻辑
  // 接收 Decoder 请求
  val renameReq = io.renameReq
  val rs1 = renameReq.bits.rs1
  val rs2 = renameReq.bits.rs2
  val rd = renameReq.bits.rd
  val isBranch = renameReq.bits.isBranch

  // 查找源寄存器的物理寄存器号和 ready 状态
  val phyRs1 = frontendRat(rs1)
  val phyRs2 = frontendRat(rs2)
  val rs1Ready = frontendReadyList(phyRs1)
  val rs2Ready = frontendReadyList(phyRs2)
  // CDB bypass forwarding
  when (io.cdb.valid) {
    when (io.cdb.bits.phyRd === phyRs1 && phyRs1 =/= 0.U) {
      rs1Ready := true.B
    }
    when (io.cdb.bits.phyRd === phyRs2 && phyRs2 =/= 0.U) {
      rs2Ready := true.B
    }
  }

  // 分配逻辑
  val allocPhyRd = WireDefault(0.U(PhyRegIdWidth.W))

  // 查找第一个 free 的物理寄存器 (最低位的 0)
  val freeIndex = PriorityEncoder(~frontendFreeList)
  val hasFree = (~frontendFreeList).orR

  // 如果 rd != x0 且有 free 物理寄存器，则分配
  when(rd =/= 0.U && hasFree) {
    allocPhyRd := freeIndex
  }.otherwise {
    // 如果没有空闲，使用 x0（毕竟不会 valid）
    allocPhyRd := 0.U
  }

  // 预计算分配后的 FreeList 与 ReadyList
  when(renameReq.fire && rd =/= 0.U) {
    frontendFreeListAfterAlloc := frontendFreeList | (1.U(128.W) << allocPhyRd)
    frontendReadyListAfterAlloc := frontendReadyList & ~(1.U(128.W) << allocPhyRd)
  }

  // 3. 独热码快照分配逻辑
  val allocSnapshotOH = PriorityEncoderOH(~snapshotsBusy) // 找出第一个 0 位，返回独热码
  val hasSnapshotFree = (~snapshotsBusy).orR
  val currentBranchMask = snapshotsBusy

  // 拍摄快照
  when(renameReq.fire && isBranch && hasSnapshotFree) {
    for (i <- 0 until 4) {
      when(allocSnapshotOH(i)) {
        snapshots(i).rat := frontendRat
        snapshots(i).freeList := frontendFreeListAfterCommit
        snapshots(i).readyList := frontendReadyListAfterBroadcast
        snapshots(i).snapshotsBusy := snapshotsBusy // 记录当前已存在的快照依赖
      }
    }
    snapshotsBusyAfterAlloc := snapshotsBusy | allocSnapshotOH
  }

  // 4. 回收与冲刷逻辑 (同步更新)
  // ROB 提交回收
  when(io.commit.valid && io.commit.bits.preRd =/= 0.U) {
    val reclaimMask = ~(1.U(128.W) << io.commit.bits.preRd)
    for (i <- 0 until 4) {
      snapshotsFreeListsAfterCommit(i) := snapshots(i).freeList & reclaimMask
    }
    frontendFreeListAfterCommit := frontendFreeListAfterAlloc & reclaimMask
    retirementFreeListAfterCommit := retirementFreeList & reclaimMask
  }

  when(io.commit.valid && io.commit.bits.archRd =/= 0.U) {
    retirementRatAfterCommit(io.commit.bits.archRd) := io.commit.bits.phyRd
  }

  // CDB 广播: 将 phyRd 标记为 ready，更新所有 Ready List
  when(io.cdb.valid && io.cdb.bits.phyRd =/= 0.U) {
    val mask = (1.U(128.W) << io.cdb.bits.phyRd)
    // 更新 Frontend Ready List（基于 AfterAlloc）
    frontendReadyListAfterBroadcast := frontendReadyListAfterAlloc | mask
    // 更新所有快照的 Ready List
    for(i <- 0 until 4) {
      snapshotsReadyListsAfterBroadcast(i) := snapshots(i).readyList | mask
    }
  }

  // 状态更新优先级链
  when(io.globalFlush) {
    // 恢复 Frontend RAT
    frontendRat := retirementRat
    // 恢复 Free List
    frontendFreeList := retirementFreeList
    // 清空所有快照
    snapshotsBusy := 0.U
  }.otherwise {
    // 默认行为：接受提交更新
    retirementRat := retirementRatAfterCommit
    retirementFreeList := retirementFreeListAfterCommit
    for (i <- 0 until 4) {
      snapshots(i).freeList := snapshotsFreeListsAfterCommit(i)
      snapshots(i).readyList := snapshotsReadyListsAfterBroadcast(i)
    }
    // Branch Flush 与 Branch 回收
    when(io.branchFlush) {
      // 1. 分支预测失败恢复
      // 使用 Mux1H 快速选择独热码对应的快照状态
      frontendRat := Mux1H(io.snapshotId, snapshots.map(_.rat))
      frontendFreeList := Mux1H(io.snapshotId, snapshotsFreeListsAfterCommit)
      frontendReadyList := Mux1H(io.snapshotId, snapshotsReadyListsAfterBroadcast)
      // 恢复到该分支点时的快照占用状态（即该分支之前的快照仍然有效）
      snapshotsBusy := Mux1H(io.snapshotId, snapshots.map(_.snapshotsBusy))
    }.otherwise {
      // 2. 正常运行逻辑
      // 只有在无 Flush 的情况下才从分配上更新 Frontend RAT
      when(renameReq.fire && rd =/= 0.U) { frontendRat(rd) := allocPhyRd }
      frontendFreeList := frontendFreeListAfterCommit
      frontendReadyList := frontendReadyListAfterBroadcast
      when(io.branchMask =/= 0.U) {
        snapshotsBusyAfterCommit := snapshotsBusyAfterAlloc & ~io.snapshotId
      }
      snapshotsBusy := snapshotsBusyAfterCommit
    }
  }

  // 5. 输出接口
  io.renameRes.valid := renameReq.fire
  io.renameRes.bits.phyRs1 := phyRs1
  io.renameRes.bits.rs1Ready := rs1Ready
  io.renameRes.bits.phyRs2 := phyRs2
  io.renameRes.bits.rs2Ready := rs2Ready
  io.renameRes.bits.phyRd := allocPhyRd
  // 发送给后端的 ID 也是独热码
  io.renameRes.bits.snapshotId := Mux(isBranch, allocSnapshotOH, 0.U)
  io.renameRes.bits.branchMask := currentBranchMask

  io.renameReq.ready := hasFree && hasSnapshotFree

  io.robData.valid := renameReq.fire
  io.robData.bits.archRd := rd
  io.robData.bits.phyRd := allocPhyRd
  io.robData.bits.phyOld := frontendRat(rd)
  io.robData.bits.branchMask := currentBranchMask

  io.commit.ready := true.B
  io.cdb.ready := true.B
}
