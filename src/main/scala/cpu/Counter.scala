package cpu

import chisel3._
import chisel3.util._

class Counter(max: Int) extends Module {
  val io = IO(new Bundle {
    val en  = Input(Bool())
    val out = Output(UInt(log2Ceil(max + 1).W))
  })

  val count = RegInit(0.U(log2Ceil(max + 1).W))

  when (io.en) {
    when (count === max.U) {
      count := 0.U
    } .otherwise {
      count := count + 1.U
    }
  }

  io.out := count
}