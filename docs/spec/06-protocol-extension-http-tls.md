# 协议扩展 Spec

## 背景

当前 jmagnifier 已具备以下能力：

- 基于 Netty 的 `tcp -> tcp` 字节级双向转发。
- 基于 `ByteReadHandler` 的原始请求/响应打印和 packet capture。
- 运行时 mapping 管理、SQLite 持久化、管理端 API/UI。

后续明确会涉及以下链路：

- `tcp -> tcp`
- `http -> http`
- `http -> https`
- `https -> http`
- `https -> https`

目标是在尽量复用现有运行时骨架的前提下，补齐 HTTP/TLS 场景能力，避免为 HTTP/HTTPS 单独重建一套监听、连接、管理、记录系统。

## 结论摘要

结论采用混合架构，而不是把所有流量都继续视为 raw bytes，也不是把所有链路都强制改成 HTTP 代理：

1. 保留现有 raw TCP 路径，继续服务 `tcp -> tcp` 和未来其他二进制协议。
2. 对明确是 HTTP/HTTPS 的 mapping，使用 Netty 原生 `HttpServerCodec`、`HttpClientCodec`、`SslHandler` 组装专用 pipeline。
3. `DataReceiver`、`TCPForWardContext`、`RuntimeMappingManager`、`PacketCaptureService`、admin/store 继续复用。
4. `ByteReadHandler` 只负责 raw TCP path，不承载 HTTP 语义。
5. 新增协议层抽象，由 mapping 决定本地端和远端端的传输层与应用层组合。

一句话总结：共享运行时骨架，按 mapping 选择 raw pipeline 或 HTTP/TLS pipeline。

## 设计目标

### 目标

- 支持五类链路：
  - `tcp -> tcp`
  - `http -> http`
  - `http -> https`
  - `https -> http`
  - `https -> https`
- 对 HTTP/HTTPS 链路使用 Netty 官方 codec / handler，避免手工解析协议。
- 保持 Java 8 兼容。
- 不破坏现有 mapping 生命周期、SQLite 管理和 packet capture 主流程。
- 保证配置和持久化向后兼容已有 raw TCP mapping。
- 为后续 header 改写、HTTP 结构化日志、请求过滤、重放留出扩展点。

### 非目标

第一阶段不做以下内容：

- 不实现通用 HTTPS MITM 抓包平台，不动态签发任意目标站点证书。
- 不实现 HTTP/2、WebSocket、CONNECT tunnel。
- 不实现完整流式 body 改写引擎。
- 不把 HTTP 代理语义反向套用到 raw TCP mapping。
- 不保证 V1 就覆盖超大文件上传下载的零拷贝最佳路径。

说明：

- `https -> http` 与 `https -> https` 中的“本地 HTTPS”是本地终止 TLS，不等价于透明 MITM。
- 客户端必须信任本地监听端使用的证书，或该证书链可被客户端接受。

## 支持链路矩阵

| 本地监听 | 远端连接 | 应用层 | 本地 TLS | 远端 TLS | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tcp` | `tcp` | raw | 否 | 否 | 继续走现有 raw path |
| `http` | `http` | HTTP/1.1 | 否 | 否 | 明文 HTTP 代理 |
| `http` | `https` | HTTP/1.1 | 否 | 是 | 明文接入、远端 TLS 发出 |
| `https` | `http` | HTTP/1.1 | 是 | 否 | 本地 TLS 终止，远端明文 |
| `https` | `https` | HTTP/1.1 | 是 | 是 | 本地 TLS 终止，远端重新建立 TLS |

约束：

- 本 spec 的 `https` 指 HTTP over TLS，而不是任意 TLS 上层协议。
- 对任意非 HTTP 二进制协议，如后续需要 `tcp -> tls`，可在 raw path 上扩展，但不纳入本 spec 首阶段。

## 总体架构

继续复用现有运行时主干：

```text
AppRuntime
  -> RuntimeMappingManager
       -> MappingRuntime
            -> DataReceiver (listen side)
            -> TCPForWardContext (remote side)
  -> PacketCaptureService
  -> MappingRepository / ConnectionRepository / PacketRepository
  -> NettyAdminServer
```

新增协议层：

```text
Mapping
  -> ProtocolProfile
       -> listen endpoint spec
       -> forward endpoint spec
       -> http proxy options

DataReceiver / TCPForWardContext
  -> ProtocolPipelineFactory
       -> RawTcpPipelineAssembler
       -> HttpProxyPipelineAssembler
       -> SslContextFactory
```

原则：

- 生命周期、线程模型、连接管理复用现有类。
- pipeline 组装逻辑从 `DataReceiver` / `TCPForWardContext` 中抽离。
- raw 与 HTTP 走两套 handler，但共享同一套 mapping/runtime/capture/store 管理。

## 配置模型

### 目标配置结构

`Mapping` 扩展为显式的监听端和远端端配置。建议结构：

```yaml
mappings:
  - name: "demo-http-to-https"
    enable: true

    listen:
      port: 9300
      applicationProtocol: "http"   # tcp / http
      tls:
        enabled: false

    forward:
      host: "api.example.com"
      port: 443
      applicationProtocol: "http"   # tcp / http
      tls:
        enabled: true
        sniHost: "api.example.com"
        insecureSkipVerify: false
        trustCertCollectionFile: null

    http:
      rewriteHost: true
      addXForwardedHeaders: true
      maxObjectSizeBytes: 1048576
```

`https -> https` 示例：

```yaml
mappings:
  - name: "demo-https-to-https"
    enable: true
    listen:
      port: 9443
      applicationProtocol: "http"
      tls:
        enabled: true
        certificateFile: "/path/server.crt"
        privateKeyFile: "/path/server.key"
        privateKeyPassword: null
    forward:
      host: "upstream.example.com"
      port: 443
      applicationProtocol: "http"
      tls:
        enabled: true
        sniHost: "upstream.example.com"
        insecureSkipVerify: false
    http:
      rewriteHost: true
      addXForwardedHeaders: true
      maxObjectSizeBytes: 1048576
```

### 字段说明

#### `listen.applicationProtocol`

- `tcp`：使用 raw path。
- `http`：使用 HTTP server codec path。

#### `listen.tls.enabled`

- `false`：本地明文监听。
- `true`：本地监听端先做 TLS 握手，再处理 HTTP message。

#### `forward.applicationProtocol`

- `tcp`：使用 raw outbound path。
- `http`：使用 HTTP client codec path。

#### `forward.tls.enabled`

- `false`：远端明文连接。
- `true`：远端连接先加 `SslHandler`，再处理 HTTP message。

#### `http.rewriteHost`

- `true`：把请求 `Host` 改成 `forward.host[:port]`。
- `false`：保留客户端原始 `Host`。

#### `http.addXForwardedHeaders`

- `true`：增加或补齐 `X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Port`。
- `false`：不额外写入。

#### `http.maxObjectSizeBytes`

- 仅用于 V1 HTTP 聚合模式。
- 防止 `HttpObjectAggregator` 无界占用内存。

### 向后兼容

现有 mapping 仍兼容：

```yaml
- name: "legacy"
  enable: true
  listenPort: 9300
  forwardHost: "example.com"
  forwardPort: 80
```

兼容规则：

1. 如果 `listen` / `forward` 未配置，则按 legacy raw TCP 方式解释：
   - `listen.applicationProtocol = tcp`
   - `listen.tls.enabled = false`
   - `forward.applicationProtocol = tcp`
   - `forward.tls.enabled = false`
2. `listen.port` 与 legacy `listenPort` 含义一致。
3. `forward.host` / `forward.port` 与 legacy `forwardHost` / `forwardPort` 含义一致。

## 持久化设计

当前 `mapping` 表只有基础字段，无法覆盖 TLS/HTTP 选项。为避免每次扩展都做大规模 schema 变更，采用“基础列 + 扩展 JSON”方案。

### SQLite 变更

在 `mapping` 表增加：

```text
config_json TEXT NULL
```

规则：

- `name`、`enabled`、`listen_port`、`forward_host`、`forward_port` 继续保留，用于列表、过滤和向后兼容。
- 完整 Mapping 配置序列化到 `config_json`。
- repository 读取时：
  - 若 `config_json` 非空，则反序列化为完整 `Mapping`。
  - 若为空，则按 legacy 字段构建 `Mapping` 并补默认值。
- repository 写入时：
  - 同时更新基础列和 `config_json`。

这样后续新增 HTTP/TLS 选项时，不需要频繁增列。

## 运行时对象与职责

### 保留现有职责

#### `DataReceiver`

继续负责：

- 监听本地端口。
- 接受本地连接。
- 创建 `ConnectionContext`。
- 启动对应的 `TCPForWardContext`。

变化：

- 不再硬编码 `pipeline.addLast(ByteReadHandler.NAME, byteReadHandler)`。
- 改为委托给 `ProtocolPipelineFactory` 组装本地 pipeline。

#### `TCPForWardContext`

继续负责：

- 建立远端连接。
- 持有 remote channel。
- 负责连接关闭传播。

变化：

- 不再硬编码远端 pipeline。
- 改为委托给 `ProtocolPipelineFactory` 组装远端 pipeline。

#### `ByteReadHandler`

继续只负责 raw TCP：

- 读取 `ByteBuf -> byte[]`
- 调用 capture / print consumer
- 转发到绑定对端

约束：

- 禁止把 HTTP header 改写、HTTP message 聚合等逻辑塞入 `ByteReadHandler`。
- 它只服务 `tcp -> tcp` 及未来 raw/tls 二进制链路。

### 新增对象

#### `ProtocolPipelineFactory`

职责：

- 根据 mapping 判断链路类型。
- 组装本地与远端 pipeline。
- 返回 raw 或 HTTP 专用 handler 所需的组件。

建议接口：

```java
public interface ProtocolPipelineFactory {
    void initListenPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge);
    void initForwardPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge);
}
```

#### `ProtocolBridge`

职责：

- 表示一次 connection 的协议桥接对象。
- raw path 与 HTTP path 使用不同实现。

建议实现：

- `RawTcpBridge`
- `HttpProxyBridge`

#### `SslContextFactory`

职责：

- 为本地监听端生成 server-side `SslContext`。
- 为远端连接端生成 client-side `SslContext`。

要求：

- 支持 client TLS 的 SNI。
- 支持 server TLS 的证书/私钥文件。
- 对 `insecureSkipVerify` 提供显式开关，默认关闭。

#### `HttpFrontendHandler` / `HttpBackendHandler`

职责：

- 处理本地入站 HTTP message。
- 处理远端出站/回流 HTTP message。
- 维护 request-response 关联关系。
- 在代理前后做 header 改写与 capture。

说明：

- 可按实现便利性命名为一个 bridge + 两个 channel handler。
- 关键是保持 HTTP 语义处理与 raw path 分离。

## pipeline 设计

### 1. `tcp -> tcp`

复用现有路径：

```text
listen pipeline:
  ByteReadHandler(local)

forward pipeline:
  ByteReadHandler(remote)
```

### 2. `http -> http`

```text
listen pipeline:
  HttpServerCodec
  HttpObjectAggregator(maxObjectSizeBytes)   # V1
  HttpFrontendHandler

forward pipeline:
  HttpClientCodec
  HttpObjectAggregator(maxObjectSizeBytes)   # V1
  HttpBackendHandler
```

### 3. `http -> https`

```text
listen pipeline:
  HttpServerCodec
  HttpObjectAggregator(maxObjectSizeBytes)
  HttpFrontendHandler

forward pipeline:
  SslHandler(client)
  HttpClientCodec
  HttpObjectAggregator(maxObjectSizeBytes)
  HttpBackendHandler
```

### 4. `https -> http`

```text
listen pipeline:
  SslHandler(server)
  HttpServerCodec
  HttpObjectAggregator(maxObjectSizeBytes)
  HttpFrontendHandler

forward pipeline:
  HttpClientCodec
  HttpObjectAggregator(maxObjectSizeBytes)
  HttpBackendHandler
```

### 5. `https -> https`

```text
listen pipeline:
  SslHandler(server)
  HttpServerCodec
  HttpObjectAggregator(maxObjectSizeBytes)
  HttpFrontendHandler

forward pipeline:
  SslHandler(client)
  HttpClientCodec
  HttpObjectAggregator(maxObjectSizeBytes)
  HttpBackendHandler
```

### 关于 `HttpObjectAggregator`

V1 采用聚合模式，原因：

- 能快速获得 `FullHttpRequest` / `FullHttpResponse`，便于正确改写 Host、记录和调试。
- 编码复杂度明显低于流式 `HttpContent` 桥接。

但必须满足以下约束：

- `maxObjectSizeBytes` 必须可配置，默认建议 `1 MiB`。
- 超限请求/响应必须明确返回错误并记录原因。
- `HttpObjectAggregator` 只作为 V1 方案，后续允许替换为流式桥接，不影响 `RuntimeMappingManager` 和 `DataReceiver` 外部接口。

## HTTP 语义处理规则

### 请求方向

对 `http -> http`、`http -> https`、`https -> http`、`https -> https`：

1. 保留 method、uri、version。
2. `rewriteHost=true` 时，改写 `Host` 为远端 host/port。
3. `addXForwardedHeaders=true` 时，写入：
   - `X-Forwarded-For`
   - `X-Forwarded-Proto`
   - `X-Forwarded-Port`
4. 默认移除或重置 hop-by-hop headers：
   - `Connection`
   - `Proxy-Connection`
   - `Keep-Alive`
   - `Transfer-Encoding`
   - `Upgrade`
5. body 原样透传，不在 V1 做内容改写。

### 响应方向

1. 保留 status、version、body。
2. 默认移除或重置 hop-by-hop headers。
3. 不在 V1 做 gzip 解压、chunk 重写、body 改写。

### keep-alive

V1 可采用“单本地连接对应单远端连接”的方式，保持与现有连接模型一致。

要求：

- HTTP keep-alive 可复用同一对 channel 处理多次请求/响应。
- 但不实现跨客户端连接池复用。

### 错误返回

对于 HTTP path：

- 本地 pipeline 解析失败：返回 `400 Bad Request`，并关闭本地连接。
- 远端连接失败：返回 `502 Bad Gateway`。
- TLS 握手失败：返回 `502 Bad Gateway` 或直接关闭，并记录 handshake error。
- 请求超过 `maxObjectSizeBytes`：返回 `413 Payload Too Large`。

## capture 与日志

### raw path

保持当前行为：

- capture 原始字节。
- 控制台打印 `new String(bytes)` 的行为后续可继续优化，但不属于本 spec 核心。

### HTTP path

对 HTTP 链路，capture 点改为“应用层明文视角”：

- `http -> http`：直接 capture HTTP 明文。
- `http -> https`：在 TLS 加密前 capture 请求，在 TLS 解密后 capture 响应。
- `https -> http`：在本地 TLS 解密后 capture 请求，在写给本地前 capture 响应。
- `https -> https`：在两侧 TLS 中间 capture HTTP 明文。

这样管理端看到的是 HTTP 明文，而不是 TLS 密文。

### PacketEvent 扩展建议

建议为 `PacketEvent` 增加以下可选字段：

```text
protocol_family      # TCP / HTTP
application_protocol # raw / http1
content_type
http_method
http_uri
http_status
```

要求：

- raw path 可不填这些字段。
- HTTP path 在列表页可直接展示 `method uri status`，提升可读性。

### 日志

建议 HTTP path 打印摘要而非整个 body：

```text
[http][request] GET /api/users Host=api.example.com size=123
[http][response] 200 OK Content-Type=application/json size=456
```

默认不在控制台完整打印大 body。

## admin 与 UI 影响

管理端 mapping 表单需要扩展：

### 新增字段

- 监听协议：`tcp` / `http`
- 监听 TLS 开关
- 监听证书路径、私钥路径、私钥密码
- 转发协议：`tcp` / `http`
- 转发 TLS 开关
- SNI Host
- `rewriteHost`
- `addXForwardedHeaders`
- `maxObjectSizeBytes`

### 表单规则

- `listen.applicationProtocol = tcp` 时隐藏 HTTP 选项。
- `listen.tls.enabled = true` 时必须提供服务端证书与私钥。
- `forward.tls.enabled = true` 时允许填写 `sniHost`。
- `http` 链路不允许一端是 `http`、另一端是 `tcp`，除非后续单独定义协议转换规则。

### 列表展示

mapping 列表建议新增：

- `listen mode`，如 `http` / `https`
- `forward mode`，如 `http` / `https`

例如：

```text
9300 http  -> https api.example.com:443
9443 https -> https upstream.example.com:443
```

## 编码边界与包结构建议

建议新增包：

```text
com.protocol
com.protocol.raw
com.protocol.http
com.protocol.tls
```

职责建议：

- `com.protocol`：抽象接口和配置对象
- `com.protocol.raw`：raw TCP bridge，复用 `ByteReadHandler`
- `com.protocol.http`：HTTP proxy handler、header policy、request/response capture
- `com.protocol.tls`：`SslContextFactory`

约束：

- 不把协议分流逻辑散落在 `DataReceiver` 和 `TCPForWardContext` 的匿名内部类里。
- 协议判断集中在 `ProtocolPipelineFactory`。

## 分阶段实施计划

### 阶段 1：配置与持久化扩展

目标：

- `Mapping` 扩展 `listen` / `forward` / `http` 结构。
- 增加 `config_json` 持久化。
- 兼容 legacy raw TCP mapping。

验收：

- 旧配置能正常读取并启动 raw TCP。
- 新配置可在 API 和 SQLite 中保存、读取、更新。

### 阶段 2：抽离 pipeline 工厂

目标：

- 新增 `ProtocolPipelineFactory`。
- `DataReceiver` / `TCPForWardContext` 只负责连接生命周期，不负责硬编码 pipeline。
- raw TCP 行为保持不变。

验收：

- `tcp -> tcp` 行为与当前版本一致。
- `mvn -DskipTests package` 可通过。

### 阶段 3：支持 `http -> http` 与 `http -> https`

目标：

- 引入 `HttpServerCodec` / `HttpClientCodec` / `SslHandler(client)`。
- 支持 Host 改写和 `X-Forwarded-*`。
- HTTP 明文 capture 可用。

验收：

- `http -> http` 可成功访问本地 echo/http 服务。
- `http -> https` 可成功访问 TLS upstream。
- 远端基于 Host 路由的服务可工作。

### 阶段 4：支持 `https -> http` 与 `https -> https`

目标：

- 引入本地 server-side TLS。
- 增加证书/私钥配置。
- 在本地 TLS 终止后继续 HTTP 代理。

验收：

- 客户端信任本地证书时，请求可成功透传。
- `https -> https` 链路下管理端可看到 HTTP 明文 capture。

### 阶段 5：管理端与查询增强

目标：

- admin mapping 表单支持协议/TLS 字段。
- packet 列表展示 HTTP 摘要字段。
- runtime summary 增加协议分类统计可选项。

验收：

- Web 可新增、编辑、启停 HTTP/HTTPS mapping。
- packet 列表可以直观看到 HTTP method/status。

## 风险与取舍

### 1. 聚合模式的内存风险

风险：

- 大 body 会占用较多内存。

取舍：

- V1 用聚合换实现速度。
- 必须加 `maxObjectSizeBytes` 上限和失败处理。
- 后续如需大文件代理，再做流式 `HttpContent` 桥接。

### 2. TLS 证书运维复杂度

风险：

- `https -> http` / `https -> https` 需要本地证书和客户端信任链。

取舍：

- V1 只支持显式证书配置，不做动态 CA 签发。

### 3. capture 语义从 wire bytes 变为 application message

风险：

- HTTP path 的 capture 与 raw path 不同，不能简单按“网络层原始字节”理解。

取舍：

- 对 HTTP/HTTPS 场景，明文可观测性比保留 TLS 密文更有价值。
- UI 与文档需要明确标注 capture 的协议语义。

### 4. handler 边界混乱

风险：

- 若把 HTTP 改写逻辑塞到现有 `ByteReadHandler`，会导致 raw 与 HTTP 耦合、难以演进。

取舍：

- 明确规定 `ByteReadHandler` 只负责 raw path。
- HTTP path 新增专用 bridge/handler。

## 测试建议

不要使用当前会阻塞的 `AppStartTest` 做主验证。优先补以下可自动化测试：

1. `Mapping` 新旧配置兼容解析测试。
2. `MappingRepository` 的 `config_json` 读写测试。
3. `ProtocolPipelineFactory` 模式选择测试。
4. `http -> http` 集成测试：
   - 本地启动 mapping
   - 远端使用本地 HTTP echo server
   - 校验 Host 改写和响应透传
5. `http -> https` 集成测试：
   - 远端使用自签名 TLS server
   - 配置 trust cert
   - 校验 SNI 与 TLS 握手
6. `https -> http` 集成测试：
   - 本地 mapping 使用测试证书
   - Java HTTP client 信任该证书
   - 校验 TLS termination 后可转明文
7. `https -> https` 集成测试：
   - 双侧 TLS
   - 校验 packet capture 中可看到 HTTP 摘要字段

## 对后续 Codex 编码的约束

1. 先做配置和 pipeline 抽象，再做 HTTP path，不要直接在现有匿名 `ChannelInitializer` 里堆分支。
2. raw TCP 行为必须保持兼容，已有 `tcp -> tcp` 不得回归。
3. 任何 HTTP 相关逻辑不得写入 `ByteReadHandler`。
4. 所有新配置都必须支持：
   - yml 读取
   - SQLite 持久化
   - admin API 读写
5. 阶段 3 完成前，不要提前承诺 HTTP/2、WebSocket、CONNECT。
6. 每个阶段完成后，以 `mvn -DskipTests package` 作为最小验收。

## 验收总览

完成本 spec 的最小验收线：

- legacy `tcp -> tcp` 行为不变。
- 可通过配置创建 `http -> http` mapping。
- 可通过配置创建 `http -> https` mapping。
- 可通过配置创建 `https -> http` mapping。
- 可通过配置创建 `https -> https` mapping。
- HTTP mapping 支持 Host 改写。
- HTTPS mapping 支持本地 TLS 终止和远端 TLS 发起。
- 管理端能查看并编辑新增协议/TLS 字段。
- packet capture 对 HTTP/HTTPS 链路可展示 HTTP 明文摘要。
