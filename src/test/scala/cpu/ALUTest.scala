package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ALUTest extends AnyFlatSpec with ChiselScalatestTester {

  // 辅助函数：设置默认输入信号
  def setDefaultInputs(dut: ALU): Unit = {
    dut.io.globalFlush.poke(false.B)
    dut.io.branchFlush.poke(false.B)
    dut.io.branchOH.poke(0.U)
    dut.io.out.ready.poke(true.B)
  }

  // 辅助函数：设置 ALU 请求包
  def setAluPacket(
      dut: ALU,
      aluOp: ALUOp.Type,
      robId: Int = 0,
      phyRd: Int = 0,
      branchMask: Int = 0,
      src1Sel: Src1Sel.Type = Src1Sel.REG,
      src2Sel: Src2Sel.Type = Src2Sel.REG,
      imm: Long = 0,
      pc: Long = 0,
      rdata1: Long = 0,
      rdata2: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.aluReq.aluOp.poke(aluOp)
    dut.io.in.bits.aluReq.meta.robId.poke(robId.U)
    dut.io.in.bits.aluReq.meta.phyRd.poke(phyRd.U)
    dut.io.in.bits.aluReq.meta.branchMask.poke(branchMask.U)
    dut.io.in.bits.aluReq.data.src1Sel.poke(src1Sel)
    dut.io.in.bits.aluReq.data.src2Sel.poke(src2Sel)
    dut.io.in.bits.aluReq.data.imm.poke((imm & mask).U)
    dut.io.in.bits.aluReq.data.pc.poke((pc & mask).U)
    dut.io.in.bits.prfData.rdata1.poke((rdata1 & mask).U)
    dut.io.in.bits.prfData.rdata2.poke((rdata2 & mask).U)
  }

  // 辅助函数：执行单条指令并验证结果
  def executeAndVerify(
      dut: ALU,
      aluOp: ALUOp.Type,
      src1Sel: Src1Sel.Type = Src1Sel.REG,
      src2Sel: Src2Sel.Type = Src2Sel.REG,
      imm: Long = 0,
      pc: Long = 0,
      rdata1: Long = 0,
      rdata2: Long = 0,
      expectedResult: Long = 0,
      robId: Int = 0,
      phyRd: Int = 0,
      branchMask: Int = 0
  ): Unit = {
    setDefaultInputs(dut)
    setAluPacket(
      dut,
      aluOp,
      robId,
      phyRd,
      branchMask,
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
    dut.io.in.ready.expect(true.B)
    dut.io.out.valid.expect(true.B)
    dut.io.out.bits.robId.expect(robId.U)
    dut.io.out.bits.phyRd.expect(phyRd.U)
    dut.io.out.bits.data.expect(expectedResult.U)
    dut.io.out.bits.hasSideEffect.expect(0.U)
    dut.io.out.bits.exception.valid.expect(false.B)
    dut.clock.step()

    dut.io.in.ready.expect(true.B)
    dut.io.out.valid.expect(false.B)
  }

  // ============================================================================
  // 1. 操作正确性测试
  // ============================================================================

  "ALU" should "正确执行 ADD 操作（正常加法）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        rdata1 = 100,
        rdata2 = 200,
        expectedResult = 300
      )
    }
  }

  it should "正确执行 ADD 操作（溢出情况：0x7FFFFFFF + 1）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        rdata1 = 0x7fffffffL,
        rdata2 = 1,
        expectedResult = 0x80000000L
      )
    }
  }

  it should "正确执行 ADD 操作（负数加法）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        rdata1 = 0xffffffffL,
        rdata2 = 0xffffffffL,
        expectedResult = 0xfffffffeL
      )
    }
  }

  it should "正确执行 SUB 操作（正常减法）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SUB,
        rdata1 = 200,
        rdata2 = 100,
        expectedResult = 100
      )
    }
  }

  it should "正确执行 SUB 操作（下溢情况：0 - 1）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SUB,
        rdata1 = 0,
        rdata2 = 1,
        expectedResult = 0xffffffffL
      )
    }
  }

  it should "正确执行 SUB 操作（负数减法）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SUB,
        rdata1 = 0xffffff9cL,
        rdata2 = 50,
        expectedResult = 0xffffff6aL
      )
    }
  }

  it should "正确执行 AND 操作（全0）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.AND,
        rdata1 = 0,
        rdata2 = 0xffffffffL,
        expectedResult = 0
      )
    }
  }

  it should "正确执行 AND 操作（全1）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.AND,
        rdata1 = 0xffffffffL,
        rdata2 = 0xffffffffL,
        expectedResult = 0xffffffffL
      )
    }
  }

  it should "正确执行 AND 操作（混合模式）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.AND,
        rdata1 = 0xaaaaaaaa,
        rdata2 = 0x55555555,
        expectedResult = 0
      )
    }
  }

  it should "正确执行 OR 操作（全0）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.OR,
        rdata1 = 0,
        rdata2 = 0,
        expectedResult = 0
      )
    }
  }

  it should "正确执行 OR 操作（全1）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.OR,
        rdata1 = 0xffffffffL,
        rdata2 = 0,
        expectedResult = 0xffffffffL
      )
    }
  }

  it should "正确执行 OR 操作（混合模式）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.OR,
        rdata1 = 0xaaaaaaaa,
        rdata2 = 0x55555555,
        expectedResult = 0xffffffffL
      )
    }
  }

  it should "正确执行 XOR 操作（相同值结果为0）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.XOR,
        rdata1 = 0x12345678,
        rdata2 = 0x12345678,
        expectedResult = 0
      )
    }
  }

  it should "正确执行 XOR 操作（不同值）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.XOR,
        rdata1 = 0xffffffffL,
        rdata2 = 0,
        expectedResult = 0xffffffffL
      )
    }
  }

  it should "正确执行 SLL 操作（移位量为0）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLL,
        rdata1 = 0x12345678,
        rdata2 = 0,
        expectedResult = 0x12345678
      )
    }
  }

  it should "正确执行 SLL 操作（移位量为31）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLL,
        rdata1 = 1,
        rdata2 = 31,
        expectedResult = 0x80000000L
      )
    }
  }

  it should "正确执行 SLL 操作（移位量为32及以上，应取低5位）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLL,
        rdata1 = 1,
        rdata2 = 32,
        expectedResult = 1
      )
      executeAndVerify(
        dut,
        ALUOp.SLL,
        rdata1 = 1,
        rdata2 = 33,
        expectedResult = 2
      )
      executeAndVerify(
        dut,
        ALUOp.SLL,
        rdata1 = 1,
        rdata2 = 63,
        expectedResult = 0x80000000
      )
    }
  }

  it should "正确执行 SRL 操作（逻辑右移，高位补0）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SRL,
        rdata1 = 0xffffffffL,
        rdata2 = 4,
        expectedResult = 0x0fffffff
      )
    }
  }

  it should "正确执行 SRL 操作（右移31位）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SRL,
        rdata1 = 0x80000000L,
        rdata2 = 31,
        expectedResult = 1
      )
    }
  }

  it should "正确执行 SRA 操作（算术右移，高位补符号位）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SRA,
        rdata1 = 0xffffffffL,
        rdata2 = 4,
        expectedResult = 0xffffffffL
      )
    }
  }

  it should "正确执行 SRA 操作（正数算术右移）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SRA,
        rdata1 = 0x7fffffff,
        rdata2 = 4,
        expectedResult = 0x07ffffff
      )
    }
  }

  it should "正确执行 SLT 操作（有符号比较，正数比较）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLT,
        rdata1 = 100,
        rdata2 = 200,
        expectedResult = 1
      )
    }
  }

  it should "正确执行 SLT 操作（有符号比较，负数比较）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLT,
        rdata1 = 0xffffff38L,
        rdata2 = 0xffffff9cL,
        expectedResult = 1
      )
    }
  }

  it should "正确执行 SLT 操作（有符号比较，0比较）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLT,
        rdata1 = 0,
        rdata2 = 0,
        expectedResult = 0
      )
    }
  }

  it should "正确执行 SLT 操作（有符号比较，负数小于正数）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLT,
        rdata1 = 0xffffffffL,
        rdata2 = 0,
        expectedResult = 1
      )
    }
  }

  it should "正确执行 SLTU 操作（无符号比较，小数比较）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLTU,
        rdata1 = 100,
        rdata2 = 200,
        expectedResult = 1
      )
    }
  }

  it should "正确执行 SLTU 操作（无符号比较，大数比较）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLTU,
        rdata1 = 0x80000000L,
        rdata2 = 0x7fffffff,
        expectedResult = 0
      )
    }
  }

  it should "正确执行 SLTU 操作（无符号比较，0比较）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.SLTU,
        rdata1 = 0,
        rdata2 = 0,
        expectedResult = 0
      )
    }
  }

  it should "正确执行 NOP 操作（无操作）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.NOP,
        rdata1 = 100,
        rdata2 = 200,
        expectedResult = 0
      )
    }
  }

  // ============================================================================
  // 2. 操作数选择测试
  // ============================================================================

  it should "正确选择 Src1Sel.REG（使用 rdata1）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src1Sel = Src1Sel.REG,
        rdata1 = 100,
        rdata2 = 200,
        expectedResult = 300
      )
    }
  }

  it should "正确选择 Src1Sel.PC（使用 pc）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src1Sel = Src1Sel.PC,
        pc = 0x80000000L,
        rdata2 = 4,
        expectedResult = 0x80000004L
      )
    }
  }

  it should "正确选择 Src1Sel.ZERO（使用 0）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src1Sel = Src1Sel.ZERO,
        rdata1 = 100, // 应该被忽略
        rdata2 = 200,
        expectedResult = 200
      )
    }
  }

  it should "正确选择 Src2Sel.REG（使用 rdata2）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src2Sel = Src2Sel.REG,
        rdata1 = 100,
        rdata2 = 200,
        expectedResult = 300
      )
    }
  }

  it should "正确选择 Src2Sel.IMM（使用 imm）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src2Sel = Src2Sel.IMM,
        imm = 100,
        rdata1 = 200,
        expectedResult = 300
      )
    }
  }

  it should "正确选择 Src2Sel.FOUR（使用 4）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src2Sel = Src2Sel.FOUR,
        rdata1 = 100,
        expectedResult = 104
      )
    }
  }

  it should "正确组合 Src1Sel.PC 和 Src2Sel.FOUR（AUIPC 模式）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src1Sel = Src1Sel.PC,
        src2Sel = Src2Sel.IMM,
        pc = 0x80000000L,
        imm = 0x12345000L,
        expectedResult = 0x912345000L
      )
    }
  }

  it should "正确组合 Src1Sel.ZERO 和 Src2Sel.IMM（LUI 模式）" in {
    test(new ALU) { dut =>
      executeAndVerify(
        dut,
        ALUOp.ADD,
        src1Sel = Src1Sel.ZERO,
        src2Sel = Src2Sel.IMM,
        imm = 0x12345000L,
        expectedResult = 0x12345000L
      )
    }
  }

  // ============================================================================
  // 3. 流水线控制测试
  // ============================================================================

  it should "正确处理 valid 和 ready 握手" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 初始状态：ALU 空闲，ready 为 true
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)

      // 发送请求
      setAluPacket(dut, ALUOp.ADD, rdata1 = 100, rdata2 = 200)
      dut.clock.step()

      // ALU 忙碌，ready 为 false
      dut.io.out.ready.poke(false.B)
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()

      // ALU 再次空闲
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.clock.step()

      // ALU 再次空闲
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理连续指令处理" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 第一条指令
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 0,
        rdata1 = 100,
        rdata2 = 200
      )
      dut.clock.step()

      // 第二条指令（此时 ALU 忙碌）
      setAluPacket(
        dut,
        ALUOp.SUB,
        robId = 1,
        phyRd = 1,
        rdata1 = 200,
        rdata2 = 100
      )
      // 第一条指令结果输出
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.robId.expect(0.U)
      dut.io.out.bits.data.expect(300.U)
      dut.clock.step()

      dut.io.in.valid.poke(false.B) // 第二条指令已经在上一个周期被接受
      // 第二条指令结果输出
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.robId.expect(1.U)
      dut.io.out.bits.data.expect(100.U)
      dut.clock.step()

      // ALU 空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理 globalFlush 信号" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 发送请求
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 0,
        rdata1 = 100,
        rdata2 = 200
      )
      dut.io.out.ready.poke(false.B)
      dut.clock.step()

      // ALU 忙碌
      dut.io.in.valid.poke(false.B)
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)

      // 触发 globalFlush
      dut.io.globalFlush.poke(true.B)
      dut.clock.step()

      // 输出应该无效
      dut.io.out.ready.poke(true.B)
      dut.io.globalFlush.poke(false.B)
      dut.io.out.valid.expect(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()

      // ALU 应该可以接收新指令
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "正确处理 globalFlush 信号（在空闲时）" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // ALU 空闲时触发 flush
      dut.io.globalFlush.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()

      // 取消 flush
      dut.io.globalFlush.poke(false.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "正确处理 branchFlush + branchOH 信号" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 发送请求，设置 branchMask
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 0,
        branchMask = 5,
        rdata1 = 100,
        rdata2 = 200
      )
      dut.clock.step()

      // ALU 忙碌
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)

      // 触发 branchFlush，branchOH 匹配
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(5.U)
      dut.clock.step()

      // 输出应该无效
      dut.io.out.valid.expect(false.B)
      dut.io.in.ready.expect(true.B)

      // 取消 flush
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(0)
      dut.clock.step()

      // ALU 应该可以接收新指令
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "正确处理 branchFlush + branchOH 信号（不匹配）" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 发送请求，设置 branchMask
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 0,
        branchMask = 5,
        rdata1 = 100,
        rdata2 = 200
      )
      dut.clock.step()

      // ALU 忙碌
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)

      // 触发 branchFlush，branchOH 不匹配
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(10.U)
      dut.clock.step()

      // 输出应该仍然有效（因为 branchOH 不匹配）
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(300.U)
      dut.clock.step()

      // 取消 flush
      dut.io.branchFlush.poke(false.B)
      dut.io.branchOH.poke(0)
      dut.clock.step()

      // ALU 应该可以接收新指令
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "正确处理 branchFlush（无 branchOH 匹配）" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 发送请求，设置 branchMask
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 0,
        branchMask = 1,
        rdata1 = 100,
        rdata2 = 200
      )
      dut.clock.step()

      // ALU 忙碌
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)

      // 触发 branchFlush，branchOH 匹配
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(1.U)
      dut.clock.step()

      // 输出应该无效
      dut.io.out.valid.expect(false.B)
      dut.io.in.ready.expect(true.B)

      // 发送新请求
      setAluPacket(
        dut,
        ALUOp.SUB,
        robId = 1,
        phyRd = 1,
        branchMask = 2,
        rdata1 = 200,
        rdata2 = 100
      )
      dut.clock.step()

      // 再次触发 branchFlush，branchOH 匹配
      dut.io.branchFlush.poke(true.B)
      dut.io.branchOH.poke(2.U)
      dut.clock.step()

      // 输出应该无效
      dut.io.out.valid.expect(false.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "正确处理输出 ready 为 false 的情况" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 发送请求
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 0,
        rdata1 = 100,
        rdata2 = 200
      )
      dut.clock.step()

      // ALU 忙碌，输出有效
      dut.io.in.ready.expect(false.B)
      dut.io.out.valid.expect(true.B)

      // 设置输出 ready 为 false
      dut.io.out.ready.poke(false.B)
      dut.clock.step()

      // 输出应该仍然有效，但不会 fire
      dut.io.out.valid.expect(true.B)
      dut.io.out.fire.expect(false.B)
      dut.io.in.ready.expect(false.B)

      // 设置输出 ready 为 true
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      // 输出 fire
      dut.io.out.fire.expect(true.B)
      dut.clock.step()

      // ALU 空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理输入 valid 为 false 的情况" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 输入 valid 为 false
      dut.io.in.valid.poke(false.B)
      dut.clock.step()

      // ALU 空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)

      // 多个周期保持空闲
      dut.clock.step(5)

      // ALU 仍然空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  // ============================================================================
  // 4. 综合测试
  // ============================================================================

  it should "正确执行完整的指令序列" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 指令1: ADD x1, x2, x3
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 1,
        rdata1 = 100,
        rdata2 = 200
      )
      dut.clock.step()

      // 指令2: SUB x4, x5, x6
      setAluPacket(
        dut,
        ALUOp.SUB,
        robId = 1,
        phyRd = 4,
        rdata1 = 500,
        rdata2 = 300
      )
      dut.clock.step()

      // 验证指令1结果
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.robId.expect(0.U)
      dut.io.out.bits.phyRd.expect(1.U)
      dut.io.out.bits.data.expect(300.U)
      dut.clock.step()

      // 验证指令2结果
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.robId.expect(1.U)
      dut.io.out.bits.phyRd.expect(4.U)
      dut.io.out.bits.data.expect(200.U)
      dut.clock.step()

      // ALU 空闲
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "正确处理混合操作数选择的指令序列" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      // 指令1: ADDI x1, x2, 100 (src1=REG, src2=IMM)
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 0,
        phyRd = 1,
        src1Sel = Src1Sel.REG,
        src2Sel = Src2Sel.IMM,
        imm = 100,
        rdata1 = 200
      )
      dut.clock.step()

      // 指令2: LUI x2, 0x12345 (src1=ZERO, src2=IMM)
      setAluPacket(
        dut,
        ALUOp.ADD,
        robId = 1,
        phyRd = 2,
        src1Sel = Src1Sel.ZERO,
        src2Sel = Src2Sel.IMM,
        imm = 0x12345000L
      )
      dut.clock.step()

      // 验证指令1结果
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(300.U)
      dut.clock.step()

      // 验证指令2结果
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(0x12345000L.U)
      dut.clock.step()
    }
  }

  it should "正确处理所有 ALU 操作" in {
    test(new ALU) { dut =>
      setDefaultInputs(dut)

      val testCases = Seq(
        (ALUOp.ADD, 100L, 200L, 300L),
        (ALUOp.SUB, 200L, 100L, 100L),
        (ALUOp.AND, 0xaaaaaaaaL, 0x55555555L, 0L),
        (ALUOp.OR, 0xaaaaaaaaL, 0x55555555L, 0xffffffffL),
        (ALUOp.XOR, 0x12345678L, 0x12345678L, 0L),
        (ALUOp.SLL, 1L, 4L, 16L),
        (ALUOp.SRL, 0xffffffffL, 4L, 0x0fffffffL),
        (ALUOp.SRA, 0xffffffffL, 4L, 0xffffffffL),
        (ALUOp.SLT, 100L, 200L, 1L),
        (ALUOp.SLTU, 100L, 200L, 1L),
        (ALUOp.NOP, 100L, 200L, 0L)
      )

      for (((op, r1, r2, expected), i) <- testCases.zipWithIndex) {
        setAluPacket(dut, op, robId = i, phyRd = i, rdata1 = r1, rdata2 = r2)
        dut.clock.step()
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.data.expect(expected.U)
        dut.clock.step()
      }
    }
  }
}
