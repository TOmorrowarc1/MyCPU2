AI 助手的工作如下：

任务目的：根据 MemorySystem 设计文档与 .scala 实现对应模块测试。

设计文档：
- docs/Implement/Top.md (整体设计文档)
- docs/Implement/Protocol.md (协议设计文档)

MemorySystem 部分设计文档：
- docs/Implement/Modules/LSU.md (LSU 模块设计文档)
- docs/Implement/Modules/MemorySystem/*.md (其他内存系统模块设计文档)

其他相关文档：
- docs/Mechanism/Chisel.md (Chisel 语法文档)
- docs/Mechanism/Privileged.md (特权级机制文档，存在 PMP 检测相关内容)

所有有关模块均在src/main/scala/cpu目录下，测试代码应放置在在src/test/scala/cpu目录下。**请不要修改任何已有文件**。（PMPCheckerTest.scala 已经存在，不需要实现）

