package cpu

import chisel3._
import circt.stage.ChiselStage

object Main extends App {
  // 生成 SystemVerilog 到 generated 文件夹
  ChiselStage.emitSystemVerilogFile(
    new Counter(10),
    args = Array("--target-dir", "generated")
  )
}