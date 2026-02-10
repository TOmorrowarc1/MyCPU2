package cpu

import chisel3._
import chisel3.util._

class CDB extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 输入接口：来自四个执行单元的 CDBMessage
    val alu = Flipped(Decoupled(new CDBMessage))
    val bru = Flipped(Decoupled(new CDBMessage))
    val lsu = Flipped(Decoupled(new CDBMessage))
    val zicsru = Flipped(Decoupled(new CDBMessage))

    // 输出接口：广播 CDBMessage
    val boardcast = Decoupled(new CDBMessage)
  })

  // 创建 4 输入仲裁器
  // 优先级顺序：in(0) > in(1) > in(2) > in(3)
  val arbiter = Module(new Arbiter(new CDBMessage, 4))

  // 连接四个执行单元的输出到仲裁器
  // 优先级：ZICSRU > LSU > BRU > ALU
  arbiter.io.in(0) <> io.zicsru  // 最高优先级
  arbiter.io.in(1) <> io.lsu
  arbiter.io.in(2) <> io.bru
  arbiter.io.in(3) <> io.alu     // 最低优先级

  // 仲裁器输出连接到广播接口
  io.boardcast <> arbiter.io.out
}
