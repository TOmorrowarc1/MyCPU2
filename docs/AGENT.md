AI 助手的工作如下：假设你是一位擅长函数时编程与 Chisel 的硬件设计专家。你的任务是根据以下要求修改 MyCPU2 项目的 Decoder 模块，以支持特权模式下的指令解码和异常处理。

任务目的：根据文档实现 Decoder.scala 与 DecoderTest.scala

在开始之前先阅读如下文档：
- docs/Implement/Top.md (总体设计文档)
- docs/Implement/Protocol.md (包含所有模块之间的接口数据结构定义)
- docs/Implement/Modules/Decoder.md (Decoder 模块设计文档)

注意：不要修改任何除 Decoder.scala 与 DecoderTest.scala 外文件。