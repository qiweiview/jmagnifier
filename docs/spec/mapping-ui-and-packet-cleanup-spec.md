# 管理端“映射配置交互 + 报文记录清理” Spec

## 1. 背景

当前管理端已经有可用的映射配置页和报文记录页，但存在三类明显的交互问题：

1. 映射列表里的 `启动` / `停止` 按钮始终同时展示，和当前运行状态没有关联，容易误点，也无法表达“正在启动 / 正在停止”。
2. 映射的新建、编辑入口不一致。现在新建依赖左侧常驻表单，编辑也是把数据回填到左侧，操作焦点会从列表跳走，页面结构也被表单长期占用。
3. 报文记录只支持查询和详情，不支持清理历史数据。随着数据积累，列表和 SQLite / payload 文件都会持续增长。

本 Spec 的目标是把这三个点收敛为一套可直接编码的交互和后端实现方案。

## 2. 现状分析

### 2.1 映射配置页现状

前端实现位于 [src/main/resources/admin/assets/app.js](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/resources/admin/assets/app.js) 的 `renderMappings()` / `loadMappings()`：

- 页面使用左右分栏布局，左侧常驻表单，右侧映射列表。
- 列表每一行固定渲染 `编辑`、`启动`、`停止`、`删除` 四个按钮。
- `editMapping(id)` 的行为是把数据回填到左侧表单，而不是打开独立上下文。
- 当前 `translateStatus()` 没有覆盖 `STARTING` / `STOPPING`，而后端 `MappingStatus` 实际已经定义了这两个状态。

后端实现位于 [src/main/java/com/admin/NettyAdminServer.java](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/java/com/admin/NettyAdminServer.java) 和 [src/main/java/com/mapping/RuntimeMappingManager.java](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/java/com/mapping/RuntimeMappingManager.java)：

- 已有 `GET /api/mappings`
- 已有 `POST /api/mappings`
- 已有 `PUT /api/mappings/{id}`
- 已有 `POST /api/mappings/{id}/start`
- 已有 `POST /api/mappings/{id}/stop`
- 返回数据里已经包含 `status`、`enabled`、`lastError`、`activeConnections`

也就是说，状态驱动按钮切换所需的数据已经具备，主要缺口在前端渲染与交互状态管理。

### 2.2 报文记录页现状

前端 `renderPackets()` / `loadPackets()` 只支持：

- 条件筛选
- 分页浏览
- 详情弹框
- 下载单条负载

后端 `NettyAdminServer` 只提供：

- `GET /api/packets`
- `GET /api/packets/{id}`
- `GET /api/packets/{id}/payload`

存储层现状：

- 报文元数据存 SQLite `packet` 表。
- 完整 payload 可能落到 `data/payload/<yyyy-MM-dd>/mapping-<id>/packet-xxxxxx.seg`。
- payload 分段文件按“报文日期目录”聚合，而不是一包一文件。

这意味着“清理报文”不能简单做成逐条删文件；否则会遇到一个 segment 文件包含多条报文的问题。

## 3. 目标

### 3.1 业务目标

- 让映射配置页的操作按钮和真实状态一致。
- 让新建、编辑映射拥有一致的交互容器。
- 让报文记录支持面向运维场景的历史清理。

### 3.2 体验目标

- 用户看到列表时，能直接知道下一步能做什么，而不是自己判断该点启动还是停止。
- 新建、编辑都在弹框内完成，避免页面左右分裂。
- 清理报文时，用户明确知道清理范围，并看到清理结果反馈。

## 4. 非目标

- 本次不改映射字段模型和协议配置结构。
- 本次不支持“按筛选条件删除报文”。
- 本次不做 segment 文件重写或 payload 文件碎片整理。
- 本次不改连接记录清理策略。

## 5. 方案总览

本次改造拆成三个子项：

1. 映射列表操作区改成“状态驱动”。
2. 映射新建 / 编辑统一迁移到弹框。
3. 报文记录新增“清空全部”和“清空非当日”。

建议按“前端交互重构 -> 报文清理后端 -> 前端接入清理”的顺序落地。

## 6. 子项 A：映射列表操作与状态联动

### 6.1 状态与按钮映射

以 `status` 为主判断依据，`enabled` 仅作文案辅助，不作为主开关来源。

建议映射规则：

| `status` | 主操作按钮 | 按钮状态 | 说明 |
| --- | --- | --- | --- |
| `RUNNING` | `停止` | 可点击 | 不显示 `启动` |
| `STOPPED` | `启动` | 可点击 | 不显示 `停止` |
| `FAILED` | `重试启动` | 可点击 | 继续保留错误文案 |
| `STARTING` | `启动中` | 禁用 | 防重复提交 |
| `STOPPING` | `停止中` | 禁用 | 防重复提交 |

补充规则：

- `编辑` 默认一直可见，但在该行有 pending 请求时禁用。
- `删除` 建议在该行有 pending 请求时禁用，避免和启动 / 停止并发。
- `translateStatus()` 需要补齐 `STARTING` / `STOPPING` 中文文案。

### 6.2 前端实现建议

前端状态新增：

```js
state.mappingActionPending = {
  // [mappingId]: 'start' | 'stop' | 'delete' | 'save'
};
```

关键调整：

1. 新增 `renderMappingActionButtons(item)`，统一根据 `status` 和 pending 状态返回按钮 HTML。
2. `mappingAction(id, action)` 发起请求前先写入 pending，返回后清理 pending 并 reload。
3. 如果某行处于 pending，整行所有操作按钮都禁用，避免重复请求。

### 6.3 交互细节

- 点击 `启动` 后，按钮立刻变成禁用态 `启动中`，直到接口返回并刷新列表。
- 点击 `停止` 后，按钮立刻变成禁用态 `停止中`。
- `FAILED` 态的主操作显示为 `重试启动`，比单纯展示 `启动` 更贴近用户心智。
- `lastError` 保留在列表中，不要因为只剩一个按钮就丢失失败诊断信息。

## 7. 子项 B：映射新建 / 编辑统一弹框

### 7.1 目标交互

映射页改为“列表主视图 + 弹框编辑器”：

- 页面主体只保留映射列表。
- 页面 Hero 或列表头部提供 `新建映射` 按钮。
- 点击 `新建映射` 打开弹框，展示空白表单。
- 点击 `编辑` 打开同一个弹框，展示已回填表单。

不再保留左侧常驻编辑表单。

### 7.2 页面结构调整

当前 `renderMappings()` 的 `split` 双栏布局改为单栏：

- Hero 区右上角：`新建映射`
- 主面板：映射列表
- 弹框：承载表单

这样可以释放横向空间，让映射列表更稳定，也避免编辑时视线来回切换。

### 7.3 前端状态建议

```js
state.mappingEditor = {
  open: false,
  mode: 'create' | 'edit',
  mappingId: null,
  submitting: false
};
```

推荐新增函数：

- `openMappingModal(mode, item)`
- `renderMappingModal()`
- `fillMappingForm(item)`
- `resetMappingForm(mode)`
- `closeMappingModal()`

实现上可以继续复用现有 `readMappingForm()`、`syncMappingFormVisibility()`、`saveMapping()`，但要把表单 HTML 从页面内嵌改成 modal body 渲染。

### 7.4 弹框规格

- `新建` 标题：`新建映射`
- `编辑` 标题：`编辑映射 #<id>`
- 关闭按钮：右上角 `关闭`
- 底部操作：`保存` + `取消`

建议补一个轻量交互：

- 如果表单已修改但未保存，关闭弹框时弹出浏览器 `confirm` 提醒。

这不是必须项，但成本低，能明显降低误关风险。

### 7.5 与现有 modal 的兼容

当前全局 modal 已用于连接详情和报文详情，映射编辑可以直接复用：

- 保留 `openModal()` / `closeModal()`
- 通过新增 modal class，例如 `modal-mapping-editor`，给映射表单单独宽度和滚动策略
- 关闭时清理 `state.mappingEditor`

注意点：

- 报文详情会给 modal 打 `modal-packet-detail` class，映射编辑也应有自己的 class，避免样式串扰。
- `closeModal()` 需要变成“按当前 modal 类型做清理”，不要只处理报文详情状态。

### 7.6 后端影响

该子项不需要新增后端接口。

现有接口已经满足：

- 新建：`POST /api/mappings`
- 编辑：`PUT /api/mappings/{id}`

## 8. 子项 C：报文记录清理

### 8.1 需求定义

报文记录页新增一个统一入口 `清理记录`，点击后打开确认弹框，提供两种操作：

1. `清空全部`
2. `清空非当日`

V1 不做“按当前筛选条件清空”，避免语义过重和误删。

### 8.2 为什么不做逐条文件删除

payload 文件当前按 segment 聚合，一个 `.seg` 里可能包含同一天、同 mapping 的多条报文。

因此：

- 删除单条报文时，不能直接删除对应 payload 文件。
- 本次需求选择的两个范围恰好适合“按日期目录”或“全量目录”删除，不需要 segment 重写。

这也是为什么本次建议只支持：

- 全部清空
- 非当日清空

而不支持更细粒度的 payload 级物理删除。

### 8.3 “非当日”的边界定义

V1 建议严格按报文存储使用的日期边界定义“当日”：

- 依据 `received_at` 的 `yyyy-MM-dd` 前缀
- 与 payload 目录 `data/payload/<yyyy-MM-dd>/...` 使用同一日期口径

原因：

- 当前 `receivedAt` 来自 `Instant.now().toString()`，天然是 UTC 时间。
- payload 目录也是按该时间字符串的日期前缀切分。
- 如果改成按浏览器本地日期清理，会出现一个 UTC 日期目录被部分保留、部分删除的情况，需要做 segment 文件重写，超出本次范围。

因此，前端文案建议明确：

- `清空非当日（按报文日期）`

如需更强提醒，也可以写成：

- `清空非今日（按 UTC 报文日期）`

### 8.4 前端交互

#### 8.4.1 入口位置

推荐放在报文记录页 Hero 区右上角，和查询面板分离：

- `清理记录`

点击后进入确认弹框，而不是直接执行。

#### 8.4.2 确认弹框内容

弹框中展示：

- 标题：`清理报文记录`
- 说明文案：`该操作会删除 SQLite 中的报文记录，并同步删除对应 payload 文件，无法恢复。`
- 两个危险操作按钮：
  - `清空非当日`
  - `清空全部`
- 一个 `取消`

建议在按钮附近补充静态说明：

- `清空非当日`：保留当前报文日期的数据，删除更早日期的数据。
- `清空全部`：删除所有报文记录和 payload 文件。

#### 8.4.3 执行反馈

执行成功后：

- 关闭确认弹框
- 重置 `state.packetPage = 1`
- 重新加载报文列表
- 在 `packet-message` 或全局轻提示中显示结果，例如：

`已清理 1234 条报文，删除 18 个 payload 文件`

执行失败后：

- 弹框保持打开
- 就地展示错误文案

### 8.5 后端接口设计

建议新增：

`POST /api/packets/purge`

请求体：

```json
{
  "scope": "ALL"
}
```

或：

```json
{
  "scope": "NON_TODAY"
}
```

返回：

```json
{
  "scope": "NON_TODAY",
  "keptDate": "2026-04-17",
  "deletedPackets": 1234,
  "deletedPayloadFiles": 18,
  "deletedPayloadBytes": 10485760
}
```

说明：

- `keptDate` 仅在 `NON_TODAY` 时返回。
- 这里的 `2026-04-17` 只是示例；真实值取服务端清理时计算出的当前报文日期。

### 8.6 存储层实现建议

建议把清理逻辑下沉到 repository / file store，而不是直接写在 `NettyAdminServer`。

推荐新增类型：

- `PacketRepository.PurgeResult`
- `PayloadFileStore.DeleteResult` 或直接复用现有 `RetentionResult` 风格

推荐新增方法：

#### `PacketRepository`

- `purgeAll()`
- `purgeBeforeDate(String keepDateExclusive)`

语义：

- `purgeAll()`：删除所有 packet 行
- `purgeBeforeDate("2026-04-17")`：删除 `received_at` 日期前缀 `< 2026-04-17` 的所有 packet 行

#### `PayloadFileStore`

- `deleteAllSegments()`
- `deleteSegmentsBeforeDate(String keepDateExclusive)`

语义：

- 删除整批 `.seg` 文件及空目录
- 按 payload 目录的日期层级做删除，不碰保留日期目录

### 8.7 一致性与并发

这是本需求里最需要提前想清楚的点。

#### 8.7.1 风险

当前 `PacketCaptureService` 会持续写入：

- SQLite `packet` 表
- payload `.seg` 文件

如果清理和写入并发，可能出现：

- 数据库刚删完，新的 payload 又写进旧目录
- payload 文件已删，但数据库新写入的记录仍引用该文件

#### 8.7.2 建议方案

在 `PacketRepository` 内新增一个“报文写入 / 清理互斥锁”，统一保护：

- `insertBatch()`
- `purgeAll()`
- `purgeBeforeDate(...)`

推荐方式：

- `private final Object packetMutationLock = new Object();`

执行策略：

1. `insertBatch()` 进入锁内后完成 payload 文件写入 + packet 入库。
2. 清理操作进入同一把锁内后完成 packet 删除 + payload 文件删除。

这样可以保证：

- 报文写入与清理不会交叉提交
- `ALL` 清理时不会删掉刚写完但尚未入库的 payload

因为清理是低频管理操作，这种串行化代价可接受。

### 8.8 “清空非当日”的推荐实现

推荐按日期目录整体处理：

1. 计算当前保留日期 `keepDate`
2. 删除 `packet` 表中日期前缀 `< keepDate` 的记录
3. 删除 `data/payload/< keepDate` 之前的日期目录下所有 `.seg`
4. 清理空目录

优点：

- 不需要解析单个 segment 内的 frame
- 不需要重写 segment 文件
- 和当前目录设计天然匹配

### 8.9 “清空全部”的推荐实现

1. 删除 `packet` 表所有记录
2. 删除 `data/payload` 下全部 `.seg` 文件
3. 清理空目录

注意：

- 不删除 SQLite 数据库文件本身
- 不删除 `connection` 记录
- 不动 spill 目录；spill 属于抓包写入队列问题，不属于已落库报文

## 9. 编码落点建议

### 9.1 前端

- [src/main/resources/admin/assets/app.js](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/resources/admin/assets/app.js)
- [src/main/resources/admin/assets/style.css](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/resources/admin/assets/style.css)

建议改动点：

- 重构 `renderMappings()`
- 新增 mapping modal 渲染和状态管理
- 新增 row action 状态机
- 新增 packet purge modal 与调用逻辑
- 补齐状态中文

### 9.2 后端

- [src/main/java/com/admin/NettyAdminServer.java](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/java/com/admin/NettyAdminServer.java)
- [src/main/java/com/store/PacketRepository.java](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/java/com/store/PacketRepository.java)
- [src/main/java/com/store/PayloadFileStore.java](/Users/liuqiwei/IdeaProjects/jmagnifier/src/main/java/com/store/PayloadFileStore.java)

建议改动点：

- 新增 `POST /api/packets/purge`
- 新增 purge DTO 解析
- 新增 packet / payload 清理实现
- 新增 purge 结果返回结构

## 10. 测试建议

### 10.1 后端单测

优先补 repository / file store 级别测试：

- `purgeAll()` 删除全部 packet 行
- `purgeBeforeDate()` 只删除旧日期 packet 行
- `deleteSegmentsBeforeDate()` 只删除旧日期 payload 目录
- 清理后空目录被正确移除

### 10.2 前端手工验证

#### 映射配置页

- `RUNNING` 行只显示 `停止`
- `STOPPED` 行只显示 `启动`
- `FAILED` 行显示 `重试启动`
- `STARTING` / `STOPPING` 行按钮为禁用态
- `新建映射` 和 `编辑映射` 都在弹框内完成
- 保存成功后弹框关闭并刷新列表

#### 报文记录页

- 可以打开 `清理记录` 弹框
- 点击 `清空非当日` 后保留当前报文日期的数据
- 点击 `清空全部` 后列表为空
- 清理成功后提示返回删除条数和 payload 文件数

## 11. 建议实施顺序

1. 先重构映射页前端，把列表与表单解耦。
2. 再补状态驱动按钮和 pending 态。
3. 最后做报文清理后端，再接前端弹框。

原因：

- 映射页改造只动前端，回归面更小。
- 报文清理涉及 SQLite 与 payload 文件一致性，技术风险更高，放在后面更稳妥。

## 12. 验收标准

- 映射列表不再同时展示 `启动` 和 `停止`。
- 新建、编辑映射都通过弹框完成。
- 报文记录支持“清空全部”和“清空非当日”。
- 清理操作不会误删保留日期的 payload 文件。
- 清理与抓包写入之间有明确互斥，避免留下脏引用。
