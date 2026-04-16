# 大报文采集与存储优化 Spec

## 1. 背景

当前项目的报文采集链路是：

1. 转发线程产出 `PacketEvent`；
2. `PacketCaptureService` 将报文截断到 `maxCaptureBytes`；
3. 后台写线程批量写入 SQLite `packet` 表；
4. 管理端详情与下载接口直接读取 `packet.payload`。

这套设计在“小报文、低频排障”的场景下足够简单，但在请求体和响应体都比较大的场景下会同时暴露两个问题：

- 如果继续保持较小的 `maxCaptureBytes`，管理端只能看到前缀，定位问题经常不够用。
- 如果提高 `maxCaptureBytes` 以保留更多内容，SQLite 会承担越来越重的 BLOB 写入和存储压力。

本 Spec 的目标不是简单把现有 BLOB 挪地方，而是为“大报文场景”定义一套可持续的采集、存储、读取和清理方案。

## 2. 现状分析

### 2.1 当前实现特征

- 采集层已经是异步写入，不会在转发线程里直接同步写库。
- SQLite 已启用 `WAL` 与批量写，短时峰值有一定缓冲能力。
- 队列满时会先 spill 到磁盘文件，再由后台线程回放入库。
- 当前 `packet.payload` 保存的是“截断后的 payload 前缀”，不是完整报文。
- 管理端 `/api/packets/{id}` 和 `/api/packets/{id}/payload` 都依赖 `packet.payload`。

### 2.2 主要瓶颈

#### 2.2.1 SQLite 不适合长期承载高频大 BLOB

当单条请求/响应体较大、报文总量持续增长时，以下成本会明显上升：

- `packet` 表写入事务变重；
- `WAL` 文件增长和 checkpoint 成本上升；
- 库文件体积增大，冷启动、备份、迁移、压缩都变慢；
- 大量 BLOB 与索引、统计信息共存在一个库中，长期维护成本高。

#### 2.2.2 当前方案无法同时满足“性能”和“完整性”

当前只有一个核心旋钮：`maxCaptureBytes`。

- 配小了：性能较稳，但 payload 信息不足。
- 配大了：内容更完整，但 SQLite 写入和占用快速恶化。

这意味着系统缺少“预览数据”和“完整数据”之间的分层。

#### 2.2.3 管理端读取路径与存储路径强耦合

当前详情页和下载接口都假设 payload 在 SQLite 中：

- 详情接口读库后直接做文本/十六进制预览；
- 下载接口直接返回 `packet.payload`。

只要底层存储方式变化，接口与展示层也需要一起调整。

## 3. 目标

### 3.1 业务目标

- 支持大请求体、大响应体场景下的稳定采集。
- 允许保留更大甚至完整 payload，同时避免 SQLite 成为主要瓶颈。
- 保持管理端的列表、详情、下载能力。
- 为后续按时间清理、按容量清理打下基础。

### 3.2 技术目标

- 将“用于检索的元数据”和“体积大的 payload 数据”分层存储。
- 保持转发线程无阻塞，写入仍由后台异步完成。
- 尽量兼容当前管理端接口语义，降低改造面。
- 保持 Java 8 兼容。

## 4. 非目标

- 不在本次改造中引入对象存储、外部消息队列或独立日志系统。
- 不在本次改造中支持跨机归档、分布式检索。
- 不在本次改造中重做 HTTP 解析逻辑或新增复杂协议重组。
- 不要求把历史 SQLite 中的旧 payload 全量迁移成文件格式后才能上线。

## 5. 设计原则

### 5.1 分层

- SQLite 负责元数据、筛选字段、状态字段。
- 磁盘文件负责大 payload 顺序写入与读取。

### 5.2 预览优先

管理端大部分操作并不需要完整 payload，只需要：

- 基本元信息；
- 一小段可视预览；
- 在需要时下载或按偏移读取完整内容。

因此需要把“预览数据”和“完整数据”作为两个不同层级处理。

### 5.3 顺序 IO 优先

大 payload 写入优先采用 append-only 文件模式，避免大量随机写和碎片化小文件。

### 5.4 渐进迁移

优先设计可兼容现网数据的方案，允许新老数据共存一段时间。

## 6. 方案总览

推荐方案：

1. SQLite 继续保存 `packet` 元数据；
2. `packet.payload` 从“完整/较大 BLOB 容器”收缩为“预览 BLOB”；
3. 完整 payload 写入独立磁盘段文件；
4. `packet` 表新增 payload 文件引用字段；
5. 详情接口优先返回预览内容，需要完整内容时按文件偏移读取；
6. 下载接口改为优先流式读取磁盘 payload，若不存在完整 payload 则回退到预览内容。

这是一套“SQLite 保存检索和预览，文件系统保存大对象”的混合存储方案。

## 7. 详细设计

### 7.1 存储模型

#### 7.1.1 `packet` 表保留的内容

`packet` 表继续作为报文索引表，保留：

- 映射、连接、方向、时间等查询字段；
- 协议识别相关字段；
- `payload_size`、`captured_size`、`truncated`；
- 一小段预览 BLOB。

建议新增字段：

```text
payload_store_type TEXT      -- NONE / PREVIEW_ONLY / FILE
payload_file_path TEXT       -- 段文件相对路径
payload_file_offset INTEGER  -- payload 在段文件内的起始偏移
payload_file_length INTEGER  -- 完整 payload 长度
payload_preview_size INTEGER -- 预览字节数
payload_complete INTEGER     -- 是否保留完整 payload
payload_sha256 TEXT          -- 可选，便于校验
```

说明：

- 现有 `payload` 列在第一阶段保留，但语义收敛为“预览内容”。
- 第一阶段不强制重命名为 `payload_preview`，避免一次性改动过大。
- `captured_size` 继续表示预览保留字节数，避免破坏现有接口含义。

#### 7.1.2 文件存储层

完整 payload 写入 `store.payloadDir` 下的段文件。

推荐目录布局：

```text
data/payload/
  2026-04-16/
    mapping-12/
      packet-000001.seg
      packet-000002.seg
```

推荐文件组织方式：

- 按日期分目录；
- 按 mapping 分子目录；
- 每个目录下维护固定大小上限的 append-only 段文件；
- 单个段文件超过阈值后轮转新文件。

推荐默认值：

- `payloadSegmentBytes`: `134217728`（128 MB）
- `previewBytes`: `4096`

### 7.2 采集模式

为兼顾不同环境，增加显式的 payload 存储模式：

```yaml
capture:
  enabled: true
  previewBytes: 4096
  payloadStoreType: "FILE"
  maxPayloadBytes: 0
  queueCapacity: 10000
  batchSize: 100
  flushIntervalMillis: 200

store:
  sqlitePath: "./data/jmagnifier.db"
  spillDir: "./data/spill"
  payloadDir: "./data/payload"
  payloadSegmentBytes: 134217728
  payloadRetentionDays: 7
  payloadRetentionBytes: 21474836480
```

字段说明：

- `previewBytes`: 管理端预览保留字节数，替代当前单一的 `maxCaptureBytes` 概念。
- `payloadStoreType`:
  - `PREVIEW_ONLY`: 仅保留预览，不保留完整 payload。
  - `FILE`: 预览入 SQLite，完整 payload 写文件。
- `maxPayloadBytes`:
  - `0` 表示不主动截断完整 payload；
  - 大于 `0` 表示单条完整 payload 的最大落盘字节数，超出后仍只保留前缀并标记不完整。

兼容策略：

- 旧配置 `maxCaptureBytes` 继续读取，并映射为 `previewBytes`；
- 若未显式配置 `payloadStoreType`，默认使用 `PREVIEW_ONLY`；
- 对大报文环境，推荐改为 `FILE`。

### 7.3 写入流程

#### 7.3.1 新的职责拆分

- `PacketCaptureService`:
  - 负责队列、批量调度、背压与失败处理。
- `PacketRepository`:
  - 负责 SQLite 元数据与预览写入。
- 新增 `PayloadFileStore`:
  - 负责段文件写入、轮转、读取、校验和删除。

#### 7.3.2 单条报文的写入策略

对每个 `PacketEvent`：

1. 从原始字节中截取 `previewBytes` 作为预览；
2. 若 `payloadStoreType=FILE`，将完整 payload 或受 `maxPayloadBytes` 限制后的 payload 写入段文件；
3. 返回文件路径、偏移、长度、是否完整等元数据；
4. 将元数据与预览 BLOB 一起写入 `packet` 表。

#### 7.3.3 批量事务边界

推荐顺序：

1. 批量写段文件；
2. 批量写 SQLite 元数据；
3. 仅当 SQLite 写入成功时，视该批次成功。

失败处理原则：

- 文件写入成功但 SQLite 失败：
  - 该批 payload 文件段记为“孤儿段”，后台异步清理；
- SQLite 成功但文件写入失败：
  - 当前条目标记为 `payload_store_type=PREVIEW_ONLY`；
  - 记录告警，确保管理端仍可查看预览。

理由：

- 不能因为完整 payload 写文件失败而影响转发主链路；
- 管理端“至少能看预览”比“强一致保完整文件”更重要。

### 7.4 读取流程

#### 7.4.1 报文列表

列表接口继续只查 `packet` 表摘要字段，不读取文件，不返回大 BLOB。

#### 7.4.2 报文详情

`GET /api/packets/{id}` 返回：

- 元数据；
- 预览视图；
- payload 存储状态字段。

建议新增字段：

```json
{
  "payloadView": {
    "previewBytes": 4096,
    "previewTruncated": true,
    "fullPayloadAvailable": true,
    "fullPayloadComplete": true,
    "storeType": "FILE"
  }
}
```

说明：

- 详情页默认使用 SQLite 中的预览数据生成文本/十六进制视图；
- 不在详情接口里默认回读完整文件，避免大详情请求拖慢页面；
- 如后续需要“查看更多”能力，可单独追加范围读取接口。

#### 7.4.3 报文下载

`GET /api/packets/{id}/payload` 调整为：

- 若 `payload_store_type=FILE` 且文件存在，流式读取完整 payload；
- 若仅有预览，则返回预览内容，并通过响应头明确：
  - `X-Payload-Truncated: true`
  - `X-Payload-Complete: false`

这样可以保持接口路径不变，同时让下载语义更清晰。

### 7.5 段文件格式

推荐使用简单的二进制帧格式：

```text
magic(4) + version(1) + payloadLength(8) + flags(4) + payloadBytes
```

说明：

- 文件内部只存 payload 原始字节，不重复存 SQLite 已有的元数据；
- 文件偏移对应每条帧的起始位置；
- `flags` 预留压缩、截断、编码标识位。

写入时记录：

- `filePath`
- `offset`
- `length`
- `complete`
- `sha256`（可选）

选择二进制帧而不是单文件单报文的原因：

- 减少小文件数量；
- 降低 inode 压力；
- 更适合顺序追加；
- 清理时可以按段文件整体处理。

### 7.6 清理与保留策略

新增后台清理任务，按两条规则执行：

1. 超过 `payloadRetentionDays` 的段文件删除；
2. 总容量超过 `payloadRetentionBytes` 时，从最老文件开始回收。

清理原则：

- 仅删除完整 payload 文件，不删除 `packet` 元数据；
- 对已删除 payload 的报文，将其视为“仅保留预览”；
- 管理端详情仍可展示预览，但下载接口返回预览或提示完整 payload 已清理。

需要在 SQLite 中增加一个轻量状态：

- `payload_complete=0/1`
- `payload_store_type` 可在清理后从 `FILE` 退化为 `PREVIEW_ONLY`，或增加 `FILE_DELETED` 状态。

推荐使用显式状态 `FILE_DELETED`，便于管理端展示“完整 payload 已过期清理”。

### 7.7 管理端与接口改动

#### 7.7.1 管理端详情页

详情页需要增加以下状态提示：

- 当前仅展示预览；
- 完整 payload 可下载；
- 完整 payload 已被清理；
- 当前 payload 因超限未完整保留。

#### 7.7.2 新的接口兼容要求

- 现有 `/api/packets` 不变；
- 现有 `/api/packets/{id}` 不变，但返回更多 payload 存储状态；
- 现有 `/api/packets/{id}/payload` 不变，但实现从“直接读 SQLite BLOB”改为“优先流式读文件”。

### 7.8 数据迁移策略

采用“只向前兼容，不强制回填”的迁移策略。

#### 7.8.1 新版本上线后

- 旧数据仍保留在 SQLite `payload` 列；
- 新数据按新策略写入：
  - `payload` 保存预览；
  - 完整 payload 写文件。

#### 7.8.2 读取兼容逻辑

读取顺序：

1. 若 `payload_store_type=FILE` 且文件存在，下载完整文件；
2. 否则若 `payload` 非空，返回预览；
3. 否则返回空内容或状态提示。

这样可以让新老数据在同一界面下共存。

### 7.9 可观测性

需要新增运行时统计项：

- `payloadFilesActive`
- `payloadBytesOnDisk`
- `payloadWriteFailures`
- `payloadReadFailures`
- `payloadRetentionDeletedFiles`
- `payloadRetentionDeletedBytes`
- `orphanPayloadFiles`

同时扩展现有运行日志：

- 段文件轮转日志；
- payload 文件写失败日志；
- 下载缺文件告警；
- 清理任务执行结果。

### 7.10 测试要求

#### 7.10.1 单元测试

- 预览截断逻辑；
- 段文件写入与偏移读取；
- 清理策略；
- 新旧数据兼容读取；
- 文件缺失时的降级逻辑。

#### 7.10.2 集成测试

- 大 payload 连续写入；
- 管理端详情读取预览；
- 下载接口返回完整 payload；
- retention 清理后下载降级；
- SQLite 写失败或文件写失败时的容错行为。

#### 7.10.3 性能验证

至少比较以下两组模式：

1. `PREVIEW_ONLY`，`previewBytes=4096`
2. `FILE`，`previewBytes=4096`，完整 payload 落盘

关注指标：

- 转发吞吐；
- 抓包写入耗时；
- SQLite 文件增长速度；
- 管理端详情接口耗时；
- 总磁盘占用增长速度。

## 8. 分阶段落地

### Phase 1：最小可用版

- 引入 `payloadStoreType`、`previewBytes` 配置；
- 新增 payload 文件存储；
- `packet` 表增加文件引用字段；
- 预览继续保存在 `payload` 列；
- 下载接口支持从文件流式返回；
- 管理端展示“完整 payload 可用/不可用”。

目标：

- 不重写现有详情预览逻辑；
- 先解决 SQLite 不适合承载大 payload 的核心问题。

### Phase 2：状态与清理完善

- 增加 retention 清理任务；
- 增加 payload 存储统计和管理端状态提示；
- 增加孤儿文件清理。

### Phase 3：语义收敛

- 视改造成本决定是否将 `payload` 重命名为 `payload_preview`；
- 视管理端需求增加范围读取或“查看更多”接口；
- 根据压测结果评估是否需要独立的 `packet_payload` 表进一步瘦身主表。

## 9. 备选方案对比

### 9.1 方案 A：继续把大 payload 存 SQLite

优点：

- 实现最简单；
- 读路径最直接。

缺点：

- 不能解决根本瓶颈；
- 库文件和写压力会持续放大；
- retention、归档、清理都更重。

结论：

- 不推荐。

### 9.2 方案 B：payload 单条单文件

优点：

- 实现概念简单；
- 文件定位直接。

缺点：

- 小文件数量巨大；
- inode 与目录扫描压力大；
- 清理和遍历成本高。

结论：

- 不推荐作为默认方案。

### 9.3 方案 C：预览入 SQLite，完整 payload 入段文件

优点：

- 查询与展示路径清晰分层；
- 顺序 IO 友好；
- 管理端仍能快速打开详情；
- 支持大 payload 和后续 retention。

缺点：

- 引入文件一致性和清理管理；
- 下载逻辑复杂度上升。

结论：

- 推荐采用。

## 10. 验收标准

- 在 `payloadStoreType=FILE` 下，大 payload 不再以完整 BLOB 形式写入 SQLite。
- 报文列表接口不因 payload 变大而显著退化。
- 详情页默认仍能在不读取完整文件的情况下展示预览。
- 下载接口能够返回完整 payload；若完整 payload 不存在，能明确降级到预览。
- 保留策略生效后，系统仍可通过预览排障，不会因文件清理导致详情报错。
- 新版本可以读取旧数据。

## 11. 开放问题

- 是否需要在 `FILE` 模式下支持 payload 压缩落盘，以进一步降低磁盘占用。
- 是否需要在管理端提供“仅下载预览”与“下载完整 payload”的显式区分。
- 是否需要为 HTTP 场景增加 body 范围读取，而不是一次下载完整文件。
- 是否需要单独抽象 `CaptureStorageMode` 枚举，避免多个布尔/字符串配置组合导致歧义。

## 12. 推荐决策

建议按 Phase 1 -> Phase 2 的顺序落地，并以以下默认策略作为第一版：

- 预览保留 `4096` 字节；
- 完整 payload 落段文件；
- SQLite 只保留检索字段和预览；
- 下载接口优先返回完整文件；
- 默认按时间和容量双阈值清理 payload 文件。

这套方案能在不推翻现有架构的前提下，解决“大报文下 SQLite 压力过大”和“当前只能看截断前缀”这两个核心矛盾。
