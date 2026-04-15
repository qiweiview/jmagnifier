# 总体设计 Spec

## 背景

当前 jmagnifier 是 Java 8 + Netty 的 TCP 转发和 packet dump 工具。启动方式依赖 yml、stdin 或命令行参数；启动后 mapping 固定，报文 dump 直接在 Netty 读事件中同步写文件。

目标是把它演进为一个可运行时管理的 TCP 转发工具：

- 启动后提供 Web 管理界面。
- 通过 Web 动态新增、修改、停止、删除监听端口。
- 报文异步写入 SQLite。
- Web 页面可查询连接和报文。
- 支持全局最大报文截断。
- 管理端使用简单密码认证。

## 非目标

第一阶段不做以下事情：

- 不实现 HTTP 代理语义，仍然只做 raw TCP byte forwarding。
- 不解析 TLS 明文，不做 MITM。
- 不把 Web 上的修改回写到 yml。
- 不引入 Spring Boot Web。
- 不做复杂用户体系、角色权限或多租户。
- 不保证高吞吐数据库写入场景下零丢失；优先保证转发链路不被数据库拖慢。

## 核心决策

### Web 技术栈

管理端使用 Netty HTTP server 实现：

- 复用 Netty 依赖。
- 增加 `netty-codec-http` 已存在，无需引入大型 Web 框架。
- 自建轻量路由、JSON 响应、静态资源处理、认证过滤器。

### 配置来源

启动时可以继续读取 yml 作为初始 mapping。

运行时通过 Web 创建或修改的 mapping 只写入 SQLite，不回写 yml。后续启动时默认以 SQLite 中的 mapping 为准；是否导入 yml 由启动策略控制。

推荐启动优先级：

1. 初始化 SQLite schema。
2. 如果 SQLite 中已有 mapping，则使用 SQLite mapping 启动。
3. 如果 SQLite 中没有 mapping 且启动参数提供 yml，则导入 yml mapping 到 SQLite 后启动。
4. 如果两者都没有，则只启动 Web 管理端。

### 修改 mapping 行为

修改 mapping 时直接重启该 mapping 的监听端口：

1. 停止旧 listener。
2. 关闭该 mapping 下的活跃连接。
3. 更新 SQLite 中的 mapping。
4. 按新配置启动 listener。
5. 更新运行状态。

这样行为简单、可预测，避免同一个 mapping 的新旧转发目标并存。

### 报文存储

报文异步写入 SQLite：

- Netty I/O 线程只复制必要 bytes 和 metadata，然后投递事件。
- 独立 writer 线程负责批量写 SQLite。
- SQLite 开启 WAL，写入采用 batch transaction。

队列满时：

- 先把当前内存队列 drain 到磁盘缓冲文件。
- 清空队列后继续接收新事件。
- 后台 writer 优先消费内存队列，空闲时回放磁盘缓冲文件。

### 截断策略

`maxCaptureBytes` 是全局配置：

- 对 request 和 response 都生效。
- 入队前完成截断，降低内存压力。
- 数据库记录原始长度、实际保存长度、是否截断。

## 目标架构

```text
AppStart
  -> AppRuntime
       -> SqliteDatabase
       -> MappingRepository
       -> PacketRepository
       -> PacketCaptureService
       -> RuntimeMappingManager
       -> NettyAdminServer

RuntimeMappingManager
  -> MappingRuntime
       -> DataReceiver / Listener
       -> Active TCP connections

TCP channel read
  -> ByteReadHandler
       -> PacketCaptureService.enqueue(PacketEvent)
       -> forward bytes to target channel

PacketCaptureService
  -> bounded in-memory queue
  -> spill file queue
  -> SQLite writer
```

## 模块划分

### `com.runtime`

应用运行时编排：

- `AppRuntime`
- `RuntimeOptions`
- `ShutdownCoordinator`

职责：

- 初始化数据库。
- 加载配置。
- 启动 capture service。
- 启动 mapping manager。
- 启动 Web 管理端。
- 统一关闭资源。

### `com.admin`

Netty Web 管理端：

- `NettyAdminServer`
- `HttpRouter`
- `AdminAuthHandler`
- `StaticResourceHandler`
- `JsonResponse`
- `ApiHandlers`

职责：

- 提供 Web UI。
- 提供 REST API。
- 校验管理密码。
- 调用 runtime service。

### `com.mapping`

运行时 mapping 管理：

- `RuntimeMappingManager`
- `MappingRuntime`
- `MappingService`
- `MappingStatus`

职责：

- mapping CRUD。
- 启停 listener。
- 管理活跃连接。
- 持久化 mapping。

### `com.capture`

报文采集：

- `PacketCaptureService`
- `PacketEvent`
- `PacketDirection`
- `CaptureOptions`
- `SpillFileManager`
- `PacketWriter`

职责：

- 接收 Netty 读事件。
- 截断 payload。
- 管理有界队列。
- 队列满时 spill 到磁盘。
- 异步写 SQLite。

### `com.store`

SQLite 存储：

- `DatabaseInitializer`
- `MappingRepository`
- `ConnectionRepository`
- `PacketRepository`
- `SettingRepository`

职责：

- 建表和 schema 迁移。
- JDBC 访问。
- 查询分页。

## 配置设计

yml 增加全局配置，但 Web 修改不回写：

```yaml
admin:
  host: "127.0.0.1"
  port: 8080
  password: "admin"

store:
  sqlitePath: "./data/jmagnifier.db"
  spillDir: "./data/spill"

capture:
  enabled: true
  maxCaptureBytes: 4096
  queueCapacity: 10000
  batchSize: 100
  flushIntervalMillis: 200

mappings:
  - name: "r1"
    enable: true
    listenPort: 9300
    forwardHost: "example.com"
    forwardPort: 80
```

配置默认值：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `admin.host` | `127.0.0.1` | 默认只允许本机访问 |
| `admin.port` | `8080` | 管理端端口 |
| `admin.password` | 必填或启动时生成 | 第一版简单密码 |
| `store.sqlitePath` | `./data/jmagnifier.db` | SQLite 文件 |
| `store.spillDir` | `./data/spill` | 队列满时磁盘缓冲目录 |
| `capture.maxCaptureBytes` | `4096` | 全局 payload 保存上限 |
| `capture.queueCapacity` | `10000` | 内存队列容量 |
| `capture.batchSize` | `100` | SQLite 批量写条数 |
| `capture.flushIntervalMillis` | `200` | 批量写最大等待时间 |

## 数据流

### 启动

```text
AppStart
  -> 解析 RuntimeOptions
  -> 初始化 SQLite
  -> 读取 settings / mappings
  -> 必要时导入 yml mappings
  -> 启动 PacketCaptureService
  -> 启动 RuntimeMappingManager
  -> 启动 NettyAdminServer
```

### 新建 mapping

```text
POST /api/mappings
  -> 校验端口和目标地址
  -> 写入 SQLite mapping
  -> RuntimeMappingManager.start(mapping)
  -> 返回 mapping 状态
```

### 修改 mapping

```text
PUT /api/mappings/{id}
  -> 校验输入
  -> RuntimeMappingManager.stop(id)
  -> 更新 SQLite
  -> RuntimeMappingManager.start(updated)
  -> 返回新状态
```

### 报文采集

```text
ByteReadHandler.channelRead
  -> 复制 ByteBuf 到 byte[]
  -> PacketCaptureService.capture(...)
       -> 按 maxCaptureBytes 截断
       -> offer 到内存队列
       -> 如果队列满，触发 spill
  -> 原始 bytes 转发给目标 channel
```

注意：转发必须使用原始完整 bytes，截断只影响存储，不影响网络转发。

## 错误处理原则

- 管理端 API 返回结构化错误。
- listener 启动失败不应导致整个应用退出。
- SQLite 写入失败不应阻塞 TCP 转发。
- spill 文件写入失败时记录错误计数，继续尝试内存队列写入。
- mapping 修改失败时尽量恢复到可描述状态，不做静默失败。

## 安全边界

第一版简单密码：

- 管理端默认绑定 `127.0.0.1`。
- 登录成功后使用 cookie 保存 session id。
- session id 只存在内存，应用重启后失效。
- 密码不要写入前端页面。
- API 和静态页面都经过认证，登录页和登录 API 除外。
- payload 展示必须做 HTML escape，不能直接插入为 HTML。

## 兼容性

- 保持 Java 8。
- 保持 raw TCP forwarding。
- 旧 yml 中的 `mappings` 继续可用。
- 旧的 file dump 可以作为兼容功能保留，但不应再作为主链路；默认使用 SQLite capture。

## 关键验收标准

- 启动后访问 Web 管理端需要密码。
- Web 可新增监听端口，并能转发 TCP 流量。
- Web 可修改 mapping，修改后旧监听关闭，新监听生效。
- Web 可停止和删除 mapping。
- request / response 报文异步写入 SQLite。
- 报文保存包含方向、连接、IP、端口、原始长度、保存长度、截断标记和时间。
- 大于 `maxCaptureBytes` 的报文被截断保存，但转发内容完整。
- 报文队列满时会生成磁盘缓冲文件并清空内存队列。
- Web 可分页查看连接和报文详情。

