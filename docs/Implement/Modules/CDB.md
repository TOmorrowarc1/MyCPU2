# CDB (公共数据总线) 设计文档

## 1. 概述

CDB（Common Data Bus，公共数据总线）将所有执行单元（EU）的结果汇聚并广播到系统中的各个模块。CDB 实现了多对一的仲裁机制，确保在任意时刻只有一个 EU 的结果被广播到保留站（RS）、物理寄存器文件（PRF）和重排序缓冲区（ROB）。

## 2. CDB 接口

### 2.1 输入接口

CDB 从多个执行单元和控制模块接收输入。

#### 2.1.1 来自执行单元（EU）

输入源为以下四个执行单元：

| 执行单元 | 描述                             |
| -------- | -------------------------------- |
| ALU      | 算术逻辑单元，执行整数运算       |
| BRU      | 分支解决单元，执行分支和跳转指令 |
| LSU      | 加载存储单元，执行内存访问指令   |
| ZICSRU   | CSR 指令单元，执行 CSR 读写指令  |

每个 EU 使用 [`CDBMessage`](../../../src/main/scala/cpu/Protocol.scala) 结构体发送结果：

| 信号            | 类型        | 描述                                                  |
| --------------- | ----------- | ----------------------------------------------------- |
| `robId`         | `RobTag`    | 重排序缓冲区条目标识符，用于 ROB 标记指令完成         |
| `phyRd`         | `PhyTag`    | 目标物理寄存器标识符，用于 RS 唤醒依赖指令和 PRF 写入 |
| `data`          | `DataW`     | 写入 PRF 的数据                                       |
| `hasSideEffect` | `Bits(1.W)` | 副作用标志（非幂等 Load 指令专用）                    |
| `exception`     | `Exception` | 执行阶段产生的异常信息                                |

### 2.2 输出接口

CDB 将仲裁后的结果广播，系统中各个模块按需使用。依旧使用 `CDBMessage` 结构体。

### 2.3 Chisel 接口定义

class CDB extends Module with CPUConfig {
  val io = IO(new Bundle {
    // 输入接口
    val ALU = Flipped(Decoupled(new CDBMessage))
    val BRU = Flipped(Decoupled(new CDBMessage))
    val LSU = Flipped(Decoupled(new CDBMessage))
    val ZicsrU = Flipped(Decoupled(new CDBMessage))

    // 输出接口
    val Boardcast = Decoupled(new CDBMessage)
  })
}

## 3. 内部逻辑

### 3.1 仲裁机制

当多个执行单元同时有结果需要广播时，CDB 按照预设优先级进行仲裁：

| 优先级 | 执行单元 | 说明                                                                           |
| ------ | -------- | ------------------------------------------------------------------------------ |
| 1 (高) | ZICSRU   | CSR 指令单元，因为 CSR 指令在 ROB 中引发全局堵塞，需要最高优先级以确保执行效率 |
| 2      | LSU      | 加载存储单元，内存操作需要较高优先级                                           |
| 3      | BRU      | 分支解决单元，分支结果需要及时处理                                             |
| 4      | ALU      | 算术逻辑单元，整数运算优先级最低                                               |

```scala
// 仲裁逻辑示例
val arbiter = Module(new Arbiter(new CDBMessage, 4))

// 连接四个执行单元的输出到仲裁器
arbiter.io.in(0) <> io.ZicsrU  // 最高优先级
arbiter.io.in(1) <> io.LSU
arbiter.io.in(2) <> io.BRU
arbiter.io.in(3) <> io.ALU   // 最低优先级

// 仲裁器输出
io.Boardcast <> arbiter.io.out
```

### 3.2 反压信号

根据仲裁器结果反压输入信号。例如，如果 BRU 的结果被选中，CDB 会反压 ALU 和 LSU 的输入信号，直到 BRU 的结果被广播完成。

## 4. 验证考虑

### 4.1 功能验证

CDB 的关键验证点：

1. **仲裁正确性**：验证多个 EU 同时请求时，按照正确的优先级进行仲裁
2. **结果完整性**：验证广播的结果与 EU 发送的结果完全一致
3. **时序正确性**：验证结果在正确的周期被广播

### 4.2 边界情况测试

测试用例应覆盖：

1. **单 EU 请求**：只有一个 EU 有结果需要广播
2. **多 EU 同时请求**：多个 EU 同时有结果需要广播
3. **异常传播**：验证异常信息正确传播
4. **连续广播**：验证连续多个周期的结果广播
5. **空周期**：验证没有 EU 请求时的正确行为
