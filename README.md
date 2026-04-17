# JMagnifier

一个基于 Java 8 和 Netty 的轻量级流量转发与抓包工具，支持原始 TCP 转发、HTTP/HTTPS 代理转发、SQLite 持久化以及内置管理控制台。  
A lightweight traffic forwarding and packet capture tool built with Java 8 and Netty. It supports raw TCP forwarding, HTTP/HTTPS proxying, SQLite persistence, and a built-in admin console.

![Java 8](https://img.shields.io/badge/Java-8-orange.svg)
![Netty](https://img.shields.io/badge/Netty-4.1.51.Final-blue.svg)
![Build](https://img.shields.io/badge/Build-Maven-3.x-brightgreen.svg)

## 概述 | Overview

JMagnifier 适合在本地开发、测试环境、联调环境中临时接管某个监听端口，将连接转发到上游服务，同时记录连接和报文，便于排查协议交互、接口行为以及链路问题。  
JMagnifier is designed for local development, testing, and integration environments where you need to listen on a local port, forward traffic to an upstream service, and inspect connections and packets for troubleshooting.

当前实现已经不只是一个简单的 TCP 转发器，还包含以下运行时能力：  
The current implementation is more than a basic TCP forwarder and includes the following runtime capabilities:

- 原始 TCP 双向转发与字节流抓取。  
  Bidirectional raw TCP forwarding and byte stream capture.
- HTTP/HTTPS 转发，支持 Host 重写与 `X-Forwarded-*` 头补充。  
  HTTP/HTTPS forwarding with optional Host rewriting and `X-Forwarded-*` headers.
- SQLite 持久化映射规则、连接记录和抓包元数据。  
  SQLite persistence for mappings, connection records, and packet metadata.
- 管理控制台，用于登录、查看运行状态、增删改启停映射规则。  
  An admin console for login, runtime visibility, and mapping lifecycle management.
- 报文预览与可选 payload 文件落盘，支持保留策略清理。  
  Packet preview plus optional payload file storage with retention cleanup.

## 核心特性 | Key Features

- `Admin UI`：默认监听 `http://127.0.0.1:8080`，提供映射、连接、报文视图。  
  `Admin UI`: listens on `http://127.0.0.1:8080` by default and provides mapping, connection, and packet views.
- `SQLite Storage`：默认数据库路径为 `./data/jmagnifier.db`。  
  `SQLite Storage`: uses `./data/jmagnifier.db` by default.
- `Packet Capture`：可只保留预览，也可将完整 payload 写入文件。  
  `Packet Capture`: supports preview-only mode or full payload file storage.
- `Bootstrap From YAML`：支持通过 `config.yml` 提供初始配置。  
  `Bootstrap From YAML`: supports bootstrapping initial configuration from `config.yml`.
- `Command-line Shortcut`：支持三参数模式快速拉起单条转发规则。  
  `Command-line Shortcut`: supports a 3-argument shortcut for quickly starting a single forwarding rule.

## 启动行为 | Startup Behavior

应用启动时按以下优先级决定使用哪一批映射规则：  
At startup, mappings are selected using the following precedence:

1. 如果 SQLite 中已经存在启用状态的映射规则，则优先从 SQLite 恢复。  
   If active mappings already exist in SQLite, they are restored first.
2. 如果 SQLite 中没有可恢复的映射规则，则导入 `config.yml` 中的 `mappings` 作为初始规则。  
   If SQLite has no active mappings, `mappings` from `config.yml` are imported as bootstrap mappings.
3. 如果使用三参数命令行模式启动，且 SQLite 中没有活动规则，则命令行传入的单条规则作为初始规则。  
   If the app is started in 3-argument shortcut mode and SQLite has no active mappings, that single mapping is used as the bootstrap mapping.

这意味着：`YAML` 和三参数模式更像“初始化来源”，而不是每次启动都会强制覆盖运行时配置。  
This means `YAML` and the 3-argument shortcut act as bootstrap sources rather than always overriding runtime configuration on every startup.

## 环境要求 | Requirements

- JDK 8  
  JDK 8
- Maven 3.x  
  Maven 3.x

仓库中没有 Maven Wrapper，因此需要系统已安装 `mvn`，或显式使用本机 Maven 可执行文件。  
The repository does not include a Maven Wrapper, so `mvn` must be available on your machine or invoked via a local Maven installation path.

## 快速开始 | Quick Start

### 1. 构建 | Build

```bash
mvn -DskipTests package
```

说明：当前测试类会启动真实服务并阻塞线程，因此日常构建建议使用 `-DskipTests`。  
Note: the current test class starts real servers and blocks indefinitely, so `-DskipTests` is recommended for routine builds.

### 2. 打包产物 | Distribution Output

执行完成后会生成以下产物：  
After packaging, the following artifacts are produced:

```text
target/jmagnifier.jar
target/jmagnifier-1.0-dist/
target/jmagnifier-1.0-dist.zip
target/jmagnifier-1.0-dist.tar.gz
```

目录版发行包结构如下：  
The directory distribution has the following structure:

```text
jmagnifier-1.0-dist/
  bin/
    jmagnifier
    jmagnifier.bat
  config/
    config.yml
  lib/
    jmagnifier.jar
    *.jar
```

### 3. 运行 | Run

使用发行包脚本启动：  
Start with the packaged launcher script:

```bash
bin/jmagnifier
```

默认读取 `config/config.yml`。  
It reads `config/config.yml` by default.

也可以通过环境变量指定配置文件：  
You can also point to a different configuration file with an environment variable:

```bash
JMAGNIFIER_CONFIG=/absolute/path/to/config.yml bin/jmagnifier
```

如果直接运行 jar，请显式传入配置文件路径：  
If you run the jar directly, pass the config file path explicitly:

```bash
java -jar target/jmagnifier.jar /absolute/path/to/config.yml
```

### 4. 登录管理端 | Open the Admin Console

默认管理端地址：  
Default admin console address:

```text
http://127.0.0.1:8080
```

默认登录密码来自 `config.yml` 中的 `admin.password`，示例值为 `admin`。  
The default login password comes from `admin.password` in `config.yml`, which is `admin` in the sample configuration.

## 配置文件 | Configuration

### 顶层结构 | Top-level Structure

推荐的配置结构如下：  
The recommended top-level configuration looks like this:

```yaml
admin:
  host: "127.0.0.1"
  port: 8080
  password: "admin"
  sessionTimeoutMinutes: 720

store:
  sqlitePath: "./data/jmagnifier.db"
  spillDir: "./data/spill"
  payloadDir: "./data/payload"
  payloadSegmentBytes: 134217728
  payloadRetentionDays: 7
  payloadRetentionBytes: 21474836480

capture:
  enabled: true
  maxCaptureBytes: 409600
  previewBytes: 409600
  payloadStoreType: "FILE"
  maxPayloadBytes: 0
  queueCapacity: 10000
  batchSize: 100
  flushIntervalMillis: 200

mappings:
  - name: "tcp-demo"
    enable: true
    listen:
      port: 9300
      applicationProtocol: "tcp"
    forward:
      host: "127.0.0.1"
      port: 8080
      applicationProtocol: "tcp"
    console:
      printRequest: true
      printResponse: true
    dump:
      enable: false
      dumpPath: "/tmp/j_magnifier"
```

### 推荐写法 | Recommended Mapping Style

新配置建议优先使用 `listen` / `forward` 结构，而不是仅使用旧版扁平字段 `listenPort`、`forwardHost`、`forwardPort`。旧字段仍兼容，但不推荐作为新文档标准写法。  
For new configs, prefer the structured `listen` / `forward` form instead of relying only on the legacy flat fields `listenPort`, `forwardHost`, and `forwardPort`. The legacy fields remain compatible, but they are not the recommended style going forward.

### 原始 TCP 示例 | Raw TCP Example

```yaml
mappings:
  - name: "mysql-local"
    enable: true
    listen:
      port: 13306
      applicationProtocol: "tcp"
    forward:
      host: "10.0.0.12"
      port: 3306
      applicationProtocol: "tcp"
    console:
      printRequest: false
      printResponse: false
    dump:
      enable: false
      dumpPath: "/tmp/j_magnifier"
```

### HTTP 代理示例 | HTTP Proxy Example

```yaml
mappings:
  - name: "http-demo"
    enable: true
    listen:
      port: 8081
      applicationProtocol: "http"
    forward:
      host: "example.com"
      port: 80
      applicationProtocol: "http"
    http:
      rewriteHost: true
      addXForwardedHeaders: true
      maxObjectSizeBytes: 1048576
```

### HTTPS 上游示例 | HTTPS Upstream Example

```yaml
mappings:
  - name: "https-upstream"
    enable: true
    listen:
      port: 8443
      applicationProtocol: "http"
    forward:
      host: "api.example.com"
      port: 443
      applicationProtocol: "http"
      tls:
        enabled: true
        sniHost: "api.example.com"
        insecureSkipVerify: false
```

### TLS 监听示例 | TLS Listener Example

如果本地监听端需要启用 TLS，请在 `listen.tls` 中提供证书和私钥：  
If the local listening side should terminate TLS, provide a certificate and private key under `listen.tls`:

```yaml
mappings:
  - name: "https-listener"
    enable: true
    listen:
      port: 9443
      applicationProtocol: "http"
      tls:
        enabled: true
        certificateFile: "/path/to/server.crt"
        privateKeyFile: "/path/to/server.key"
        privateKeyPassword: ""
    forward:
      host: "127.0.0.1"
      port: 8080
      applicationProtocol: "http"
```

## 配置项说明 | Configuration Reference

### `admin`

- `host`：管理端绑定地址，建议保持为 `127.0.0.1`。  
  `host`: bind address for the admin console; keeping it at `127.0.0.1` is recommended.
- `port`：管理端端口，默认 `8080`。  
  `port`: admin console port, `8080` by default.
- `password`：登录密码。  
  `password`: login password.
- `sessionTimeoutMinutes`：登录会话超时时间。  
  `sessionTimeoutMinutes`: session timeout for the admin UI.

### `store`

- `sqlitePath`：SQLite 数据库文件路径。  
  `sqlitePath`: SQLite database file path.
- `spillDir`：抓包队列回压时的临时落盘目录。  
  `spillDir`: spill directory used when capture buffering overflows to disk.
- `payloadDir`：完整 payload 的文件存储目录。  
  `payloadDir`: directory for persisted payload files.
- `payloadSegmentBytes`：单个 payload 段文件的目标大小。  
  `payloadSegmentBytes`: target size of each payload segment file.
- `payloadRetentionDays`：payload 文件保留天数，`<= 0` 表示不按天数清理。  
  `payloadRetentionDays`: retention in days for payload files; `<= 0` disables age-based cleanup.
- `payloadRetentionBytes`：payload 文件总保留上限，`<= 0` 表示不按容量清理。  
  `payloadRetentionBytes`: total retained payload size limit; `<= 0` disables size-based cleanup.

### `capture`

- `enabled`：是否启用抓包。  
  `enabled`: enables or disables packet capture.
- `maxCaptureBytes`：单个报文最大抓取字节数。  
  `maxCaptureBytes`: max bytes captured per packet.
- `previewBytes`：管理端可直接预览的字节数。  
  `previewBytes`: number of bytes available for inline preview in the admin UI.
- `payloadStoreType`：`NONE`、`PREVIEW_ONLY`、`FILE`。  
  `payloadStoreType`: `NONE`, `PREVIEW_ONLY`, or `FILE`.
- `maxPayloadBytes`：每条 payload 最大落盘字节数，`0` 通常表示不单独限制。  
  `maxPayloadBytes`: max persisted payload bytes per message; `0` typically means no separate limit.
- `queueCapacity`、`batchSize`、`flushIntervalMillis`：抓包异步写入队列与批处理参数。  
  `queueCapacity`, `batchSize`, `flushIntervalMillis`: async capture queue and batching parameters.

### `mappings[*]`

- `name`：规则名称。  
  `name`: mapping name.
- `enable`：是否启用该规则。  
  `enable`: whether the mapping should be active.
- `listen`：本地监听端配置。  
  `listen`: local listening endpoint configuration.
- `forward`：上游转发端配置。  
  `forward`: upstream forwarding endpoint configuration.
- `console.printRequest` / `console.printResponse`：是否打印请求/响应日志。  
  `console.printRequest` / `console.printResponse`: whether request/response traffic should be logged to console.
- `dump.enable` / `dump.dumpPath`：是否将流量按日追加写入 dump 文件。  
  `dump.enable` / `dump.dumpPath`: whether traffic should be appended to daily dump files.
- `http.rewriteHost`：HTTP 模式下是否重写 `Host`。  
  `http.rewriteHost`: whether to rewrite the `Host` header in HTTP mode.
- `http.addXForwardedHeaders`：HTTP 模式下是否追加转发头。  
  `http.addXForwardedHeaders`: whether to add forwarding headers in HTTP mode.
- `http.maxObjectSizeBytes`：HTTP 聚合对象大小上限。  
  `http.maxObjectSizeBytes`: max aggregated HTTP object size.

### `listen` / `forward`

- `port`：端口。  
  `port`: port number.
- `host`：仅 `forward` 侧必填，表示上游主机。  
  `host`: required on the `forward` side and points to the upstream host.
- `applicationProtocol`：当前支持 `tcp` 和 `http`。  
  `applicationProtocol`: currently supports `tcp` and `http`.
- `tls.enabled`：是否启用 TLS。  
  `tls.enabled`: enables TLS for the endpoint.
- `tls.certificateFile` / `tls.privateKeyFile`：监听端 TLS 所需证书和私钥。  
  `tls.certificateFile` / `tls.privateKeyFile`: certificate and private key for TLS termination on the listening side.
- `tls.sniHost`：连接上游 TLS 服务时使用的 SNI。  
  `tls.sniHost`: SNI hostname for upstream TLS connections.
- `tls.insecureSkipVerify`：是否跳过上游证书校验，不建议生产环境启用。  
  `tls.insecureSkipVerify`: skips upstream certificate verification; not recommended in production.
- `tls.trustCertCollectionFile`：自定义信任证书文件。  
  `tls.trustCertCollectionFile`: custom trust certificate collection file.

## 命令行快速模式 | Command-line Shortcut Mode

除了配置文件模式，还支持三参数快速模式：  
In addition to config-file mode, a 3-argument shortcut is supported:

```bash
java -jar target/jmagnifier.jar 9300 example.com 80
```

参数含义：`listenPort forwardHost forwardPort`。  
Arguments: `listenPort forwardHost forwardPort`.

这个模式适合快速临时转发，但只适用于单条初始化规则。  
This mode is convenient for quick temporary forwarding, but it only initializes a single mapping.

## 管理端能力 | Admin Console Capabilities

管理端目前提供以下能力：  
The admin console currently provides:

- 登录与会话管理。  
  Login and session management.
- 运行状态总览。  
  Runtime status overview.
- 映射规则的创建、更新、启动、停止、删除。  
  Create, update, start, stop, and delete mappings.
- 连接列表与连接详情查看。  
  Connection list and per-connection details.
- 抓包列表、报文详情、payload 预览与下载。  
  Packet list, packet details, and payload preview/download.

## 项目结构 | Project Layout

```text
src/main/java/com/AppStart.java         应用入口 | Application entry point
src/main/java/com/runtime               运行时装配 | Runtime bootstrap and lifecycle
src/main/java/com/admin                 管理端 HTTP 服务 | Admin HTTP server
src/main/java/com/mapping               映射规则管理 | Mapping lifecycle management
src/main/java/com/protocol              TCP/HTTP/TLS 协议桥接 | Protocol pipelines and bridges
src/main/java/com/capture               抓包与落盘 | Packet capture and payload storage
src/main/java/com/store                 SQLite 持久化 | SQLite persistence layer
src/main/resources/admin                管理端静态资源 | Admin static assets
src/main/resources/config.yml           示例配置 | Sample configuration
```

## 运行建议与注意事项 | Operational Notes

- 管理端默认密码为示例值，请在实际环境中修改。  
  The default admin password is only a sample value and should be changed in real environments.
- 管理端绑定到 `0.0.0.0` 时会暴露在网络上，不要直接开放到不可信网络。  
  Binding the admin console to `0.0.0.0` exposes it on the network; do not expose it to untrusted environments.
- 如果希望每次启动都按 YAML 初始化，请使用新的 `sqlitePath` 或清理旧数据库中的活动规则。  
  If you need startup to re-bootstrap from YAML every time, use a fresh `sqlitePath` or remove active mappings from the existing database.
- `payloadStoreType=FILE` 会产生持续磁盘占用，应合理配置保留天数和容量上限。  
  `payloadStoreType=FILE` consumes disk space over time, so retention days and byte limits should be configured carefully.
- 日常构建不要直接执行 `mvn test`，当前测试会长时间阻塞。  
  Avoid running `mvn test` for routine checks because the current tests block for a long time.

## 许可证 | License

本项目采用 `Apache License 2.0`。许可证全文见根目录 [LICENSE](/Users/liuqiwei/IdeaProjects/jmagnifier/LICENSE)。  
This project is licensed under the `Apache License 2.0`. See [LICENSE](/Users/liuqiwei/IdeaProjects/jmagnifier/LICENSE) for the full text.
