# 分阶段实施 Spec

## 原则

- 保持 Java 8。
- 保持 raw TCP forwarding。
- 每个阶段都应能编译。
- 避免一次性重写全部代码。
- 先抽象生命周期，再接 Web，再接 SQLite 报文查询。

## 阶段 0：基础修正和准备

目标：降低后续动态化风险。

任务：

- 修复命令行三参数模式判断：`args.length >= 3` 或明确新启动参数格式。
- 端口校验改为 `0..65535`。
- YAML mapping 缺少 `console` / `dump` 时补默认值。
- `Mapping.enable` 在启动时生效。
- `ByteReadHandler.channelInactive()` 增加 null 和幂等保护。
- `ByteReadHandler.closeSwap()` 处理 context 为空。
- response 打印配置使用 `printResponse`。
- `pom.xml` resources 包含 yml 和后续 admin 静态资源。

验收：

- `mvn -DskipTests package` 成功。
- 旧 yml 启动方式仍可用。

## 阶段 1：运行时 Mapping Manager

目标：在没有 Web UI 的情况下，代码层面支持 start/stop/update mapping。

任务：

- 新增 `RuntimeMappingManager`。
- 改造 `DataReceiver`，保存 server channel，支持 stop。
- 改造 `TCPForWardContext`，保存 remote channel，支持 close。
- 引入 `ConnectionContext`。
- 管理 active connections。
- 抽出共享 `NettyGroups`，避免每个连接创建 event loop。
- `AppStart` 改为创建 `AppRuntime` 并通过 manager 启动 mappings。

验收：

- 启动多个 mapping 正常。
- 停止某个 mapping 后端口释放。
- 修改 mapping 后旧端口不可连接，新端口生效。
- 停止 mapping 会关闭该 mapping 的活跃连接。

## 阶段 2：SQLite 基础存储

目标：引入 SQLite，持久化 mapping 和 connection。

任务：

- 添加 SQLite JDBC 依赖。
- 新增 `DatabaseInitializer`。
- 创建 `mapping`、`connection`、`packet`、`setting` 表。
- 新增 repository 层。
- 启动时执行 schema 初始化和 PRAGMA。
- 支持 yml 初次导入到 SQLite。
- mapping CRUD 改为先写 SQLite，再驱动 runtime。
- connection open/close 写入 SQLite。

验收：

- 首次启动可从 yml 导入 mapping。
- 重启后从 SQLite 恢复 mapping，不依赖回写 yml。
- Web 尚未完成时，可通过日志或临时入口验证 mapping 持久化。

## 阶段 3：异步 Packet Capture

目标：替换同步文件 dump 主链路，实现异步报文入库。

任务：

- 新增 `PacketCaptureService`。
- 新增 `PacketEvent`、`PacketDirection`、`CaptureOptions`。
- 实现全局 `maxCaptureBytes` 截断。
- 实现有界内存队列。
- 实现 SQLite writer 批量写。
- 实现 connection bytes 统计。
- 改造 `ByteReadHandler`，从同步 dump 改为 capture event。
- 保留旧 dump 配置时，明确它不是主存储路径；可以暂时关闭或后续移除。

验收：

- request / response 都能写入 `packet` 表。
- 大报文只保存前 `maxCaptureBytes` bytes。
- 转发目标收到完整原始报文。
- 高流量下 Netty I/O 线程不直接写 SQLite。

## 阶段 4：队列满 Spill 到磁盘

目标：实现用户确认的队列满处理策略。

任务：

- 新增 `SpillFileManager`。
- 队列满时 drain 内存队列到 spill 文件。
- spill 完成后清空队列，并接收当前新事件。
- writer 空闲时回放 spill 文件。
- spill 成功写入 SQLite 后删除文件。
- 记录 spill 指标和错误。

验收：

- 人为设置很小 `queueCapacity` 可以稳定触发 spill。
- spill 文件生成后会被 writer 回放并删除。
- spill 期间 TCP 转发不中断。
- spill 文件损坏时不会导致 writer 线程退出。

## 阶段 5：Netty Admin API

目标：提供可用的 HTTP JSON API 和简单密码认证。

任务：

- 新增 `NettyAdminServer`。
- 实现 HTTP pipeline。
- 实现轻量 router。
- 实现登录、logout、session cookie。
- 实现 mapping API。
- 实现 connection 查询 API。
- 实现 packet 查询和 payload 下载 API。
- 实现 `/api/runtime` 指标。

验收：

- 未登录访问 API 返回 401。
- 登录后可创建、修改、停止、删除 mapping。
- 修改 mapping 会直接重启端口。
- 可分页查询 connection 和 packet。
- 可下载 captured payload。

## 阶段 6：Web UI

目标：提供基础可用的管理页面。

任务：

- 新增登录页。
- 新增首页 runtime summary。
- 新增 mapping 管理页面。
- 新增 connection 列表页面。
- 新增 packet 查询页面。
- packet 详情支持 hex view 和 escaped text view。
- 静态资源打包进 jar。

验收：

- 浏览器访问管理端需要登录。
- 页面可完成 mapping CRUD。
- 页面可查看报文列表和详情。
- payload text view 不产生 XSS。

## 阶段 7：清理、测试和文档

目标：稳定化。

任务：

- 增加 Maven Wrapper。
- 固定 JUnit 版本，不使用 `RELEASE`。
- 增加纯逻辑单测：
  - mapping 校验。
  - yml 默认值补齐。
  - packet 截断。
  - spill 文件读写。
  - SQLite repository。
- 增加集成测试：
  - ephemeral listen port。
  - local echo server。
  - request / response packet 入库。
  - mapping update 重启端口。
- 更新 README。

验收：

- `mvn -DskipTests package` 成功。
- 新增单测可独立运行，不调用当前会阻塞的 `AppStartTest`。
- README 能说明 Web 启动、密码、SQLite 路径和 capture 配置。

## 推荐开发顺序

优先顺序：

1. 阶段 0。
2. 阶段 1。
3. 阶段 2。
4. 阶段 3。
5. 阶段 5 的 API 子集：login + mapping API。
6. 阶段 4。
7. 阶段 5 剩余 API。
8. 阶段 6。
9. 阶段 7。

原因：

- 动态端口管理是整个需求的地基。
- SQLite mapping 持久化要先于 Web CRUD。
- packet capture 可以先无页面验证。
- Web UI 最后做，避免前端绑定未稳定的 API。

## 主要风险

### Netty 关闭递归

local 和 remote channel 互相关闭时可能重复调用。所有 close 方法必须幂等。

### SQLite 写入拖慢转发

禁止在 Netty I/O 线程中写 SQLite。capture service 失败不能影响 `writeAndFlush` 转发。

### Spill 文件一致性

写 spill 文件时应先写临时文件：

```text
spill-xxx.tmp
```

写完并 fsync/close 后 rename 为：

```text
spill-xxx.bin
```

writer 只扫描 `.bin`。

### Web 管理端暴露风险

默认绑定 `127.0.0.1`。如果用户配置外网地址，启动日志必须提醒。

### payload 展示安全

报文可能包含任意字节，不可信。页面必须 escape，下载接口使用 octet-stream。

## 最小可交付版本

最小版本应包含：

- Netty 管理端登录。
- mapping 新增、修改、停止、删除。
- SQLite 持久化 mapping。
- request / response packet 异步入库。
- 全局 maxCaptureBytes。
- packet 列表和详情查看。
- 队列满 spill 到磁盘。

可以暂缓：

- 自动数据保留。
- 实时报文流。
- HTTP 自动解析。
- payload 搜索。
- 复杂图表。

