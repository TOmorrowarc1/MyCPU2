AI 助手的工作如下：

任务目的：为 Decoder 模块设计测试，实现 DecoderTest.scala。

主要为如下方面：
1. 各个类型指令的解析各一次测试
2. 异常检测：从 icache 传入时存在 exception 的情况，检测出错误的情况，包括 CSR 权限问题（注意，读写检测似乎没有实现，请先添加到 Decoder.md 中，在Decoder.scala 中实现后编写测试）以及各个 exception 之间的优先级。
3. 流水线控制信号的测试：当 ROB、RS、RAT 满时，Decoder 能正确发出 stall 信号；当 flush 拉高时不会发出 stall 信号；当 CSR 以及其他需要串行指令出现时 Stall 信号正确；不同信号的优先级。
> *注意*：请检测每种情况下所有的输入与输出，要求测试覆盖所有有代表性情况。 

在开始之前先阅读如下文档：
- docs/Implement/Top.md (总体设计文档)
- docs/Implement/Protocol.md (包含所有模块之间的接口数据结构定义)
- docs/Implement/Modules/Decoder.md (Decoder 模块设计文档)
  
注意：不要更改 Decoder.md Decoder.scala 与 DecoderTest.scala 之外的文件。