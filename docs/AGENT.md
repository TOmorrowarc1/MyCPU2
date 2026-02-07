AI 助手的工作如下：

任务目的：根据 BRU.md 以及相关文档实现 BRU 模块。

在开始之前先阅读如下文档：
- docs/Implement/Top.md (总体设计文档)
- docs/Implement/Protocol.md (包含所有模块之间的接口数据结构定义)
- docs/Mechanism/Chisel.md (Decoder 模块设计文档)
- docs/Implement/Modules/BRU.md (BRU 模块设计文档)
- src/main/scala/cpu/Protocol.scala (Chisel 中接口数据结构定义)
- src/main/scala/cpu/RS.scala (内部存在 BRU 保留站源码)
- src/main/scala/cpu/ALU.scala (ALU 模块源码，两者作为 EU 设计风格类似)
  
注意：不要更改 BRU.scala 之外的文件。