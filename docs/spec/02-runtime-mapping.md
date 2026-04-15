# 动态 Mapping 运行时 Spec

## 目标

把当前一次性启动的 `DataReceiver` 改造成可运行时管理的 listener，使 Web API 可以动态新增、修改、停止、删除 TCP 转发规则。

## 核心对象

### `MappingEntity`

持久化对象，对应 SQLite `mapping` 表：

```text
id
name
enabled
listen_port
forward_host
forward_port
deleted
created_at
updated_at
```

### `MappingRuntime`

运行时对象，不直接序列化：

```text
mappingId
mappingSnapshot
status
serverChannel
bossGroup / workerGroup reference
activeConnections
lastError
startedAt
stoppedAt
```

### `RuntimeMappingManager`

统一管理所有 `MappingRuntime`：

```text
startAll()
startMapping(mapping)
stopMapping(mappingId)
updateMapping(mappingId, request)
deleteMapping(mappingId)
listMappingsWithStatus()
shutdown()
```

要求：

- 线程安全。
- 同一个 mapping 的 start/stop/update 串行执行。
- 不允许两个 running mapping 监听同一个端口。

## 端口管理

启动 mapping 前必须检查：

1. `listenPort` 在 `0..65535`。
2. `forwardPort` 在 `0..65535`。
3. `forwardHost` 非空。
4. 没有其他 running mapping 使用同一个 `listenPort`。

端口冲突分两类：

- 应用内部冲突：另一个 running mapping 已经占用。
- 系统冲突：bind 失败。

API 错误码建议：

| Code | 说明 |
| --- | --- |
| `INVALID_PORT` | 端口不合法 |
| `INVALID_FORWARD_HOST` | 目标主机为空 |
| `PORT_ALREADY_CONFIGURED` | 应用内部已有 running mapping 使用该端口 |
| `BIND_FAILED` | Netty bind 失败 |
| `MAPPING_NOT_FOUND` | mapping 不存在 |

## 修改 mapping 行为

用户已确认：修改 mapping 直接重启端口。

流程：

```text
updateMapping(id, newValue)
  -> lock mapping id
  -> old = repository.find(id)
  -> runtime = runtimes.get(id)
  -> if runtime running: stopRuntime(runtime)
  -> repository.update(newValue)
  -> if newValue.enabled: startRuntime(newValue)
  -> unlock
```

停止旧 runtime 时：

- 先关闭 server channel，停止接收新连接。
- 再关闭 active connections。
- 更新 connection 表状态为 closed。
- 更新 runtime status。

如果更新数据库成功但新 listener 启动失败：

- mapping 保持新配置。
- runtime status = `FAILED`。
- lastError 记录失败原因。
- API 返回失败和 lastError。

不自动回滚到旧配置，避免状态反复不清晰。

## 删除 mapping 行为

建议软删除：

```text
deleted = true
enabled = false
updated_at = now
```

原因：

- 历史 connection / packet 仍然能通过 `mapping_id` 关联。
- 不需要级联删除大规模 packet。

删除流程：

1. 停止 runtime。
2. 关闭 active connections。
3. 标记 deleted。
4. 默认列表不展示 deleted mapping。

## 连接生命周期

### `ConnectionContext`

每个 accepted local channel 对应一个 connection：

```text
connectionId
mappingId
mappingSnapshot
clientIp
clientPort
listenIp
listenPort
forwardHost
forwardPort
localChannel
remoteChannel
openedAt
closedAt
bytesUp
bytesDown
status
```

创建时机：

- local channel accepted 后、尝试连接 remote 前创建 connection 记录。

关闭时机：

- local 或 remote 任一侧关闭后，关闭另一侧。
- 更新 connection 状态。
- 从 `MappingRuntime.activeConnections` 移除。

### 关闭策略

mapping stop/update/delete 时：

- 关闭 listener server channel。
- 遍历 active connections。
- 关闭 local channel 和 remote channel。
- close reason 使用：
  - `MAPPING_STOPPED`
  - `MAPPING_UPDATED`
  - `MAPPING_DELETED`
  - `REMOTE_CLOSED`
  - `LOCAL_CLOSED`
  - `ERROR`

## Netty EventLoopGroup 策略

当前代码每个 `DataReceiver` 和每个 `TCPForWardContext` 都创建新的 `NioEventLoopGroup`，动态管理后不合适。

建议：

- 管理端 HTTP server 使用独立 event loop。
- TCP listener 共享 boss group 和 worker group。
- outbound remote connect 共享 client worker group，或复用 TCP worker group。

推荐结构：

```text
NettyGroups
  - adminBossGroup
  - adminWorkerGroup
  - tcpBossGroup
  - tcpWorkerGroup
  - tcpClientGroup
```

所有 group 在 `AppRuntime.shutdown()` 中统一 `shutdownGracefully()`。

## `DataReceiver` 改造要求

当前 `DataReceiver.start()` bind 后没有保存 channel，无法精确 stop。

目标能力：

```text
start()
stop()
isRunning()
getBoundPort()
```

内部必须保存：

```text
Channel serverChannel
ChannelFuture bindFuture
```

启动成功条件：

- `bind().sync()` 成功。
- `serverChannel` 非空且 active。

停止条件：

- `serverChannel.close().sync()` 完成或超时。
- 不直接 shutdown 共享 event loop。

## `TCPForWardContext` 改造要求

当前 `release()` 为空，需要补齐。

目标能力：

```text
connect()
close()
isConnected()
```

要求：

- 保存 remote channel。
- remote connect 失败时关闭 local channel。
- 连接成功后再绑定 local/remote handler target。
- 关闭时避免递归 close。

## `ByteReadHandler` 改造要求

当前 handler 存在几个动态运行时风险：

- `channelInactive()` 调用 `dataSwap.closeSwap()` 没有 null 判断。
- `closeSwap()` 假设 `channelHandlerContext` 非空。
- consumer 中同步写文件会阻塞 I/O 线程。

目标行为：

```text
channelRead
  -> copy bytes
  -> captureService.capture(event)
  -> target.receiveData(original bytes)
```

要求：

- close 逻辑幂等。
- target 为空时记录错误并关闭当前连接。
- capture 失败不影响转发。
- request / response 方向通过 enum 明确传入，不再靠 printPrefix 推断。

## 状态模型

Mapping runtime status：

| 状态 | 说明 |
| --- | --- |
| `STOPPED` | 未运行 |
| `STARTING` | 启动中 |
| `RUNNING` | 已绑定端口 |
| `STOPPING` | 停止中 |
| `FAILED` | 启动或运行失败 |

Connection status：

| 状态 | 说明 |
| --- | --- |
| `OPENING` | local accepted，remote connecting |
| `OPEN` | 双向连接已建立 |
| `CLOSING` | 正在关闭 |
| `CLOSED` | 已关闭 |
| `FAILED` | remote connect 或转发异常 |

## 并发控制

最低要求：

- `RuntimeMappingManager` 使用 `ConcurrentHashMap<Long, MappingRuntime>` 保存 runtime。
- 每个 mapping id 使用独立 lock，避免同一 mapping 并发 start/stop/update。
- 全局端口占用检查需要同步保护，避免两个请求同时启动同一端口。

可选实现：

```text
private final Object mappingMutationLock = new Object();
```

第一版可以用一个全局锁保护 mapping 变更，降低复杂度。

