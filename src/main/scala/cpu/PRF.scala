package cpu

import chisel3._
import chisel3.util._

class WriteBackData extends Bundle with CPUConfig {
  val rd = PhyTag
  val data = DataW
}

class PRF extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 读接口（来自 RS）
    val readReq = Flipped(Decoupled(new PrfReadPacket))
    val readResp = Decoupled(new PrfReadData)

    // 写接口（来自 CDB）
    val write = Flipped(Valid(new WriteBackData))
  })

  // 维护状态：128 个 32 位物理寄存器
  val phyRegNum = 1 << PhyRegIdWidth
  val regs = RegInit(VecInit(Seq.fill(phyRegNum)(0.U(32.W))))

  // 读请求处理
  io.readReq.ready := true.B
  val rData1 = WireDefault(0.U(32.W))
  val rData2 = WireDefault(0.U(32.W))
  val readReqValid = io.readReq.valid

  when(io.readReq.fire) {
    // 读取两个源寄存器
    rData1 := regs(io.readReq.bits.raddr1)
    rData2 := regs(io.readReq.bits.raddr2)
  }

  io.readResp.bits.rdata1 := rData1
  io.readResp.bits.rdata2 := rData2
  io.readResp.valid := readReqValid

  // 写回处理
  when(io.write.valid) {
    val rd = io.write.bits.rd
    val data = io.write.bits.data

    when(rd =/= 0.U) {
      regs(rd) := data
    }
  }
}
