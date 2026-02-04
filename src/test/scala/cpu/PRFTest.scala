package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class PRFTest extends AnyFlatSpec with ChiselScalatestTester {

  // 辅助函数：设置默认输入信号
  def setDefaultInputs(dut: PRF): Unit = {
    dut.io.readReq.valid.poke(false.B)
    dut.io.readReq.bits.raddr1.poke(0.U)
    dut.io.readReq.bits.raddr2.poke(0.U)
    dut.io.readReq.ready.expect(true.B)
    dut.io.write.valid.poke(false.B)
    dut.io.write.bits.rd.poke(0.U)
    dut.io.write.bits.data.poke(0.U)
  }

  // 辅助函数：设置读请求
  def setReadReq(dut: PRF, raddr1: Int, raddr2: Int = 0): Unit = {
    dut.io.readReq.valid.poke(true.B)
    dut.io.readReq.bits.raddr1.poke(raddr1.U)
    dut.io.readReq.bits.raddr2.poke(raddr2.U)
  }

  // 辅助函数：设置写回数据
  def setWrite(dut: PRF, rd: Int, data: BigInt): Unit = {
    dut.io.write.valid.poke(true.B)
    dut.io.write.bits.rd.poke(rd.U)
    dut.io.write.bits.data.poke(data.U)
  }

  // ============================================================================
  // 1. 基本读写操作测试
  // ============================================================================

  "PRF" should "正确初始化状态" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 检查初始状态：所有寄存器应该初始化为 0
      val testRegs = Seq(0, 1, 5, 10, 50, 100, 127)
      for (regAddr <- testRegs) {
        setReadReq(dut, raddr1 = regAddr)
        dut.io.readResp.valid.expect(true.B)
        dut.io.readResp.bits.rdata1.expect(0.U)
        dut.clock.step()
      }

      // 检查 ready 信号
      dut.io.readReq.ready.expect(true.B)
    }
  }

  it should "正确执行基本读写操作" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入寄存器 1
      setWrite(dut, rd = 1, data = 0x12345678L)
      dut.clock.step()

      // 写入寄存器 2
      setWrite(dut, rd = 2, data = 0xABCDEF00L)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 读取寄存器 1
      setReadReq(dut, raddr1 = 1)
      dut.io.readResp.valid.expect(true.B)
      dut.io.readResp.bits.rdata1.expect(0x12345678.U)
      dut.clock.step()

      // 读取寄存器 2
      setReadReq(dut, raddr1 = 2)
      dut.io.readResp.bits.rdata1.expect(0xABCDEF00.U)
      dut.clock.step()
    }
  }

  it should "正确处理 x0 寄存器读写" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 尝试写入 x0 寄存器（应该被忽略）
      setWrite(dut, rd = 0, data = 0xFFFFFFFFL)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 读取 x0 寄存器
      setReadReq(dut, raddr1 = 0, raddr2 = 0)
      dut.io.readResp.valid.expect(true.B)
      dut.io.readResp.bits.rdata1.expect(0.U)
      dut.io.readResp.bits.rdata2.expect(0.U)
    }
  }

  it should "正确处理连续写入同一寄存器" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 第一次写入寄存器 5
      setWrite(dut, rd = 5, data = 0x11111111L)
      dut.clock.step()

      // 第二次写入寄存器 5
      setWrite(dut, rd = 5, data = 0x22222222L)
      dut.clock.step()

      // 第三次写入寄存器 5
      setWrite(dut, rd = 5, data = 0x33333333L)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 读取寄存器 5
      setReadReq(dut, raddr1 = 5)
      dut.io.readResp.bits.rdata1.expect(0x33333333.U)
    }
  }

  // ============================================================================
  // 2. 双读端口测试
  // ============================================================================

  it should "正确同时读取两个不同寄存器" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入寄存器 3 和 4
      setWrite(dut, rd = 3, data = 0x11112222L)
      dut.clock.step()

      setWrite(dut, rd = 4, data = 0x33334444L)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 同时读取寄存器 3 和 4
      setReadReq(dut, raddr1 = 3, raddr2 = 4)
      dut.io.readResp.bits.rdata1.expect(0x11112222.U)
      dut.io.readResp.bits.rdata2.expect(0x33334444.U)
      dut.clock.step()
    }
  }

  it should "正确同时读取同一寄存器" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入寄存器 10
      setWrite(dut, rd = 10, data = 0xABCDEF12L)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 同时读取同一寄存器
      setReadReq(dut, raddr1 = 10, raddr2 = 10)
      dut.io.readResp.bits.rdata1.expect(0xABCDEF12.U)
      dut.io.readResp.bits.rdata2.expect(0xABCDEF12.U)
    }
  }

  it should "正确读取 x0 和其他寄存器" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入寄存器 20
      setWrite(dut, rd = 20, data = 0xDEADBEEFL)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 读取 x0 和寄存器 20
      setReadReq(dut, raddr1 = 0, raddr2 = 20)
      dut.io.readResp.bits.rdata1.expect(0.U)
      dut.io.readResp.bits.rdata2.expect(0xDEADBEEF.U)
    }
  }

  // ============================================================================
  // 3. 读写冲突测试
  // ============================================================================

  it should "正确处理读写冲突" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 初始化寄存器 10 为 0xAAAA
      setWrite(dut, rd = 10, data = 0xAAAAL)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 读取寄存器 10（应该返回 0xAAAA）
      setReadReq(dut, raddr1 = 10)
      dut.io.readResp.bits.rdata1.expect(0xAAAA.U)
      dut.clock.step()

      // 在下一周期写入寄存器 10
      setDefaultInputs(dut)
      setWrite(dut, rd = 10, data = 0xBBBBL)
      dut.clock.step()

      // 再次读取寄存器 10（应该返回新值 0xBBBB）
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 10)
      dut.io.readResp.bits.rdata1.expect(0xBBBB.U)
    }
  }

  it should "在同一周期内进行读写操作" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 初始化寄存器 15 为 0x1111
      setWrite(dut, rd = 15, data = 0x1111L)
      dut.clock.step()

      // 在同一周期内读取寄存器 15 并写入寄存器 20
      setReadReq(dut, raddr1 = 15)
      setWrite(dut, rd = 20, data = 0x2222L)
      dut.io.readResp.bits.rdata1.expect(0x1111.U) // 读操作返回旧值
      dut.clock.step()

      // 验证寄存器 20 的值
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 20)
      dut.io.readResp.bits.rdata1.expect(0x2222.U)
    }
  }

  // ============================================================================
  // 4. 握手协议测试
  // ============================================================================

  it should "正确处理 Decoupled 握手协议" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 测试写接口握手
      setWrite(dut, rd = 30, data = 0x12345678L)
      dut.clock.step()

      // 测试读接口握手
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 30)

      // 验证 ready 信号
      dut.io.readReq.ready.expect(true.B)
      dut.clock.step()

      // 验证 valid 信号和数据
      dut.io.readResp.valid.expect(true.B)
      dut.io.readResp.bits.rdata1.expect(0x12345678.U)
    }
  }

  it should "正确处理读请求的 valid 信号" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入寄存器 25
      setWrite(dut, rd = 25, data = 0x55555555L)
      dut.clock.step()

      // 设置 readReq.valid = false
      setDefaultInputs(dut)
      dut.io.readReq.valid.poke(false.B)
      dut.clock.step()

      // 验证 readResp.valid 应该为 false
      dut.io.readResp.valid.expect(false.B)

      // 设置 readReq.valid = true
      setReadReq(dut, raddr1 = 25)
      dut.io.readResp.valid.expect(true.B)
      dut.io.readResp.bits.rdata1.expect(0x55555555.U)
    }
  }

  // ============================================================================
  // 5. 边界条件测试
  // ============================================================================

  it should "正确处理最大寄存器编号" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入最大寄存器 127
      setWrite(dut, rd = 127, data = 0x99999999L)
      dut.clock.step()

      // 读取最大寄存器 127
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 127)
      dut.io.readResp.bits.rdata1.expect(0x99999999.U)
    }
  }

  it should "正确处理 32 位数据边界" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入 0x00000000
      setWrite(dut, rd = 50, data = 0x00000000L)
      dut.clock.step()

      setReadReq(dut, raddr1 = 50)
      dut.io.readResp.bits.rdata1.expect(0x00000000.U)
      dut.clock.step()

      // 写入 0xFFFFFFFF
      setWrite(dut, rd = 51, data = 0xFFFFFFFFL)
      dut.clock.step()

      setReadReq(dut, raddr1 = 51)
      dut.io.readResp.bits.rdata1.expect(0xFFFFFFFF.U)
      dut.clock.step()

      // 写入 0x80000000
      setWrite(dut, rd = 52, data = 0x80000000L)
      dut.clock.step()

      setReadReq(dut, raddr1 = 52)
      dut.io.readResp.bits.rdata1.expect(0x80000000.U)
    }
  }

  // ============================================================================
  // 6. 综合场景测试
  // ============================================================================

  it should "正确处理连续读写多个寄存器" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 连续写入多个寄存器
      for (i <- 0 until 10) {
        setWrite(dut, rd = i, data = (i * 0x11111111L))
        dut.clock.step()
      }

      // 停止写操作
      setDefaultInputs(dut)

      // 连续读取多个寄存器
      for (i <- 0 until 10) {
        setReadReq(dut, raddr1 = i)
        dut.io.readResp.bits.rdata1.expect((i * 0x11111111).U)
        dut.clock.step()
      }
    }
  }

  it should "正确处理读写交替操作" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 写入寄存器 1
      setWrite(dut, rd = 1, data = 0x11111111L)
      dut.clock.step()

      // 读取寄存器 1
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 1)
      dut.io.readResp.bits.rdata1.expect(0x11111111.U)
      dut.clock.step()

      // 写入寄存器 2
      setDefaultInputs(dut)
      setWrite(dut, rd = 2, data = 0x22222222L)
      dut.clock.step()

      // 读取寄存器 2
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 2)
      dut.io.readResp.bits.rdata1.expect(0x22222222.U)
      dut.clock.step()

      // 再次写入寄存器 1
      setDefaultInputs(dut)
      setWrite(dut, rd = 1, data = 0x33333333L)
      dut.clock.step()

      // 读取寄存器 1（应该返回新值）
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 1)
      dut.io.readResp.bits.rdata1.expect(0x33333333.U)
    }
  }

  it should "正确处理同时读写不同寄存器" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 初始化多个寄存器
      setWrite(dut, rd = 10, data = 0xAAAAAAAAL)
      dut.clock.step()

      setWrite(dut, rd = 11, data = 0xBBBBBBBBL)
      dut.clock.step()

      setWrite(dut, rd = 12, data = 0xCCCCCCCCL)
      dut.clock.step()

      // 同时读取寄存器 10 和 11，并写入寄存器 12
      setReadReq(dut, raddr1 = 10, raddr2 = 11)
      setWrite(dut, rd = 12, data = 0xDDDDDDDDL)
      dut.io.readResp.bits.rdata1.expect(0xAAAAAAAA.U)
      dut.io.readResp.bits.rdata2.expect(0xBBBBBBBB.U)
      dut.clock.step()

      // 验证寄存器 12 的值已更新
      setDefaultInputs(dut)
      setReadReq(dut, raddr1 = 12)
      dut.io.readResp.bits.rdata1.expect(0xDDDDDDDD.U)
    }
  }

  it should "正确处理 x0 写入被忽略的情况" in {
    test(new PRF) { dut =>
      setDefaultInputs(dut)

      // 先写入寄存器 20
      setWrite(dut, rd = 20, data = 0xDEADBEEFL)
      dut.clock.step()

      // 尝试写入 x0（应该被忽略）
      setWrite(dut, rd = 0, data = 0xFFFFFFFFL)
      dut.clock.step()

      // 停止写操作
      setDefaultInputs(dut)

      // 读取 x0（应该仍然是 0）
      setReadReq(dut, raddr1 = 0)
      dut.io.readResp.bits.rdata1.expect(0.U)

      // 读取寄存器 20（应该不受影响）
      setReadReq(dut, raddr1 = 20)
      dut.io.readResp.bits.rdata1.expect(0xDEADBEEF.U)
    }
  }
}
