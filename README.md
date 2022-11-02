# Java Magnifier🔍🔍🔍

![jdk12](https://img.shields.io/badge/jdk-8-orange.svg)


## 功能：
* 端口监听，转发并记录tcp报文

## 配置文件
```yaml
listenPort: 889 # 监听端口
forwardPort: 8848 # 准发目标端口
consolePrint: true # 控制台打印
logDump: true # 是否存储
dumpPath: "/usr/local/dump" # 存储地址
ignoreHex: true # true 则不转化为hex
ignoreString: false # true 则不转化为字符串
```
