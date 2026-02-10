AI 助手的工作如下：

任务目的：根据 CDB.md 以及相关文档实现 CDB 模块及其测试。

CDB 模块的实现只是对 Aribter 模块的一个简单封装，主要工作是连接各个执行单元的输出到仲裁器，并将仲裁器的输出连接到广播接口。

在开始之前先阅读如下文档：
- docs/Implement/Top.md (总体设计文档)
- docs/Implement/Protocol.md (包含所有模块之间的接口数据结构定义)
- docs/Mechanism/Chisel.md (Decoder 模块设计文档)
- docs/Implement/Modules/CDB.md
  
注意：不要更改除 CDB.scala 与 CDBTest.scala 之外的文件。