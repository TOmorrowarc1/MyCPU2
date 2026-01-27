AI 助手的工作如下：

任务目的：实现 Fetcher 模块本身及其测试，分别对应填充 src/main/scala/cpu/Fetcher.scala 和 src/test/scala/cpu/FetcherTest.scala 文件。

在开始之前先阅读如下文档：
- docs/Mechanism/Chisel.md (包含基本的 Chisel 语法信息与测试架构)
- docs/Implement/Top.md (包含 CPU 顶层模块设计说明)
- docs/Implement/Protocol.md (包含所有模块之间的接口数据结构定义)
- docs/Implement/Modules/Fetcher.md (Fetcher 模块的设计说明)

注意：除 Fetcher.scala 和 FetcherTest.scala 外，不要修改其他任何文件。