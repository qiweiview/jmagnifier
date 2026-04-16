# jmagnifier Web Runtime Specs

本目录记录 jmagnifier 从静态配置 TCP 转发工具演进为“Netty Web 管理 + 动态监听 + SQLite 报文查看”的设计 spec。

阅读顺序：

1. [00-overall-design.md](00-overall-design.md)：总体架构、核心决策、数据流、配置边界。
2. [01-netty-admin-web.md](01-netty-admin-web.md)：Netty 管理端、认证、API、静态页面设计。
3. [02-runtime-mapping.md](02-runtime-mapping.md)：动态 mapping 生命周期、监听端口管理、连接关闭策略。
4. [03-packet-capture-sqlite.md](03-packet-capture-sqlite.md)：异步报文采集、SQLite 表结构、队列满时磁盘缓冲策略。
5. [04-implementation-plan.md](04-implementation-plan.md)：推荐开发阶段、验收标准和风险点。
6. [05-remaining-work.md](05-remaining-work.md)：当前代码基线后的剩余工作、注释和原始 spec 映射关系。

## 已确认的产品约束

- Web 管理端使用 Netty 实现，不引入 Spring Boot Web。
- Web 修改运行时配置后，不回写 yml。
- 修改 mapping 时直接重启对应监听端口，旧 mapping 的监听和连接都按停止流程关闭。
- 报文异步入库到 SQLite。
- 报文截断大小 `maxCaptureBytes` 是全局配置。
- 报文队列满时先把队列内容落到磁盘缓冲文件，然后清空内存队列继续接收。
- 管理端需要简单密码保护。
