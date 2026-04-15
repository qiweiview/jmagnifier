# 报文采集与 SQLite Spec

## 目标

把请求和响应报文从同步文件 dump 改为异步 SQLite 存储，并支持：

- 全局 `maxCaptureBytes` 截断。
- 存储 payload 本身。
- 存储 IP、端口、方向、连接、长度、时间等基础信息。
- Web 分页查询。
- 队列满时把内存队列落到磁盘缓冲文件，然后清空内存队列。

## 采集事件

### `PacketEvent`

字段：

```text
mappingId
connectionId
direction
clientIp
clientPort
listenIp
listenPort
targetHost
targetPort
remoteIp
remotePort
payload
payloadSize
capturedSize
truncated
sequenceNo
receivedAt
```

说明：

- `payload` 是已截断后的 bytes。
- `payloadSize` 是原始 bytes 长度。
- `capturedSize` 是实际保存 bytes 长度。
- `truncated = payloadSize > capturedSize`。
- `sequenceNo` 在 connection 内单调递增，request/response 可以共用一个序列，也可以分方向维护。第一版建议共用一个 `AtomicLong`。

### 方向

```text
REQUEST:  client -> remote
RESPONSE: remote -> client
```

## 截断策略

`maxCaptureBytes` 是全局配置。

截断发生在入队前：

```text
original = bytes.length
captured = min(original, maxCaptureBytes)
payload = copy first captured bytes
truncated = original > captured
```

要求：

- 网络转发仍使用完整原始 bytes。
- SQLite 只保存截断后的 payload。
- 如果 `maxCaptureBytes = 0`，表示只保存 metadata，不保存 payload。
- 如果 `capture.enabled = false`，不产生 packet 记录，但 connection 统计仍可以保留。

## 内存队列

使用有界队列：

```text
ArrayBlockingQueue<PacketEvent>
```

配置：

```yaml
capture:
  queueCapacity: 10000
  batchSize: 100
  flushIntervalMillis: 200
```

入队流程：

```text
capture(event)
  -> if disabled: return
  -> truncate payload
  -> if queue.offer(event): return
  -> spillQueueToDisk()
  -> queue.clear()
  -> queue.offer(event)
```

注意：

- `spillQueueToDisk()` 必须有并发保护，避免多个 I/O 线程同时 spill。
- spill 时应 drain queue 到局部 list，不应长时间持有全局锁。
- 如果 spill 失败，不应阻塞转发链路；记录错误计数并尝试重新 offer。

## 磁盘缓冲策略

用户已确认：队列满时存磁盘后清空队列。

### Spill 文件目录

配置：

```yaml
store:
  spillDir: "./data/spill"
```

文件命名：

```text
spill-{yyyyMMdd-HHmmssSSS}-{sequence}.bin
```

### 文件格式

第一版建议使用简单二进制 frame 格式，避免 JSON/base64 放大 payload：

```text
MAGIC      4 bytes  "JMSP"
VERSION    1 byte
COUNT      4 bytes

repeated frame:
  LENGTH    4 bytes
  JSON_LEN  4 bytes
  JSON      UTF-8 metadata without payload
  PAYLOAD   bytes
```

metadata JSON 包含除 payload 外的 `PacketEvent` 字段。

如果实现复杂度优先，也可以第一版用 JSON Lines + base64 payload，但要接受磁盘占用变大。

### Spill 回放

writer 线程逻辑：

1. 优先消费内存队列。
2. 内存队列空闲时扫描 spill 目录。
3. 按文件名时间顺序读取。
4. 成功写入 SQLite 后删除 spill 文件。
5. 写入失败则保留文件，下次重试。

注意：

- 回放 spill 时可能与新流量并发。第一版允许新内存事件优先，避免实时流量堆积。
- 如果 spill 文件损坏，移动到 `spillDir/bad`，记录错误。

## SQLite Schema

### `schema_version`

```sql
CREATE TABLE IF NOT EXISTS schema_version (
  version INTEGER NOT NULL
);
```

### `mapping`

```sql
CREATE TABLE IF NOT EXISTS mapping (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  listen_port INTEGER NOT NULL,
  forward_host TEXT NOT NULL,
  forward_port INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mapping_deleted ON mapping(deleted);
CREATE INDEX IF NOT EXISTS idx_mapping_listen_port ON mapping(listen_port);
```

### `connection`

```sql
CREATE TABLE IF NOT EXISTS connection (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  mapping_id INTEGER NOT NULL,
  client_ip TEXT,
  client_port INTEGER,
  listen_ip TEXT,
  listen_port INTEGER,
  forward_host TEXT,
  forward_port INTEGER,
  remote_ip TEXT,
  remote_port INTEGER,
  status TEXT NOT NULL,
  close_reason TEXT,
  opened_at TEXT NOT NULL,
  closed_at TEXT,
  bytes_up INTEGER NOT NULL DEFAULT 0,
  bytes_down INTEGER NOT NULL DEFAULT 0,
  error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_connection_mapping_time ON connection(mapping_id, opened_at);
CREATE INDEX IF NOT EXISTS idx_connection_status ON connection(status);
CREATE INDEX IF NOT EXISTS idx_connection_client ON connection(client_ip, client_port);
```

### `packet`

```sql
CREATE TABLE IF NOT EXISTS packet (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  mapping_id INTEGER NOT NULL,
  connection_id INTEGER NOT NULL,
  direction TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  client_ip TEXT,
  client_port INTEGER,
  listen_ip TEXT,
  listen_port INTEGER,
  target_host TEXT,
  target_port INTEGER,
  remote_ip TEXT,
  remote_port INTEGER,
  payload BLOB,
  payload_size INTEGER NOT NULL,
  captured_size INTEGER NOT NULL,
  truncated INTEGER NOT NULL,
  received_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_packet_mapping_time ON packet(mapping_id, received_at);
CREATE INDEX IF NOT EXISTS idx_packet_connection_seq ON packet(connection_id, sequence_no);
CREATE INDEX IF NOT EXISTS idx_packet_direction_time ON packet(direction, received_at);
```

### `setting`

```sql
CREATE TABLE IF NOT EXISTS setting (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
```

用于保存运行时全局设置，例如 `maxCaptureBytes`。第一版也可以只从 yml 读取，不通过 Web 修改全局设置。

## SQLite 连接配置

推荐启动时执行：

```sql
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA busy_timeout=5000;
```

写入策略：

- 单 writer 线程。
- 批量 insert。
- 每批一个 transaction。
- reader API 使用独立 connection。

## Writer 线程

伪代码：

```text
while running:
  batch = drain queue up to batchSize or wait flushInterval
  if batch not empty:
    insert batch into packet
    update connection bytes counters
    continue

  spillFile = spillManager.nextFile()
  if spillFile exists:
    read events
    insert events in batches
    delete spillFile
```

关闭流程：

1. 停止接收新事件。
2. drain 内存队列。
3. 尝试写 SQLite。
4. 写失败则 spill 到磁盘。
5. 关闭 SQLite connection。

## Connection 统计

每次 packet 写入时更新连接字节数：

- `REQUEST` 增加 `bytes_up`。
- `RESPONSE` 增加 `bytes_down`。

字节数使用原始 `payloadSize`，不是 `capturedSize`。

为了降低写放大，可以在 writer 内存中累加每批 connection delta，再批量 update。

## 查询和展示

### Packet 列表

列表查询不返回 `payload`，避免大响应：

```sql
SELECT id, mapping_id, connection_id, direction, sequence_no,
       client_ip, client_port, target_host, target_port,
       payload_size, captured_size, truncated, received_at
FROM packet
WHERE ...
ORDER BY received_at DESC, id DESC
LIMIT ? OFFSET ?;
```

### Packet 详情

详情查询返回 payload，并在服务端生成：

- hex preview。
- text preview。

text preview 要处理：

- UTF-8 尝试解码。
- 不可见字符替换。
- HTML escape。

## 数据保留

第一版可以先提供手动清理 API，后续再加自动保留策略。

建议 API：

```text
DELETE /api/packets?before=...
DELETE /api/mappings/{id}/packets
```

后续配置：

```yaml
retention:
  days: 7
  maxDatabaseSizeMb: 1024
```

## 失败和降级

| 场景 | 行为 |
| --- | --- |
| SQLite 短暂 busy | writer 重试，遵守 busy_timeout |
| SQLite 持续失败 | 内存队列满后 spill 到磁盘 |
| spill 失败 | 记录错误计数，丢弃该批事件，转发不受影响 |
| packet payload 太大 | 入队前按全局配置截断 |
| capture disabled | 不写 packet，只做转发 |

## 监控指标

至少在首页或 `/api/runtime` 暴露：

```text
queueSize
queueCapacity
spillFileCount
spillBytes
packetsWritten
packetsSpilled
packetsDropped
lastWriterError
```

