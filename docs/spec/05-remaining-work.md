# 剩余工作 Spec

本文档记录当前代码基线之后的剩余实现项，并把每一项映射回原始 spec。

当前基线：

- 阶段 0：基础修正和准备已完成。
- 阶段 1：运行时 mapping manager 主体已完成。
- 阶段 2：SQLite schema、mapping / connection 持久化已完成。
- 阶段 3：异步 packet capture、截断、SQLite writer 已完成。
- 阶段 4：队列满 spill 到磁盘和回放已完成。
- 阶段 5 子集：Netty admin server、登录/session、mapping API、`/api/runtime`、connection 查询 API、packet 查询 API、payload 下载、API 错误码和轻量 router 已完成。
- 阶段 6：静态资源服务、登录页、Runtime Summary、Mapping 管理页、Connection / Packet 页面已完成。

## 范围约束

- 继续使用 Netty 管理端，不切换到 Spring Boot Web。
- 继续保持 raw TCP byte forwarding，不实现 HTTP 代理语义。
- Web 修改运行时配置后只写 SQLite，不回写 yml。
- 后续实现应保持 Java 8 兼容。
- 每个阶段完成后仍以 `mvn -DskipTests package` 作为最小编译验收。

## 原始 Spec 映射

| 原始 spec | 本文剩余章节 | 说明 |
| --- | --- | --- |
| `00-overall-design.md` | 全部章节 | 继续保持总体架构：`AppRuntime -> SQLite -> RuntimeMappingManager -> NettyAdminServer -> PacketCaptureService`。 |
| `01-netty-admin-web.md` | Netty Admin API 剩余项、Web UI | 补齐 connection / packet API、payload 下载、页面路由和静态资源。 |
| `02-runtime-mapping.md` | Runtime 运行时硬化、测试 | 补齐错误码、失败状态、连接关闭原因和动态更新验收。 |
| `03-packet-capture-sqlite.md` | Packet 查询 API、数据清理、测试 | 补齐 packet 分页查询、详情预览、payload 下载、可选清理 API。 |
| `04-implementation-plan.md` | 全部章节 | 继续执行阶段 5 剩余 API、阶段 6 UI、阶段 7 清理测试文档。 |

## 1. Netty Admin API 剩余项

来源映射：

- `01-netty-admin-web.md`：Connection API、Packet API、API 通用格式。
- `03-packet-capture-sqlite.md`：Packet 列表、详情、payload 下载。
- `04-implementation-plan.md`：阶段 5 剩余 API。

### 1.1 Connection 查询 API

状态：已完成。

接口：

```text
GET /api/connections
GET /api/connections/{id}
```

任务：

- 新增 connection 查询 repository 方法。
- 支持过滤参数：`mappingId`、`clientIp`、`status`、`from`、`to`、`page`、`pageSize`。
- 默认分页：`page=1`、`pageSize=50`。
- 返回字段对齐 `01-netty-admin-web.md` 的 Connection API。
- `GET /api/connections/{id}` 返回连接详情和最近 packet 摘要。

注释：

- 第一版不需要实时流式刷新。
- 查询连接时不返回 packet payload。
- `bytesUp` / `bytesDown` 使用 packet writer 累加的原始字节数。

验收：

- 未登录访问返回 401。
- 登录后可分页查询。
- 指定 `mappingId` 和 `status` 能正确过滤。

### 1.2 Packet 查询 API

状态：已完成。

接口：

```text
GET /api/packets
GET /api/packets/{id}
GET /api/packets/{id}/payload
```

任务：

- 新增 packet 查询 repository。
- `GET /api/packets` 支持过滤参数：`mappingId`、`connectionId`、`direction`、`from`、`to`、`page`、`pageSize`。
- 列表不返回完整 payload，只返回摘要字段。
- `GET /api/packets/{id}` 返回 metadata、hex preview、escaped text preview。
- `GET /api/packets/{id}/payload` 返回 `application/octet-stream`。

注释：

- text preview 必须 UTF-8 尝试解码，不可见字符替换，并做 HTML escape。
- payload 下载文件名使用 `packet-{id}.bin`。
- `direction` 只接受 `REQUEST` 或 `RESPONSE`。

验收：

- 大 payload 只返回截断后的 captured payload。
- 列表接口不包含 payload BLOB。
- payload 下载响应头包含 octet-stream 和下载文件名。

### 1.3 API 错误码和路由硬化

状态：已完成。

来源映射：

- `01-netty-admin-web.md`：API 通用格式。
- `02-runtime-mapping.md`：端口管理错误码。

任务：

- 把当前通用 `RuntimeException` 映射为稳定错误码。
- 至少覆盖：
  - `INVALID_PORT`
  - `INVALID_FORWARD_HOST`
  - `PORT_ALREADY_CONFIGURED`
  - `BIND_FAILED`
  - `MAPPING_NOT_FOUND`
  - `BAD_REQUEST`
  - `UNAUTHORIZED`
- 拆分当前 admin handler 中的路由逻辑，避免单类继续膨胀。

注释：

- 不需要引入外部 router 框架。
- 错误响应继续保持：

```json
{
  "success": false,
  "error": {
    "code": "CODE",
    "message": "message"
  }
}
```

验收：

- 端口冲突返回可识别错误码。
- 无效 JSON 返回 `BAD_REQUEST`。
- 不存在的 mapping 返回 `MAPPING_NOT_FOUND`。

## 2. Web UI

来源映射：

- `01-netty-admin-web.md`：页面路由、Web UI 第一版页面。
- `04-implementation-plan.md`：阶段 6。

### 2.1 静态资源服务

状态：已完成。

任务：

- 实现 `/assets/*` 静态资源处理。
- 静态资源从 classpath 打包进 jar。
- 页面请求未登录时 302 到 `/login`。
- API 请求未登录时保持 401 JSON。

注释：

- 继续使用 Netty，不引入 Spring MVC 或 Spring Boot Web。
- 第一版可以使用简单 HTML + JavaScript，不要求 SPA 框架。

验收：

- jar 中包含静态资源。
- 浏览器直接访问 `/` 未登录会跳转 `/login`。

### 2.2 登录页

状态：已完成。

任务：

- 新增 `/login` 页面。
- 表单提交到 `POST /api/login`。
- 登录成功后跳转 `/`。
- 支持 logout。

注释：

- cookie 已由 API 设置 `HttpOnly`。
- 非 HTTPS 场景暂不设置 `Secure`，但 README 需要说明不要公网暴露。

验收：

- 密码错误显示失败状态。
- 登录后刷新页面仍保持 session。

### 2.3 首页 Runtime Summary

状态：已完成。

任务：

- 首页调用 `/api/runtime`。
- 展示：
  - mapping 总数
  - running / stopped / failed 数量
  - 当前活跃连接数
  - capture queue 当前长度
  - spill 文件数量和字节数
  - packets written / spilled / dropped

注释：

- 今日 packet 数量当前 repository 尚未实现，可在 packet 查询 repository 中补 count 后接入。

验收：

- 数据来自 API，不写死。
- capture / spill 指标能随运行变化刷新。

### 2.4 Mapping 管理页面

状态：已完成。

任务：

- 列表展示所有 mapping 和 runtime 状态。
- 新增 mapping。
- 编辑 mapping。
- start / stop。
- delete。
- 显示 `lastError`。

注释：

- 修改 mapping 继续走直接重启端口策略。
- 删除继续软删除，保留历史 connection / packet 关联。

验收：

- 页面可以完成 mapping CRUD。
- 修改 running mapping 后旧端口释放，新端口生效。

### 2.5 Connection 和 Packet 页面

状态：已完成。

任务：

- connection 列表页调用 `/api/connections`。
- packet 列表页调用 `/api/packets`。
- packet 详情页展示 hex preview 和 escaped text preview。
- payload 下载按钮调用 `/api/packets/{id}/payload`。

注释：

- payload 是不可信任数据，页面必须使用 escaped text。
- 列表页不要请求完整 payload。

验收：

- 可按 mapping / connection / direction 过滤 packet。
- 二进制 payload 不导致页面脚本执行。

## 3. Runtime 运行时硬化

来源映射：

- `02-runtime-mapping.md`：端口管理、连接生命周期、并发控制。
- `04-implementation-plan.md`：主要风险。

任务：

- 明确 mapping start 失败后 runtime status 保持 `FAILED`，API 返回失败和 `lastError`。
- stop / update / delete 时关闭原因写入 connection 表：
  - `MAPPING_STOPPED`
  - `MAPPING_UPDATED`
  - `MAPPING_DELETED`
  - `REMOTE_CLOSED`
  - `LOCAL_CLOSED`
  - `ERROR`
- update mapping 时使用 `MAPPING_UPDATED` 关闭旧连接。
- delete mapping 时使用 `MAPPING_DELETED` 关闭旧连接。
- 区分应用内部端口冲突和系统 bind 失败。

注释：

- 当前已具备基本 stop/update/delete 能力，但关闭原因还需要更细。
- 当前 runtime restore 已能容忍单个 mapping 启动失败；API 错误响应仍需细化。

验收：

- 删除 mapping 后历史 connection / packet 仍可通过 `mapping_id` 查询。
- 系统端口已被其他进程占用时返回 `BIND_FAILED`。

## 4. Packet 存储和清理扩展

来源映射：

- `03-packet-capture-sqlite.md`：查询和展示、数据保留。

任务：

- 实现 packet count 查询，用于首页今日 packet 数量。
- 实现可选手动清理 API：

```text
DELETE /api/packets?before=...
DELETE /api/mappings/{id}/packets
```

注释：

- 自动保留策略不是最小可交付要求，可以继续暂缓。
- 清理 API 需要认证。

验收：

- 清理指定时间前 packet 后，列表和 count 同步变化。
- 清理某 mapping 的 packet 不删除 mapping / connection 记录。

## 5. 测试、构建和文档

来源映射：

- `04-implementation-plan.md`：阶段 7。

### 5.1 Maven Wrapper

任务：

- 添加 Maven Wrapper。
- README 中统一使用 `./mvnw`。

注释：

- 当前本机使用 IntelliJ 自带 Maven 验证，不适合作为长期约定。

验收：

- `./mvnw -DskipTests package` 成功。

### 5.2 单元测试

任务：

- 固定并隔离不阻塞的测试。
- 覆盖：
  - mapping 校验。
  - yml 默认值补齐。
  - packet 截断。
  - spill 文件读写。
  - SQLite repository。
  - admin auth session。

注释：

- 当前 `AppStartTest` 会启动服务并阻塞，不应作为常规 `mvn test` 的基础。

验收：

- 新增测试可以独立运行。
- 不依赖固定端口。

### 5.3 集成测试

任务：

- 使用 ephemeral listen port。
- 使用本地 echo server 作为 forward target。
- 验证 request / response packet 入库。
- 验证 mapping update 会重启端口。
- 验证 stop / delete 会关闭活跃连接。

注释：

- 集成测试必须包含 deterministic shutdown。

验收：

- 集成测试不会永久阻塞。
- 不污染默认 `./data` 目录。

### 5.4 README 更新

任务：

- 更新启动方式。
- 更新 yml 配置示例。
- 说明 admin 默认地址、默认密码和环境变量覆盖。
- 说明 SQLite 路径、spill 路径、capture 配置。
- 说明不要把 admin 端口暴露到公网。

验收：

- README 不再使用旧的顶层 `listenPort` / `forwardPort` 配置示例。
- README 能覆盖 jar 运行、Web 登录、mapping 管理和 packet 查看。

## 推荐剩余开发顺序

1. 补 runtime 关闭原因和端口错误分类。
2. 补 packet 清理 API。
3. 添加 Maven Wrapper。
4. 增加单元测试和集成测试。
5. 更新 README。

## 最小可交付剩余清单

最小可交付还缺：

- README 更新。

可继续暂缓：

- 自动数据保留。
- 实时报文流。
- payload 搜索。
- 复杂图表。
- HTTP 协议解析。
