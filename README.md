# Java Magnifier🔍🔍🔍

![jdk12](https://img.shields.io/badge/jdk-8-orange.svg)


## 功能：
* 端口监听，转发并记录tcp报文

## 配置文件

`config.yml` 只保留启动时需要的全局配置。转发规则建议在 Web 管理端维护，并持久化到 SQLite。

```yaml
admin:
  host: "127.0.0.1"
  port: 8080
  password: "admin"
  sessionTimeoutMinutes: 720

store:
  sqlitePath: "./data/jmagnifier.db"
  spillDir: "./data/spill"

capture:
  enabled: true
  maxCaptureBytes: 4096
  queueCapacity: 10000
  batchSize: 100
  flushIntervalMillis: 200
```

启动后访问 `http://127.0.0.1:8080`，使用配置中的密码登录，然后在 Web 页面创建、修改、启停转发规则。

## 打包和运行

```bash
mvn -DskipTests package
```

打包后会生成：

```text
target/jmagnifier-1.0-dist.zip
target/jmagnifier-1.0-dist.tar.gz
```

解压后的目录结构：

```text
jmagnifier-1.0/
  bin/
    jmagnifier
    jmagnifier.bat
  config/
    config.yml
  lib/
    jmagnifier.jar
    *.jar
```

启动：

```bash
bin/jmagnifier
```

默认读取 `config/config.yml`。也可以通过环境变量指定其他配置文件：

```bash
JMAGNIFIER_CONFIG=/path/to/config.yml bin/jmagnifier
```
