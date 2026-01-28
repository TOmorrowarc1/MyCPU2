package cpu

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class FetcherTest extends AnyFlatSpec with ChiselScalatestTester {
  "Fetcher" should "正常取指，nextPC 更新为 PC+4" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 初始 PC 应该为 0x8000_0000
      dut.io.icache.valid.expect(true.B)
      dut.io.icache.bits.pc.expect(0x80000000L.U)
      dut.io.icache.bits.prediction.taken.expect(false.B)
      dut.io.icache.bits.prediction.targetPC.expect(0x80000004L.U)
      dut.io.icache.bits.exception.valid.expect(false.B)
      
      // 模拟 Icache 接收请求（ready 为高）
      dut.io.icache.ready.poke(true.B)
      dut.clock.step()
      
      // 握手成功后，nextPC 应该更新为 0x8000_0004
      dut.io.icache.bits.pc.expect(0x80000004L.U)
      dut.io.icache.bits.prediction.targetPC.expect(0x80000008L.U)
    }
  }
  
    it should "响应全局冲刷信号" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 正常取指
      dut.io.globalFlush.poke(false.B)
      dut.io.icache.ready.poke(true.B)
      dut.clock.step()
      
      // 发送全局冲刷信号，目标 PC 为 0x8000_2000
      dut.io.globalFlush.poke(true.B)
      dut.io.globalFlushPC.poke(0x80002000L.U)
      
      // PC 应该跳转到 0x8000_2000（优先级高于分支冲刷）
      dut.io.icache.bits.pc.expect(0x80002000L.U)
      dut.io.icache.bits.prediction.targetPC.expect(0x80002004L.U)
      
      // 握手成功
      dut.clock.step()
      
      // nextPC 应该更新为 0x8000_2004
      dut.io.globalFlush.poke(false.B)
      dut.io.icache.bits.pc.expect(0x80002004L.U)

      dut.clock.step()

      dut.io.globalFlush.poke(true.B)
      dut.io.globalFlushPC.poke(0x80003000L.U)
      dut.io.branchFlush.poke(true.B)
      dut.io.branchFlushPC.poke(0x80004000L.U)
      dut.io.icache.bits.pc.expect(0x80003000L.U)

      dut.clock.step()
      dut.io.globalFlush.poke(false.B)
      dut.io.branchFlush.poke(false.B)
      dut.io.icache.bits.pc.expect(0x80003004L.U)
    }
  }

  it should "响应分支冲刷信号" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 正常取指
      dut.io.branchFlush.poke(false.B)
      dut.io.icache.ready.poke(true.B)
      dut.clock.step()
      
      // 发送分支冲刷信号，目标 PC 为 0x8000_1000
      dut.io.branchFlush.poke(true.B)
      dut.io.branchFlushPC.poke(0x80001000L.U)
      
      // PC 应该跳转到 0x8000_1000
      dut.io.icache.bits.pc.expect(0x80001000L.U)
      dut.io.icache.bits.prediction.targetPC.expect(0x80001004L.U)
      
      // 握手成功
      dut.clock.step()
      
      // nextPC 应该更新为 0x8000_1004
      dut.io.branchFlush.poke(false.B)
      dut.io.icache.bits.pc.expect(0x80001004L.U)
    }
  }
  
  it should "响应取指暂停信号" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.privMode.poke(PrivMode.M)
      
      // 正常取指
      dut.io.ifStall.poke(false.B)
      dut.io.icache.ready.poke(true.B)
      dut.io.icache.bits.pc.expect(0x80000000L.U)
      
      dut.clock.step()
      
      // 发送取指暂停信号
      dut.io.ifStall.poke(true.B)
      
      // valid 应该为低（不发起取指请求）
      dut.io.icache.valid.expect(false.B)
      
      // 握手失败，nextPC 维持当前值
      dut.clock.step()
      dut.io.icache.bits.pc.expect(0x80000004L.U)
    }
  }
  
  it should "在 Flush 时无视 ifStall" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.privMode.poke(PrivMode.M)
      
      // 正常取指
      dut.io.ifStall.poke(false.B)
      dut.io.icache.ready.poke(true.B)
      dut.clock.step()
      
      // 同时发送 ifStall 和 branchFlush
      dut.io.ifStall.poke(true.B)
      dut.io.branchFlush.poke(true.B)
      dut.io.branchFlushPC.poke(0x80003000L.U)
      
      // valid 应该为高（无视 ifStall）
      dut.io.icache.valid.expect(true.B)
      
      // PC 应该跳转到 0x8000_3000
      dut.io.icache.bits.pc.expect(0x80003000L.U)

      dut.clock.step()

      dut.io.ifStall.poke(false.B)
      dut.io.branchFlush.poke(false.B)
      dut.io.icache.bits.pc.expect(0x80003004L.U)
    }
  }
  
  it should "检测指令地址未对齐异常" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 发送分支冲刷到未对齐地址（0x8000_0001）
      dut.io.branchFlush.poke(true.B)
      dut.io.branchFlushPC.poke(0x80000001L.U)
      
      // 应该检测到异常
      dut.io.icache.bits.exception.valid.expect(true.B)
      dut.io.icache.bits.exception.cause.expect(ExceptionCause.INSTRUCTION_ADDRESS_MISALIGNED)
      dut.io.icache.bits.exception.tval.expect(0x80000001L.U)
    }
  }
  
  it should "检测指令地址未对齐异常（低2位为2）" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 发送分支冲刷到未对齐地址（0x8000_0002）
      dut.io.branchFlush.poke(true.B)
      dut.io.branchFlushPC.poke(0x80000002L.U)
      
      // 应该检测到异常
      dut.io.icache.bits.exception.valid.expect(true.B)
      dut.io.icache.bits.exception.cause.expect(ExceptionCause.INSTRUCTION_ADDRESS_MISALIGNED)
      dut.io.icache.bits.exception.tval.expect(0x80000002L.U)
    }
  }
  
  it should "不检测对齐地址的异常" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 正常取指，地址对齐（0x8000_0000）
      dut.io.icache.ready.poke(true.B)
      
      // 不应该检测到异常
      dut.io.icache.bits.exception.valid.expect(false.B)
    }
  }
  
  it should "正确传递特权模式" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      
      // 测试 User 模式
      dut.io.privMode.poke(PrivMode.U)
      dut.io.icache.bits.privMode.expect(PrivMode.U)
      
      // 测试 Supervisor 模式
      dut.io.privMode.poke(PrivMode.S)
      dut.io.icache.bits.privMode.expect(PrivMode.S)
      
      // 测试 Machine 模式
      dut.io.privMode.poke(PrivMode.M)
      dut.io.icache.bits.privMode.expect(PrivMode.M)
    }
  }
  
  it should "正确传递指令纪元" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 测试纪元 0
      dut.io.insEpoch.poke(0.U)
      dut.io.icache.bits.instEpoch.expect(0.U)
      
      // 测试纪元 1
      dut.io.insEpoch.poke(1.U)
      dut.io.icache.bits.instEpoch.expect(1.U)
      
      // 测试纪元 2
      dut.io.insEpoch.poke(2.U)
      dut.io.icache.bits.instEpoch.expect(2.U)
      
      // 测试纪元 3
      dut.io.insEpoch.poke(3.U)
      dut.io.icache.bits.instEpoch.expect(3.U)
    }
  }
  
  it should "握手失败时 nextPC 维持当前值" in {
    test(new Fetcher) { dut =>
      // 初始化输入信号
      dut.io.insEpoch.poke(0.U)
      dut.io.globalFlush.poke(false.B)
      dut.io.globalFlushPC.poke(0.U)
      dut.io.branchFlush.poke(false.B)
      dut.io.branchFlushPC.poke(0.U)
      dut.io.ifStall.poke(false.B)
      dut.io.privMode.poke(PrivMode.M)
      
      // 正常取指
      dut.io.icache.ready.poke(true.B)
      dut.clock.step()
      
      // 握手失败（ready 为低）
      dut.io.icache.ready.poke(false.B)
      dut.clock.step()
      
      // nextPC 应该维持当前值（0x8000_0004）
      dut.io.icache.bits.pc.expect(0x80000004L.U)
      
      // 再次握手失败
      dut.clock.step()
      
      // nextPC 仍然维持当前值
      dut.io.icache.bits.pc.expect(0x80000004L.U)
    }
  }
}
