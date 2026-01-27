package cpu

import chisel3._
import chisel3.util._

// ============================================================================
// BranchPrediction 模块（简化版本）
// ============================================================================
// 暂时设计为永远返回 PC+4 nottaken 的简单模块
// 后续可以扩展为更复杂的分支预测器
class BranchPrediction extends Module with CPUConfig {
  val io = IO(new Bundle {
    val pc = Input(AddrW)
    val predict = Output(new Prediction)
  })
  
  // 简单实现：永远不跳转，目标 PC 为 PC+4
  io.predict.taken := false.B
  io.predict.targetPC := io.pc + 4.U
}

// ============================================================================
// Fetcher 模块
// ============================================================================
// 负责从指令存储器中取指，并向 Icache 发送取指请求
class Fetcher extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 输入接口
    val insEpoch = Input(EpochW)                // 指令纪元
    val globalFlush = Input(Bool())             // 全局冲刷信号
    val globalFlushPC = Input(AddrW)            // 全局冲刷的目标 PC
    val branchFlush = Input(Bool())             // 分支冲刷信号
    val branchFlushPC = Input(AddrW)            // 分支冲刷的目标 PC
    val ifStall = Input(Bool())                 // 取指暂停信号
    val privMode = Input(PrivMode())            // 当前特权模式
    
    // 输出接口
    val icache = Decoupled(new IFetchPacket)    // 向 Icache 发送取指请求
  })
  
  // ============================================================================
  // 1. 内部状态
  // ============================================================================
  // nextPC 寄存器：初始值为 0x8000_0000（RISC-V 启动地址）
  val nextPC = RegInit(0x80000000L.U(AddrWidth.W))
  
  // 实例化分支预测器
  val branchPredictor = Module(new BranchPrediction)
  
  // ============================================================================
  // 2. PC 选择逻辑（优先级从高到低）
  // ============================================================================
  // 全局冲刷（globalFlush）> 分支冲刷（branchFlush）> 正常取指（nextPC）
  val currentPC = Mux(io.globalFlush,
    io.globalFlushPC,
    Mux(io.branchFlush,
      io.branchFlushPC,
      nextPC
    )
  )
  
  // ============================================================================
  // 3. 取指请求发起逻辑
  // ============================================================================
  // valid 信号由 ifStall 控制：
  // - 当 ifStall 为高时，valid 为低（不发起请求）
  // - 当 Flush 信号发生时，无视 ifStall（强制发起请求）
  val hasFlush = io.globalFlush || io.branchFlush
  val canFetch = !io.ifStall || hasFlush
  
  io.icache.valid := canFetch
  
  // ============================================================================
  // 4. 异常检测逻辑
  // ============================================================================
  // 检测指令地址未对齐异常（PC 的低 2 位不为 0）
  val misaligned = currentPC(1, 0) =/= 0.U
  
  // 构造异常信息
  val exception = Wire(new Exception)
  exception.valid := misaligned
  exception.cause := ExceptionCause.INSTRUCTION_ADDRESS_MISALIGNED
  exception.tval := currentPC
  
  // ============================================================================
  // 5. 分支预测
  // ============================================================================
  // 获取当前 PC 的预测结果
  branchPredictor.io.pc := currentPC
  val prediction = branchPredictor.io.predict
  
  // ============================================================================
  // 6. IFetchPacket 打包
  // ============================================================================
  io.icache.bits.pc := currentPC
  io.icache.bits.instEpoch := io.insEpoch
  io.icache.bits.prediction := prediction
  io.icache.bits.exception := exception
  io.icache.bits.privMode := io.privMode
  
  // ============================================================================
  // 7. NextPC 更新逻辑
  // ============================================================================
  // 握手成功（valid && ready）时，更新 nextPC
  // 握手失败时，nextPC 维持当前值
  when(io.icache.valid && io.icache.ready) {
    nextPC := prediction.targetPC
  }
}
