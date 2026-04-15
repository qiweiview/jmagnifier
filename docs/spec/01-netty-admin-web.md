# Netty Web 管理端 Spec

## 目标

使用 Netty 实现轻量 Web 管理端，提供：

- 登录页和简单密码认证。
- mapping 管理 API。
- connection / packet 查询 API。
- 静态 Web UI。

不引入 Spring Boot Web。

## 监听配置

管理端配置：

```yaml
admin:
  host: "127.0.0.1"
  port: 8080
  password: "admin"
  sessionTimeoutMinutes: 720
```

默认绑定 `127.0.0.1`。如果用户显式配置 `0.0.0.0`，启动日志必须提示管理端暴露风险。

## Netty pipeline

推荐 pipeline：

```text
HttpServerCodec
HttpObjectAggregator
ChunkedWriteHandler
AdminAccessLogHandler
AdminAuthHandler
HttpRouterHandler
```

说明：

- `HttpObjectAggregator` 限制请求体大小，默认 1MB。
- API 只接受 JSON body。
- 静态资源走 classpath resource 或文件目录。
- 认证 handler 应在路由前执行。

## 路由

### 页面路由

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/login` | 登录页 |
| GET | `/` | 管理首页 |
| GET | `/mappings` | mapping 页面 |
| GET | `/connections` | 连接页面 |
| GET | `/packets` | 报文页面 |
| GET | `/assets/*` | 静态资源 |

页面可以第一版先做单页应用，也可以使用简单 HTML + JavaScript。

### 认证 API

| Method | Path | 说明 |
| --- | --- | --- |
| POST | `/api/login` | 提交密码，成功后设置 session cookie |
| POST | `/api/logout` | 清除 session |
| GET | `/api/me` | 返回当前登录状态 |

登录请求：

```json
{
  "password": "admin"
}
```

登录成功：

```json
{
  "success": true
}
```

## 认证设计

第一版使用简单密码 + 内存 session：

- 密码来自 yml、环境变量或启动参数。
- 推荐支持环境变量覆盖：`JMAGNIFIER_ADMIN_PASSWORD`。
- session id 使用 `SecureRandom` 生成。
- session 存在内存 map 中。
- cookie 名称：`JMAGNIFIER_SESSION`。
- 默认 cookie `HttpOnly`。
- 如果管理端不是 HTTPS，不设置 `Secure`，但文档中注明不要公网暴露。

需要认证的范围：

- 除 `/login`、`/api/login`、`/assets/login/*` 外，其他页面和 API 都需要认证。

失败响应：

- 页面请求未登录：302 到 `/login`。
- API 请求未登录：401 JSON。

## API 通用格式

成功：

```json
{
  "success": true,
  "data": {}
}
```

失败：

```json
{
  "success": false,
  "error": {
    "code": "PORT_IN_USE",
    "message": "listen port is already in use"
  }
}
```

分页：

```json
{
  "success": true,
  "data": {
    "items": [],
    "page": 1,
    "pageSize": 50,
    "total": 123
  }
}
```

## Mapping API

### `GET /api/mappings`

返回所有 mapping 和运行状态。

字段：

```json
{
  "id": 1,
  "name": "r1",
  "enabled": true,
  "listenPort": 9300,
  "forwardHost": "example.com",
  "forwardPort": 80,
  "status": "RUNNING",
  "activeConnections": 2,
  "lastError": null,
  "createdAt": "2026-04-16T10:00:00+08:00",
  "updatedAt": "2026-04-16T10:00:00+08:00"
}
```

### `POST /api/mappings`

创建并启动 mapping。

请求：

```json
{
  "name": "r1",
  "enabled": true,
  "listenPort": 9300,
  "forwardHost": "example.com",
  "forwardPort": 80
}
```

### `PUT /api/mappings/{id}`

修改 mapping。行为固定为：

1. 停止旧 listener。
2. 关闭旧连接。
3. 更新 SQLite。
4. 如果 `enabled=true`，按新配置启动 listener。

### `POST /api/mappings/{id}/start`

启用并启动 mapping。

### `POST /api/mappings/{id}/stop`

停止 mapping，关闭 listener 和活跃连接。

### `DELETE /api/mappings/{id}`

删除 mapping：

1. 停止 listener。
2. 关闭活跃连接。
3. 删除 mapping 或标记 deleted。

建议第一版使用软删除，便于保留历史 connection / packet 的 mapping 关联。

## Connection API

### `GET /api/connections`

查询参数：

| 参数 | 说明 |
| --- | --- |
| `mappingId` | 可选 |
| `clientIp` | 可选 |
| `status` | 可选 |
| `from` | 可选，开始时间 |
| `to` | 可选，结束时间 |
| `page` | 默认 1 |
| `pageSize` | 默认 50 |

返回字段：

```json
{
  "id": 100,
  "mappingId": 1,
  "clientIp": "127.0.0.1",
  "clientPort": 52000,
  "listenPort": 9300,
  "forwardHost": "example.com",
  "forwardPort": 80,
  "status": "CLOSED",
  "openedAt": "2026-04-16T10:00:00+08:00",
  "closedAt": "2026-04-16T10:01:00+08:00",
  "bytesUp": 120,
  "bytesDown": 2048
}
```

### `GET /api/connections/{id}`

返回连接详情和最近报文摘要。

## Packet API

### `GET /api/packets`

查询参数：

| 参数 | 说明 |
| --- | --- |
| `mappingId` | 可选 |
| `connectionId` | 可选 |
| `direction` | `REQUEST` 或 `RESPONSE` |
| `from` | 可选 |
| `to` | 可选 |
| `page` | 默认 1 |
| `pageSize` | 默认 50 |

列表不直接返回完整 payload，只返回摘要：

```json
{
  "id": 200,
  "connectionId": 100,
  "mappingId": 1,
  "direction": "REQUEST",
  "clientIp": "127.0.0.1",
  "clientPort": 52000,
  "targetHost": "example.com",
  "targetPort": 80,
  "payloadSize": 8192,
  "capturedSize": 4096,
  "truncated": true,
  "receivedAt": "2026-04-16T10:00:01+08:00"
}
```

### `GET /api/packets/{id}`

返回 metadata 和 payload 的文本/hex 预览。

### `GET /api/packets/{id}/payload`

下载 captured payload：

- Content-Type: `application/octet-stream`
- 文件名：`packet-{id}.bin`

## Web UI 第一版页面

### 首页

展示：

- mapping 总数。
- running / stopped / failed 数量。
- 当前活跃连接数。
- 今日 packet 数量。
- capture queue 当前长度。
- spill 文件数量。

### Mapping 页面

功能：

- 列表展示。
- 新增 mapping。
- 编辑 mapping。
- 启动 / 停止。
- 删除。
- 显示 last error。

表单校验：

- `listenPort` 必须是 `0..65535`。
- `forwardPort` 必须是 `0..65535`。
- `forwardHost` 非空。
- `name` 非空。

### Packet 页面

功能：

- 按条件分页查询。
- 展示方向、时间、长度、截断标记、地址信息。
- 详情弹窗展示 text view 和 hex view。
- 下载 payload。

安全要求：

- text view 必须 escape。
- 不把 payload 当 HTML 插入 DOM。

## 实现建议

### 路由实现

不要在 handler 中写大量 if-else。建议引入轻量内部路由表：

```text
RouteKey(method, pathPattern) -> RouteHandler
```

支持：

- 精确路径。
- `{id}` 路径参数。
- `/assets/*` 通配。

### JSON

项目已有 Jackson，可用 `ObjectMapper`：

- API 请求 body 反序列化为 DTO。
- 响应统一序列化。
- 时间统一 ISO-8601 字符串。

### 静态资源

第一版可以将前端资源放在：

```text
src/main/resources/admin/
  index.html
  login.html
  app.js
  style.css
```

注意当前 `pom.xml` resources 只包含 css/xml，需要改为包含 admin 静态资源和必要配置。

