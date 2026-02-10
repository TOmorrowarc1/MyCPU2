package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class CDBTest extends AnyFlatSpec with ChiselScalatestTester {

  // 辅助函数：设置默认输入信号
  def setDefaultInputs(dut: CDB): Unit = {
    dut.io.alu.valid.poke(false.B)
    dut.io.bru.valid.poke(false.B)
    dut.io.lsu.valid.poke(false.B)
    dut.io.zicsru.valid.poke(false.B)
    dut.io.boardcast.ready.poke(true.B)
  }

  // 辅助函数：设置 CDBMessage
  def setCDBMessage(
      dut: CDB,
      port: String, // "alu", "bru", "lsu", "zicsru"
      robId: Int = 0,
      phyRd: Int = 0,
      data: Long = 0,
      hasSideEffect: Int = 0,
      exceptionValid: Boolean = false,
      exceptionCause: Int = 0,
      exceptionTval: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    val portIO = port match {
      case "alu"   => dut.io.alu
      case "bru"   => dut.io.bru
      case "lsu"   => dut.io.lsu
      case "zicsru" => dut.io.zicsru
    }
    portIO.valid.poke(true.B)
    portIO.bits.robId.poke(robId.U)
    portIO.bits.phyRd.poke(phyRd.U)
    portIO.bits.data.poke((data & mask).U)
    portIO.bits.hasSideEffect.poke(hasSideEffect.U)
    portIO.bits.exception.valid.poke(exceptionValid.B)
    portIO.bits.exception.cause.poke(exceptionCause.U)
    portIO.bits.exception.tval.poke((exceptionTval & mask).U)
  }

  // 辅助函数：验证 CDBMessage
  def verifyCDBMessage(
      dut: CDB,
      expectedRobId: Int = 0,
      expectedPhyRd: Int = 0,
      expectedData: Long = 0,
      expectedHasSideEffect: Int = 0,
      expectedExceptionValid: Boolean = false,
      expectedExceptionCause: Int = 0,
      expectedExceptionTval: Long = 0
  ): Unit = {
    val mask = 0xffffffffL
    dut.io.boardcast.valid.expect(true.B)
    dut.io.boardcast.bits.robId.expect(expectedRobId.U)
    dut.io.boardcast.bits.phyRd.expect(expectedPhyRd.U)
    dut.io.boardcast.bits.data.expect((expectedData & mask).U)
    dut.io.boardcast.bits.hasSideEffect.expect(expectedHasSideEffect.U)
    dut.io.boardcast.bits.exception.valid.expect(expectedExceptionValid.B)
    dut.io.boardcast.bits.exception.cause.expect(expectedExceptionCause.U)
    dut.io.boardcast.bits.exception.tval.expect((expectedExceptionTval & mask).U)
  }

  // ============================================================================
  // 1. 基本功能测试
  // ============================================================================

  "CDB" should "正确广播 ALU 的输出" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送 ALU 消息
      setCDBMessage(dut, "alu", robId = 1, phyRd = 5, data = 0x12345678L)
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 5, expectedData = 0x12345678L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确广播 BRU 的输出" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送 BRU 消息
      setCDBMessage(dut, "bru", robId = 2, phyRd = 10, data = 0x80000004L)
      verifyCDBMessage(dut, expectedRobId = 2, expectedPhyRd = 10, expectedData = 0x80000004L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确广播 LSU 的输出" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送 LSU 消息
      setCDBMessage(dut, "lsu", robId = 3, phyRd = 15, data = 0xabcdef00L, hasSideEffect = 1)
      verifyCDBMessage(
        dut,
        expectedRobId = 3,
        expectedPhyRd = 15,
        expectedData = 0xabcdef00L,
        expectedHasSideEffect = 1
      )
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确广播 ZICSRU 的输出" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送 ZICSRU 消息
      setCDBMessage(dut, "zicsru", robId = 4, phyRd = 20, data = 0xdeadbeefL)
      verifyCDBMessage(dut, expectedRobId = 4, expectedPhyRd = 20, expectedData = 0xdeadbeefL)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确广播包含异常信息的 CDBMessage" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送包含异常的 ALU 消息
      setCDBMessage(
        dut,
        "alu",
        robId = 5,
        phyRd = 25,
        data = 0,
        exceptionValid = true,
        exceptionCause = 2, // ILLEGAL_INSTRUCTION
        exceptionTval = 0x80000000L
      )
      verifyCDBMessage(
        dut,
        expectedRobId = 5,
        expectedPhyRd = 25,
        expectedData = 0,
        expectedExceptionValid = true,
        expectedExceptionCause = 2,
        expectedExceptionTval = 0x80000000L
      )
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  // ============================================================================
  // 2. 仲裁优先级测试
  // ============================================================================

  it should "正确处理 ZICSRU 和 ALU 同时有效（ZICSRU 优先）" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 同时发送 ZICSRU 和 ALU 消息
      setCDBMessage(dut, "zicsru", robId = 1, phyRd = 1, data = 0x11111111L)
      setCDBMessage(dut, "alu", robId = 2, phyRd = 2, data = 0x22222222L)
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 1, expectedData = 0x11111111L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理 LSU 和 BRU 同时有效（LSU 优先）" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 同时发送 LSU 和 BRU 消息
      setCDBMessage(dut, "lsu", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "bru", robId = 4, phyRd = 4, data = 0x44444444L)
      verifyCDBMessage(dut, expectedRobId = 3, expectedPhyRd = 3, expectedData = 0x33333333L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理 BRU 和 ALU 同时有效（BRU 优先）" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 同时发送 BRU 和 ALU 消息
      setCDBMessage(dut, "bru", robId = 5, phyRd = 5, data = 0x55555555L)
      setCDBMessage(dut, "alu", robId = 6, phyRd = 6, data = 0x66666666L)
      verifyCDBMessage(dut, expectedRobId = 5, expectedPhyRd = 5, expectedData = 0x55555555L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理所有四个输入同时有效（ZICSRU 优先）" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 同时发送所有四个消息
      setCDBMessage(dut, "zicsru", robId = 1, phyRd = 1, data = 0x11111111L)
      setCDBMessage(dut, "lsu", robId = 2, phyRd = 2, data = 0x22222222L)
      setCDBMessage(dut, "bru", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "alu", robId = 4, phyRd = 4, data = 0x44444444L)
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 1, expectedData = 0x11111111L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理连续仲裁（优先级顺序：ZICSRU > LSU > BRU > ALU）" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 第1轮：所有输入同时有效
      setCDBMessage(dut, "zicsru", robId = 1, phyRd = 1, data = 0x11111111L)
      setCDBMessage(dut, "lsu", robId = 2, phyRd = 2, data = 0x22222222L)
      setCDBMessage(dut, "bru", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "alu", robId = 4, phyRd = 4, data = 0x44444444L)
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 1, expectedData = 0x11111111L)
      dut.clock.step()

      // 第2轮：ZICSRU 无效，其他仍然有效
      dut.io.zicsru.valid.poke(false.B)
      setCDBMessage(dut, "lsu", robId = 2, phyRd = 2, data = 0x22222222L)
      setCDBMessage(dut, "bru", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "alu", robId = 4, phyRd = 4, data = 0x44444444L)
      verifyCDBMessage(dut, expectedRobId = 2, expectedPhyRd = 2, expectedData = 0x22222222L)
      dut.clock.step()

      // 第3轮：ZICSRU 和 LSU 无效
      dut.io.lsu.valid.poke(false.B)
      setCDBMessage(dut, "bru", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "alu", robId = 4, phyRd = 4, data = 0x44444444L)
      verifyCDBMessage(dut, expectedRobId = 3, expectedPhyRd = 3, expectedData = 0x33333333L)
      dut.clock.step()

      // 第4轮：只有 ALU 有效
      dut.io.bru.valid.poke(false.B)
      setCDBMessage(dut, "alu", robId = 4, phyRd = 4, data = 0x44444444L)
      verifyCDBMessage(dut, expectedRobId = 4, expectedPhyRd = 4, expectedData = 0x44444444L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  // ============================================================================
  // 3. 反压信号测试
  // ============================================================================

  it should "正确处理输出 ready 为 false 的情况" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送 ALU 消息，但输出 ready 为 false
      setCDBMessage(dut, "alu", robId = 1, phyRd = 5, data = 0x12345678L)
      dut.io.boardcast.ready.poke(false.B)
      dut.clock.step()

      // 验证输出有效但不会 fire
      dut.io.boardcast.valid.expect(true.B)
      dut.io.alu.ready.expect(false.B) // ALU 的 ready 应该为 false
      dut.clock.step()

      // 设置输出 ready 为 true
      dut.io.boardcast.ready.poke(true.B)
      // 验证输出成功 fire
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 5, expectedData = 0x12345678L)
      dut.clock.step()

      // 验证输出无效
      dut.io.alu.valid.poke(false.B)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理多个输入同时有效且输出 ready 为 false" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 同时发送所有消息，但输出 ready 为 false
      setCDBMessage(dut, "zicsru", robId = 1, phyRd = 1, data = 0x11111111L)
      setCDBMessage(dut, "lsu", robId = 2, phyRd = 2, data = 0x22222222L)
      setCDBMessage(dut, "bru", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "alu", robId = 4, phyRd = 4, data = 0x44444444L)
      dut.io.boardcast.ready.poke(false.B)
      dut.clock.step()

      // 验证输出有效但不会 fire
      dut.io.boardcast.valid.expect(true.B)
      dut.io.zicsru.ready.expect(false.B) // ZICSRU 的 ready 应该为 false
      dut.io.lsu.ready.expect(false.B)   // LSU 的 ready 应该为 false
      dut.io.bru.ready.expect(false.B)   // BRU 的 ready 应该为 false
      dut.io.alu.ready.expect(false.B)   // ALU 的 ready 应该为 false
      dut.clock.step()

      // 设置输出 ready 为 true
      dut.io.boardcast.ready.poke(true.B)
      // 验证 ZICSRU 成功 fire
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 1, expectedData = 0x11111111L)
      dut.clock.step()

      // 验证其他路输出依旧有效
      dut.io.zicsru.valid.poke(false.B)
      dut.io.boardcast.valid.expect(true.B)
    }
  }

  it should "正确处理输入 ready 的反压传播" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 初始状态：所有输入的 ready 应该为 true
      dut.io.zicsru.ready.expect(true.B)
      dut.io.lsu.ready.expect(true.B)
      dut.io.bru.ready.expect(true.B)
      dut.io.alu.ready.expect(true.B)

      // 发送 ZICSRU 消息
      setCDBMessage(dut, "zicsru", robId = 1, phyRd = 1, data = 0x11111111L)
      dut.clock.step()

      // 验证输出有效
      dut.io.boardcast.valid.expect(true.B)

      // 此时只有 ZICSRU 的 ready 应该为 true（因为它的请求被选中）
      // 其他输入的 ready 应该为 false
      dut.io.zicsru.ready.expect(true.B)
      dut.io.lsu.ready.expect(false.B)
      dut.io.bru.ready.expect(false.B)
      dut.io.alu.ready.expect(false.B)

      // 发送 LSU 消息
      setCDBMessage(dut, "lsu", robId = 2, phyRd = 2, data = 0x22222222L)
      dut.clock.step()

      // 验证 LSU 的 ready 应该为 false（因为 ZICSRU 仍然在等待）
      dut.io.zicsru.ready.expect(true.B)
      dut.io.lsu.ready.expect(false.B)
      dut.io.bru.ready.expect(false.B)
      dut.io.alu.ready.expect(false.B)

      // 取消 ZICSRU 消息
      dut.io.zicsru.valid.poke(false.B)
      // 验证 LSU 被选中
      verifyCDBMessage(dut, expectedRobId = 2, expectedPhyRd = 2, expectedData = 0x22222222L)
      dut.clock.step()
    }
  }

  // ============================================================================
  // 4. 边界条件测试
  // ============================================================================

  it should "正确处理所有输入都无效的情况" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 所有输入都无效
      dut.io.alu.valid.poke(false.B)
      dut.io.bru.valid.poke(false.B)
      dut.io.lsu.valid.poke(false.B)
      dut.io.zicsru.valid.poke(false.B)
      dut.clock.step()

      // 验证输出无效
      dut.io.boardcast.valid.expect(false.B)

      // 多个周期保持无效
      dut.clock.step(5)

      // 验证输出仍然无效
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理数据边界值（0x00000000）" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送数据为 0 的消息
      setCDBMessage(dut, "alu", robId = 0, phyRd = 0, data = 0x00000000L)
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 0, expectedData = 0x00000000L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理数据边界值（0xFFFFFFFF）" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送数据为 0xFFFFFFFF 的消息
      setCDBMessage(dut, "alu", robId = 31, phyRd = 127, data = 0xFFFFFFFFL)
      verifyCDBMessage(dut, expectedRobId = 31, expectedPhyRd = 127, expectedData = 0xFFFFFFFFL)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理 robId 和 phyRd 的最大值" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // robId 最大值：31 (5 bits)
      // phyRd 最大值：127 (7 bits)
      setCDBMessage(dut, "zicsru", robId = 31, phyRd = 127, data = 0x12345678L)
      verifyCDBMessage(dut, expectedRobId = 31, expectedPhyRd = 127, expectedData = 0x12345678L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理 hasSideEffect 标志" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送 hasSideEffect = 1 的消息
      setCDBMessage(dut, "lsu", robId = 1, phyRd = 1, data = 0x12345678L, hasSideEffect = 1)
      verifyCDBMessage(
        dut,
        expectedRobId = 1,
        expectedPhyRd = 1,
        expectedData = 0x12345678L,
        expectedHasSideEffect = 1
      )
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理各种异常原因" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      val exceptionCauses = Seq(
        (0, 0x80000000L), // INSTRUCTION_ADDRESS_MISALIGNED
        (1, 0x80000004L), // INSTRUCTION_ACCESS_FAULT
        (2, 0x80000008L), // ILLEGAL_INSTRUCTION
        (3, 0x8000000CL), // BREAKPOINT
        (4, 0x80000010L), // LOAD_ADDRESS_MISALIGNED
        (5, 0x80000014L), // LOAD_ACCESS_FAULT
        (6, 0x80000018L), // STORE_ADDRESS_MISALIGNED
        (7, 0x8000001CL)  // STORE_ACCESS_FAULT
      )

      for ((cause, tval) <- exceptionCauses) {
        setCDBMessage(
          dut,
          "alu",
          robId = cause,
          phyRd = cause,
          data = 0,
          exceptionValid = true,
          exceptionCause = cause,
          exceptionTval = tval
        )
        verifyCDBMessage(
          dut,
          expectedRobId = cause,
          expectedPhyRd = cause,
          expectedData = 0,
          expectedExceptionValid = true,
          expectedExceptionCause = cause,
          expectedExceptionTval = tval
        )
        dut.clock.step()

        // 验证输出无效
        setDefaultInputs(dut)
        dut.io.boardcast.valid.expect(false.B)
      }
    }
  }

  it should "正确处理连续的消息流" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 发送连续的消息流
      val messages = Seq(
        ("alu", 1, 5, 0x11111111L),
        ("bru", 2, 10, 0x22222222L),
        ("lsu", 3, 15, 0x33333333L),
        ("zicsru", 4, 20, 0x44444444L),
        ("alu", 5, 25, 0x55555555L),
        ("bru", 6, 30, 0x66666666L),
        ("lsu", 7, 35, 0x77777777L),
        ("zicsru", 8, 40, 0x88888888L)
      )

      for ((port, robId, phyRd, data) <- messages) {
        setCDBMessage(dut, port, robId, phyRd, data)
        verifyCDBMessage(dut, expectedRobId = robId, expectedPhyRd = phyRd, expectedData = data)
        dut.clock.step()

        // 验证输出无效
        setDefaultInputs(dut)
        dut.io.boardcast.valid.expect(false.B)
      }
    }
  }

  it should "正确处理混合有效和无效输入的切换" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 第1轮：只有 ALU 有效
      setCDBMessage(dut, "alu", robId = 1, phyRd = 1, data = 0x11111111L)
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 1, expectedData = 0x11111111L)
      dut.clock.step()

      // 第2轮：所有输入有效
      setCDBMessage(dut, "zicsru", robId = 2, phyRd = 2, data = 0x22222222L)
      setCDBMessage(dut, "lsu", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "bru", robId = 4, phyRd = 4, data = 0x44444444L)
      setCDBMessage(dut, "alu", robId = 5, phyRd = 5, data = 0x55555555L)
      verifyCDBMessage(dut, expectedRobId = 2, expectedPhyRd = 2, expectedData = 0x22222222L)
      dut.clock.step()

      // 第3轮：只有 BRU 有效
      dut.io.zicsru.valid.poke(false.B)
      dut.io.lsu.valid.poke(false.B)
      dut.io.bru.valid.poke(true.B)
      dut.io.alu.valid.poke(false.B)
      setCDBMessage(dut, "bru", robId = 6, phyRd = 6, data = 0x66666666L)
      verifyCDBMessage(dut, expectedRobId = 6, expectedPhyRd = 6, expectedData = 0x66666666L)
      dut.clock.step()

      // 第4轮：所有输入无效
      dut.io.bru.valid.poke(false.B)
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)

      // 第5轮：只有 LSU 有效
      setCDBMessage(dut, "lsu", robId = 7, phyRd = 7, data = 0x77777777L)
      verifyCDBMessage(dut, expectedRobId = 7, expectedPhyRd = 7, expectedData = 0x77777777L)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  // ============================================================================
  // 5. 综合测试
  // ============================================================================

  it should "正确处理复杂的消息序列" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 场景：模拟真实的执行单元输出序列
      // 指令1: ADD x1, x2, x3 (ALU)
      setCDBMessage(dut, "alu", robId = 0, phyRd = 1, data = 0x12345678L)
      verifyCDBMessage(dut, expectedRobId = 0, expectedPhyRd = 1, expectedData = 0x12345678L)
      dut.clock.step()

      // 指令2: BEQ x4, x5, offset (BRU)
      setCDBMessage(dut, "bru", robId = 1, phyRd = 4, data = 0x80000004L)
      verifyCDBMessage(dut, expectedRobId = 1, expectedPhyRd = 4, expectedData = 0x80000004L)
      dut.clock.step()

      // 指令3: LW x6, 0(x7) (LSU)
      setCDBMessage(dut, "lsu", robId = 2, phyRd = 6, data = 0xabcdef00L, hasSideEffect = 1)
      verifyCDBMessage(
        dut,
        expectedRobId = 2,
        expectedPhyRd = 6,
        expectedData = 0xabcdef00L,
        expectedHasSideEffect = 1
      )
      dut.clock.step()

      // 指令4: CSRRW x8, mstatus (ZICSRU)
      setCDBMessage(dut, "zicsru", robId = 3, phyRd = 8, data = 0xdeadbeefL)
      verifyCDBMessage(dut, expectedRobId = 3, expectedPhyRd = 8, expectedData = 0xdeadbeefL)
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理同时到达的异常和正常消息" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 同时发送正常消息和异常消息
      setCDBMessage(
        dut,
        "zicsru",
        robId = 1,
        phyRd = 1,
        data = 0x11111111L,
        exceptionValid = true,
        exceptionCause = 2,
        exceptionTval = 0x80000000L
      )
      setCDBMessage(dut, "lsu", robId = 2, phyRd = 2, data = 0x22222222L)
      setCDBMessage(dut, "bru", robId = 3, phyRd = 3, data = 0x33333333L)
      setCDBMessage(dut, "alu", robId = 4, phyRd = 4, data = 0x44444444L)
      verifyCDBMessage(
        dut,
        expectedRobId = 1,
        expectedPhyRd = 1,
        expectedData = 0x11111111L,
        expectedExceptionValid = true,
        expectedExceptionCause = 2,
        expectedExceptionTval = 0x80000000L
      )
      dut.clock.step()

      // 验证输出无效
      setDefaultInputs(dut)
      dut.io.boardcast.valid.expect(false.B)
    }
  }

  it should "正确处理所有端口的消息序列" in {
    test(new CDB) { dut =>
      setDefaultInputs(dut)

      // 测试每个端口的消息都能正确广播
      val testCases = Seq(
        ("alu", 0, 0, 0x00000000L, 0, false, 0, 0L),
        ("bru", 1, 1, 0x11111111L, 0, false, 0, 0L),
        ("lsu", 2, 2, 0x22222222L, 1, false, 0, 0L),
        ("zicsru", 3, 3, 0x33333333L, 0, false, 0, 0L),
        ("alu", 4, 4, 0x44444444L, 0, true, 2, 0x80000000L),
        ("bru", 5, 5, 0x55555555L, 0, false, 0, 0L),
        ("lsu", 6, 6, 0x66666666L, 0, false, 0, 0L),
        ("zicsru", 7, 7, 0x77777777L, 0, false, 0, 0L)
      )

      for ((port, robId, phyRd, data, hasSideEffect, exceptionValid, exceptionCause, exceptionTval) <- testCases) {
        setCDBMessage(
          dut,
          port,
          robId,
          phyRd,
          data,
          hasSideEffect,
          exceptionValid,
          exceptionCause,
          exceptionTval
        )
        verifyCDBMessage(
          dut,
          expectedRobId = robId,
          expectedPhyRd = phyRd,
          expectedData = data,
          expectedHasSideEffect = hasSideEffect,
          expectedExceptionValid = exceptionValid,
          expectedExceptionCause = exceptionCause,
          expectedExceptionTval = exceptionTval
        )
        dut.clock.step()

        // 验证输出无效
        setDefaultInputs(dut)
        dut.io.boardcast.valid.expect(false.B)
      }
    }
  }
}
