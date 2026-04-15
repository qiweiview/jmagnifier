# Codex Working Notes

This repository is a small Java 8 TCP forwarding and packet-dump tool built on Netty. The runtime listens on local ports, opens a corresponding remote TCP connection for each accepted client connection, forwards raw bytes in both directions, and optionally logs/dumps the traffic.

## Project Map

- `pom.xml`: Maven build. Main class is `com.AppStart`; Java source/target is 8. Key dependencies are Netty 4.1.51, SnakeYAML, Lombok, Commons IO, Logback, and JUnit.
- `README.md`: short user-facing overview. Some configuration examples there are older than the current model classes.
- `src/main/resources/config.yml`: example YAML using the current `mappings` list shape.
- `src/main/java/com/AppStart.java`: application entry point, startup banner, argument parsing, config loading, validation, and per-mapping server startup.
- `src/main/java/com/model/*`: YAML-bound configuration objects.
- `src/main/java/com/core/*`: Netty forwarding pipeline and byte transfer logic.
- `src/main/java/com/util/*`: YAML parsing, Netty group factory, process exit helper, HTTP helper leftovers, and small utilities.
- `src/test/java/com/AppStartTest.java`: integration-style smoke tests that call `AppStart.main()` and then block forever.

## Runtime Flow

1. `AppStart.main()` builds a `GlobalConfig` from one of three modes:
   - no arguments: interactive stdin prompts;
   - one `.yml` argument: parse YAML file via `YmlParser`;
   - more than three arguments: command-line mapping mode using the first three arguments as `listenPort forwardHost forwardPort`.
2. `GlobalConfig.verifyConfiguration()` checks that at least one mapping exists and filters out mappings whose ports are outside the accepted range.
3. `AppStart.startMappingServer()` creates one `DataReceiver` per mapping.
4. `DataReceiver` binds a local `NioServerSocketChannel` on `mapping.listenPort`.
5. For each accepted local channel, `DataReceiver` creates a local `ByteReadHandler`, adds it to the pipeline, and starts a `TCPForWardContext`.
6. `TCPForWardContext` connects to `mapping.forwardHost:mapping.forwardPort`, creates a remote `ByteReadHandler`, and binds the two handlers to each other with `setTarget`.
7. Each `ByteReadHandler` copies incoming `ByteBuf` bytes, runs its logging/dump consumer, then forwards the bytes to its target handler with `receiveData`.

## Configuration Shape

Current YAML expects a top-level `mappings` list:

```yaml
mappings:
  - name: "r1"
    listenPort: 9300
    forwardHost: "example.com"
    forwardPort: 80
    console:
      printRequest: false
      printResponse: false
    dump:
      enable: true
      dumpPath: "/tmp/j_magnifier"
```

Important details:

- `Mapping.createDefaultMapping()` fills default `console` and `dump` objects, but YAML parsing does not call it. YAML mappings should include `console` and `dump` to avoid null handling issues in the forwarding path.
- `Mapping.enable` currently exists but is not used by startup or validation.
- Dump files are named by `Mapping.dumpName()` as `<mapping-name>_<yyyy-MM-dd>.log`.
- Dump writes append raw bytes to `dumpPath + File.separator + dumpName`; code does not create the dump directory before writing.

## Build And Run

There is no Maven Wrapper in this repository. With Maven installed:

```bash
mvn -DskipTests package
java -jar target/jmagnifier.jar /absolute/path/to/config.yml
```

Development run alternatives:

```bash
mvn -DskipTests compile exec:java -Dexec.mainClass=com.AppStart -Dexec.args="/absolute/path/to/config.yml"
```

The `exec:java` command requires adding/configuring `exec-maven-plugin` or using an IDE run configuration; it is not currently declared in `pom.xml`.

## Testing Notes

- Avoid running plain `mvn test` as a quick check. `AppStartTest` starts real servers and then blocks indefinitely via `thread.wait()`.
- Prefer `mvn -DskipTests package` for a fast compile/package check when Maven is available.
- For future automated tests, isolate pure logic first:
  - YAML parsing and model defaults;
  - `GlobalConfig.verifyConfiguration()` filtering;
  - `Mapping.dumpName()` formatting;
  - `ByteReadHandler` behavior with mocked or embedded Netty channels.
- Integration tests should bind ephemeral local ports, use a local echo server as the forward target, and include deterministic shutdown.

## Known Edge Cases And Likely Bugs

- Command-line mode is probably unreachable for the natural three-argument form. `AppStart.main()` checks `args.length > 3`, while `paramMode()` only needs the first three arguments.
- `TCPForWardContext` reads `mapping.getConsole().getPrintRequest()` when handling remote response bytes, but the log message refers to `printResponse`. This likely should use `getPrintResponse()`.
- Port validation accepts `65536` because it filters only `> 65536`; valid TCP/UDP ports are `0..65535`.
- `Mapping.enable` is ignored, so disabled mappings still start.
- YAML mappings with missing `console` or `dump` can cause `NullPointerException` when traffic arrives.
- `ByteReadHandler.channelInactive()` calls `dataSwap.closeSwap()` without a null check.
- `ByteReadHandler.closeSwap()` assumes `channelHandlerContext` is non-null.
- `DataReceiver` and `TCPForWardContext` each allocate a fresh `NioEventLoopGroup`; lifecycle cleanup is minimal and remote contexts have an empty `release()`.
- `pom.xml` resource includes only `**/*.css` and `**/*.xml`, so `src/main/resources/config.yml` is not packaged into the jar.
- `junit:junit:RELEASE` is non-reproducible; pin a concrete JUnit 4 version before tightening tests.
- `InetUtils` imports `sun.net.util.IPAddressUtil`, which is an internal JDK API and may be fragile outside older JDKs.
- `HttpResponseBuilder`, `BlockValueFeature`, and `InetUtils` appear to be leftovers from an older HTTP proxy path rather than the active raw TCP forwarding path.

## Change Guidance

- Keep changes Java 8 compatible unless the project explicitly raises its Java version.
- Preserve the raw TCP forwarding model: this is byte-level forwarding, not HTTP-aware proxying.
- Prefer fixing config/model defaults before adding new user-facing options; many runtime assumptions depend on `Mapping.console` and `Mapping.dump` being non-null.
- When touching Netty handlers, pay close attention to `ByteBuf` ownership and channel close propagation.
- Do not treat `README.md` as fully authoritative for configuration keys; confirm against `com.model` classes and `src/main/resources/config.yml`.
- If adding regular CI or local checks, add a Maven Wrapper first so future agents can run the same commands consistently.

