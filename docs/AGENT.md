AI 助手的工作如下：

任务目的：分别根据 ALU.md 与 BRU.md 以及相关文档实现 ALU 模块和 BRU 模块的测试。

在开始之前先阅读如下文档：
- docs/Implement/Top.md (总体设计文档)
- docs/Implement/Protocol.md (包含所有模块之间的接口数据结构定义)
- docs/Mechanism/Chisel.md (Decoder 模块设计文档)
- docs/Implement/Modules/ALU.md (ALU 模块设计文档)
- docs/Implement/Modules/BRU.md (BRU 模块设计文档)
- src/main/scala/cpu/Protocol.scala (Chisel 中接口数据结构定义)
- src/main/scala/cpu/ALU.scala
- src/main/scala/cpu/BRU.scala
  
注意：不要更改 ALUTest.scala 与 BRUTest.scala 之外的文件。