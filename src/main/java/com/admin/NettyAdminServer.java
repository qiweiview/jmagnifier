package com.admin;

import com.capture.PacketCaptureService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapping.MappingRuntime;
import com.mapping.MappingOperationException;
import com.mapping.MappingStatus;
import com.mapping.RuntimeMappingManager;
import com.model.AdminConfig;
import com.model.Mapping;
import com.runtime.NettyGroups;
import com.store.ConnectionRepository;
import com.store.PacketRepository;
import com.store.PageResult;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NettyAdminServer {

    private static final Logger log = LoggerFactory.getLogger(NettyAdminServer.class);

    private static final int PREVIEW_BYTES = 4096;

    private final AdminConfig adminConfig;

    private final RuntimeMappingManager mappingManager;

    private final PacketCaptureService packetCaptureService;

    private final ConnectionRepository connectionRepository;

    private final PacketRepository packetRepository;

    private final NettyGroups nettyGroups;

    private final AdminSessionManager sessionManager;

    private Channel serverChannel;

    public NettyAdminServer(AdminConfig adminConfig, RuntimeMappingManager mappingManager,
                            PacketCaptureService packetCaptureService, NettyGroups nettyGroups,
                            ConnectionRepository connectionRepository, PacketRepository packetRepository) {
        this.adminConfig = adminConfig;
        this.mappingManager = mappingManager;
        this.packetCaptureService = packetCaptureService;
        this.connectionRepository = connectionRepository;
        this.packetRepository = packetRepository;
        this.nettyGroups = nettyGroups;
        this.sessionManager = new AdminSessionManager(adminConfig.getSessionTimeoutMinutes());
    }

    public void start() {
        if ("0.0.0.0".equals(adminConfig.getHost())) {
            log.warn("admin server is binding to 0.0.0.0, do not expose it to untrusted networks");
        }
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(nettyGroups.getAdminBossGroup(), nettyGroups.getAdminWorkerGroup())
                .channel(NioServerSocketChannel.class)
                .localAddress(new InetSocketAddress(adminConfig.getHost(), adminConfig.getPort()))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
                        pipeline.addLast(new ChunkedWriteHandler());
                        pipeline.addLast(new AdminHandler());
                    }
                });
        try {
            serverChannel = bootstrap.bind().sync().channel();
            log.info("admin server started at http://{}:{}", adminConfig.getHost(), adminConfig.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("start admin server interrupted", e);
        }
    }

    public void stop() {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().syncUninterruptibly();
        }
    }

    private class AdminHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private final ObjectMapper objectMapper = new ObjectMapper();

        private final AdminRouter publicRouter = new AdminRouter();

        private final AdminRouter authenticatedRouter = new AdminRouter();

        private AdminHandler() {
            publicRouter.add(HttpMethod.GET, "/login", (request, params) -> classpathResource("admin/login.html"));
            publicRouter.add(HttpMethod.POST, "/api/login", (request, params) -> login(request));

            authenticatedRouter.add(HttpMethod.POST, "/api/logout", (request, params) -> logout(request));
            authenticatedRouter.add(HttpMethod.GET, "/api/me", (request, params) -> me());
            authenticatedRouter.add(HttpMethod.GET, "/api/runtime", (request, params) -> JsonResponse.success(runtimeSummary()));
            authenticatedRouter.add(HttpMethod.GET, "/api/mappings", (request, params) -> JsonResponse.success(mappingList()));
            authenticatedRouter.add(HttpMethod.POST, "/api/mappings", (request, params) ->
                    JsonResponse.success(toMappingResponse(mappingManager.startMapping(readMapping(request)))));
            authenticatedRouter.add(HttpMethod.PUT, "/api/mappings/{id}", (request, params) ->
                    JsonResponse.success(toMappingResponse(mappingManager.updateMapping(requiredId(params, "id"), readMapping(request)))));
            authenticatedRouter.add(HttpMethod.POST, "/api/mappings/{id}/start", (request, params) ->
                    JsonResponse.success(toMappingResponse(mappingManager.startMapping(requiredId(params, "id")))));
            authenticatedRouter.add(HttpMethod.POST, "/api/mappings/{id}/stop", (request, params) -> {
                mappingManager.stopMapping(requiredId(params, "id"));
                return JsonResponse.success();
            });
            authenticatedRouter.add(HttpMethod.DELETE, "/api/mappings/{id}", (request, params) -> {
                mappingManager.deleteMapping(requiredId(params, "id"));
                return JsonResponse.success();
            });
            authenticatedRouter.add(HttpMethod.GET, "/api/connections", (request, params) ->
                    JsonResponse.success(connectionPage(readConnectionQuery(new QueryStringDecoder(request.uri())))));
            authenticatedRouter.add(HttpMethod.GET, "/api/connections/{id}", (request, params) ->
                    connectionDetail(requiredId(params, "id")));
            authenticatedRouter.add(HttpMethod.GET, "/api/packets", (request, params) ->
                    JsonResponse.success(packetPage(readPacketQuery(new QueryStringDecoder(request.uri())))));
            authenticatedRouter.add(HttpMethod.GET, "/api/packets/{id}", (request, params) ->
                    packetDetail(requiredId(params, "id")));
            authenticatedRouter.add(HttpMethod.GET, "/api/packets/{id}/payload", (request, params) ->
                    packetPayload(requiredId(params, "id")));
            authenticatedRouter.add(HttpMethod.GET, "/", (request, params) -> classpathResource("admin/index.html"));
            authenticatedRouter.add(HttpMethod.GET, "/mappings", (request, params) -> classpathResource("admin/index.html"));
            authenticatedRouter.add(HttpMethod.GET, "/connections", (request, params) -> classpathResource("admin/index.html"));
            authenticatedRouter.add(HttpMethod.GET, "/packets", (request, params) -> classpathResource("admin/index.html"));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            FullHttpResponse response;
            try {
                response = route(request);
            } catch (AdminApiException e) {
                response = JsonResponse.error(e.getStatus(), e.getCode(), e.getMessage());
            } catch (MappingOperationException e) {
                response = JsonResponse.error(statusForMappingError(e.getCode()), e.getCode(), e.getMessage());
            } catch (IllegalArgumentException e) {
                response = JsonResponse.error(HttpResponseStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
            } catch (RuntimeException e) {
                response = JsonResponse.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage());
            }
            write(ctx, request, response);
        }

        private FullHttpResponse route(FullHttpRequest request) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            String path = decoder.path();
            if (HttpMethod.GET.equals(request.method()) && path.startsWith("/assets/")) {
                return classpathResource("admin" + path);
            }
            FullHttpResponse publicResponse = publicRouter.route(request);
            if (publicResponse != null) {
                return publicResponse;
            }

            String sessionId = sessionId(request);
            boolean authenticated = sessionManager.isValid(sessionId);
            if (!authenticated) {
                if (path.startsWith("/api/")) {
                    throw new AdminApiException(HttpResponseStatus.UNAUTHORIZED, "UNAUTHORIZED", "login required");
                }
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
                response.headers().set(HttpHeaderNames.LOCATION, "/login");
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                return response;
            }

            FullHttpResponse response = authenticatedRouter.route(request);
            if (response != null) {
                return response;
            }
            return JsonResponse.error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "route not found");
        }

        private FullHttpResponse classpathResource(String resourcePath) {
            String normalized = resourcePath.replace('\\', '/');
            if (normalized.contains("..") || normalized.startsWith("/")) {
                return JsonResponse.error(HttpResponseStatus.BAD_REQUEST, "BAD_REQUEST", "invalid resource path");
            }
            try (InputStream inputStream = NettyAdminServer.class.getClassLoader().getResourceAsStream(normalized)) {
                if (inputStream == null) {
                    return JsonResponse.error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "resource not found");
                }
                byte[] bytes = readAll(inputStream);
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType(normalized));
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                return response;
            } catch (IOException e) {
                return JsonResponse.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "read resource failed");
            }
        }

        private byte[] readAll(InputStream inputStream) throws IOException {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }

        private String contentType(String resourcePath) {
            if (resourcePath.endsWith(".html")) {
                return "text/html; charset=utf-8";
            }
            if (resourcePath.endsWith(".css")) {
                return "text/css; charset=utf-8";
            }
            if (resourcePath.endsWith(".js")) {
                return "application/javascript; charset=utf-8";
            }
            if (resourcePath.endsWith(".svg")) {
                return "image/svg+xml";
            }
            if (resourcePath.endsWith(".png")) {
                return "image/png";
            }
            return "application/octet-stream";
        }

        private FullHttpResponse login(FullHttpRequest request) {
            Map<String, Object> body = readJsonMap(request);
            String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
            String expectedPassword = System.getenv("JMAGNIFIER_ADMIN_PASSWORD");
            if (expectedPassword == null || expectedPassword.length() == 0) {
                expectedPassword = adminConfig.getPassword();
            }
            if (!Objects.equals(expectedPassword, password)) {
                return JsonResponse.error(HttpResponseStatus.UNAUTHORIZED, "INVALID_PASSWORD", "invalid password");
            }
            String sessionId = sessionManager.createSession();
            FullHttpResponse response = JsonResponse.success();
            DefaultCookie cookie = new DefaultCookie(AdminSessionManager.COOKIE_NAME, sessionId);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
            return response;
        }

        private FullHttpResponse logout(FullHttpRequest request) {
            String sessionId = sessionId(request);
            sessionManager.remove(sessionId);
            FullHttpResponse response = JsonResponse.success();
            DefaultCookie cookie = new DefaultCookie(AdminSessionManager.COOKIE_NAME, "");
            cookie.setPath("/");
            cookie.setMaxAge(0);
            cookie.setHttpOnly(true);
            response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
            return response;
        }

        private FullHttpResponse me() {
            Map<String, Object> data = new HashMap<>();
            data.put("authenticated", true);
            return JsonResponse.success(data);
        }

        private Mapping readMapping(FullHttpRequest request) {
            Map<String, Object> body = readJsonMap(request);
            Mapping mapping = objectMapper.convertValue(body, Mapping.class);
            if (body.containsKey("enabled")) {
                mapping.setEnable(booleanValue(body.get("enabled")));
            }
            if (body.containsKey("enable")) {
                mapping.setEnable(booleanValue(body.get("enable")));
            }
            mapping.applyDefaults();
            return mapping;
        }

        private HttpResponseStatus statusForMappingError(String code) {
            if ("MAPPING_NOT_FOUND".equals(code)) {
                return HttpResponseStatus.NOT_FOUND;
            }
            if ("BIND_FAILED".equals(code)) {
                return HttpResponseStatus.CONFLICT;
            }
            if ("PORT_ALREADY_CONFIGURED".equals(code)) {
                return HttpResponseStatus.CONFLICT;
            }
            if ("INVALID_PORT".equals(code) || "INVALID_FORWARD_HOST".equals(code) || "BAD_REQUEST".equals(code)) {
                return HttpResponseStatus.BAD_REQUEST;
            }
            if ("INVALID_PROTOCOL".equals(code) || "INVALID_PROTOCOL_COMBINATION".equals(code) || "UNSUPPORTED_PROTOCOL".equals(code)) {
                return HttpResponseStatus.BAD_REQUEST;
            }
            return HttpResponseStatus.INTERNAL_SERVER_ERROR;
        }

        private Map<String, Object> readJsonMap(FullHttpRequest request) {
            try {
                String content = request.content().toString(java.nio.charset.StandardCharsets.UTF_8);
                if (content == null || content.trim().length() == 0) {
                    return new HashMap<>();
                }
                return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid json body");
            }
        }

        private ConnectionRepository.ConnectionQuery readConnectionQuery(QueryStringDecoder decoder) {
            Map<String, List<String>> params = decoder.parameters();
            ConnectionRepository.ConnectionQuery query = new ConnectionRepository.ConnectionQuery();
            query.mappingId = longParam(params, "mappingId");
            query.clientIp = stringParam(params, "clientIp");
            query.status = stringParam(params, "status");
            query.from = stringParam(params, "from");
            query.to = stringParam(params, "to");
            query.page = intParam(params, "page", 1);
            query.pageSize = intParam(params, "pageSize", 50);
            return query;
        }

        private PacketRepository.PacketQuery readPacketQuery(QueryStringDecoder decoder) {
            Map<String, List<String>> params = decoder.parameters();
            PacketRepository.PacketQuery query = new PacketRepository.PacketQuery();
            query.mappingId = longParam(params, "mappingId");
            query.connectionId = longParam(params, "connectionId");
            query.direction = stringParam(params, "direction");
            if (query.direction != null && !"REQUEST".equals(query.direction) && !"RESPONSE".equals(query.direction)) {
                throw new IllegalArgumentException("direction must be REQUEST or RESPONSE");
            }
            query.from = stringParam(params, "from");
            query.to = stringParam(params, "to");
            query.page = intParam(params, "page", 1);
            query.pageSize = intParam(params, "pageSize", 50);
            return query;
        }

        private Map<String, Object> connectionPage(ConnectionRepository.ConnectionQuery query) {
            PageResult<ConnectionRepository.ConnectionRecord> page = connectionRepository.query(query);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ConnectionRepository.ConnectionRecord record : page.getItems()) {
                items.add(toConnectionResponse(record, false));
            }
            return pageResponse(items, page.getPage(), page.getPageSize(), page.getTotal());
        }

        private FullHttpResponse connectionDetail(long connectionId) {
            ConnectionRepository.ConnectionRecord record = connectionRepository.findById(connectionId);
            if (record == null) {
                return JsonResponse.error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "connection not found");
            }
            Map<String, Object> data = toConnectionResponse(record, true);
            List<Map<String, Object>> packets = new ArrayList<>();
            for (PacketRepository.PacketRecord packet : packetRepository.findRecentByConnectionId(connectionId, 50)) {
                packets.add(toPacketSummary(packet));
            }
            data.put("recentPackets", packets);
            return JsonResponse.success(data);
        }

        private Map<String, Object> packetPage(PacketRepository.PacketQuery query) {
            PageResult<PacketRepository.PacketRecord> page = packetRepository.query(query);
            List<Map<String, Object>> items = new ArrayList<>();
            for (PacketRepository.PacketRecord record : page.getItems()) {
                items.add(toPacketSummary(record));
            }
            return pageResponse(items, page.getPage(), page.getPageSize(), page.getTotal());
        }

        private FullHttpResponse packetDetail(long packetId) {
            PacketRepository.PacketRecord record = packetRepository.findById(packetId);
            if (record == null) {
                return JsonResponse.error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "packet not found");
            }
            return JsonResponse.success(toPacketDetail(record));
        }

        private FullHttpResponse packetPayload(long packetId) {
            PacketRepository.PacketRecord record = packetRepository.findById(packetId);
            if (record == null) {
                return JsonResponse.error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "packet not found");
            }
            byte[] payload = record.payload == null ? new byte[0] : record.payload;
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK, Unpooled.wrappedBuffer(payload));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, payload.length);
            response.headers().set("Content-Disposition", "attachment; filename=\"packet-" + packetId + ".bin\"");
            return response;
        }

        private List<Map<String, Object>> mappingList() {
            List<Map<String, Object>> items = new ArrayList<>();
            for (MappingRuntime runtime : mappingManager.listMappingsWithStatus()) {
                items.add(toMappingResponse(runtime));
            }
            return items;
        }

        private Map<String, Object> runtimeSummary() {
            int running = 0;
            int stopped = 0;
            int failed = 0;
            int activeConnections = 0;
            for (MappingRuntime runtime : mappingManager.listMappingsWithStatus()) {
                if (runtime.getStatus() == MappingStatus.RUNNING) {
                    running++;
                } else if (runtime.getStatus() == MappingStatus.FAILED) {
                    failed++;
                } else {
                    stopped++;
                }
                activeConnections += runtime.getActiveConnections();
            }
            Map<String, Object> data = new HashMap<>();
            data.put("mappings", mappingManager.listMappingsWithStatus().size());
            data.put("runningMappings", running);
            data.put("stoppedMappings", stopped);
            data.put("failedMappings", failed);
            data.put("activeConnections", activeConnections);
            data.put("captureQueueSize", packetCaptureService.getQueueSize());
            data.put("captureQueueCapacity", packetCaptureService.getQueueCapacity());
            data.put("spillFileCount", packetCaptureService.getSpillFileCount());
            data.put("spillBytes", packetCaptureService.getSpillBytes());
            data.put("packetsWritten", packetCaptureService.getPacketsWritten());
            data.put("packetsSpilled", packetCaptureService.getPacketsSpilled());
            data.put("packetsDropped", packetCaptureService.getPacketsDropped());
            data.put("lastWriterError", packetCaptureService.getLastWriterError());
            return data;
        }

        private Map<String, Object> toMappingResponse(MappingRuntime runtime) {
            Mapping mapping = runtime.getMappingSnapshot();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", runtime.getMappingId());
            data.put("name", mapping.getName());
            data.put("enabled", mapping.getEnable());
            data.put("listenPort", mapping.getListenPort());
            data.put("forwardHost", mapping.getForwardHost());
            data.put("forwardPort", mapping.getForwardPort());
            data.put("listen", mapping.getListen());
            data.put("forward", mapping.getForward());
            data.put("http", mapping.getHttp());
            data.put("listenMode", mapping.getListenMode());
            data.put("forwardMode", mapping.getForwardMode());
            data.put("status", runtime.getStatus().name());
            data.put("activeConnections", runtime.getActiveConnections());
            data.put("lastError", runtime.getLastError());
            data.put("createdAt", null);
            data.put("updatedAt", null);
            return data;
        }

        private Map<String, Object> toConnectionResponse(ConnectionRepository.ConnectionRecord record, boolean detail) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", record.id);
            data.put("mappingId", record.mappingId);
            data.put("clientIp", record.clientIp);
            data.put("clientPort", record.clientPort);
            if (detail) {
                data.put("listenIp", record.listenIp);
            }
            data.put("listenPort", record.listenPort);
            data.put("forwardHost", record.forwardHost);
            data.put("forwardPort", record.forwardPort);
            if (detail) {
                data.put("remoteIp", record.remoteIp);
                data.put("remotePort", record.remotePort);
                data.put("closeReason", record.closeReason);
                data.put("errorMessage", record.errorMessage);
            }
            data.put("status", record.status);
            data.put("openedAt", record.openedAt);
            data.put("closedAt", record.closedAt);
            data.put("bytesUp", record.bytesUp);
            data.put("bytesDown", record.bytesDown);
            return data;
        }

        private Map<String, Object> toPacketSummary(PacketRepository.PacketRecord record) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", record.id);
            data.put("connectionId", record.connectionId);
            data.put("mappingId", record.mappingId);
            data.put("direction", record.direction);
            data.put("sequenceNo", record.sequenceNo);
            data.put("clientIp", record.clientIp);
            data.put("clientPort", record.clientPort);
            data.put("targetHost", record.targetHost);
            data.put("targetPort", record.targetPort);
            data.put("payloadSize", record.payloadSize);
            data.put("capturedSize", record.capturedSize);
            data.put("truncated", record.truncated);
            data.put("receivedAt", record.receivedAt);
            return data;
        }

        private Map<String, Object> toPacketDetail(PacketRepository.PacketRecord record) {
            Map<String, Object> data = toPacketSummary(record);
            byte[] payload = record.payload == null ? new byte[0] : record.payload;
            data.put("listenIp", record.listenIp);
            data.put("listenPort", record.listenPort);
            data.put("remoteIp", record.remoteIp);
            data.put("remotePort", record.remotePort);
            data.put("hexPreview", hexPreview(payload));
            data.put("textPreview", textPreview(payload));
            data.put("previewBytes", Math.min(payload.length, PREVIEW_BYTES));
            data.put("previewTruncated", payload.length > PREVIEW_BYTES);
            return data;
        }

        private Map<String, Object> pageResponse(List<Map<String, Object>> items, int page, int pageSize, long total) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("page", page);
            data.put("pageSize", pageSize);
            data.put("total", total);
            return data;
        }

        private String hexPreview(byte[] payload) {
            StringBuilder builder = new StringBuilder();
            int length = Math.min(payload.length, PREVIEW_BYTES);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    builder.append(i % 16 == 0 ? '\n' : ' ');
                }
                int value = payload[i] & 0xff;
                if (value < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(value).toUpperCase(Locale.ROOT));
            }
            return builder.toString();
        }

        private String textPreview(byte[] payload) {
            int length = Math.min(payload.length, PREVIEW_BYTES);
            String text = new String(Arrays.copyOf(payload, length), StandardCharsets.UTF_8);
            StringBuilder normalized = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                normalized.append(Character.isISOControl(value) ? '.' : value);
            }
            return htmlEscape(normalized.toString());
        }

        private String htmlEscape(String text) {
            StringBuilder escaped = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                if (value == '&') {
                    escaped.append("&amp;");
                } else if (value == '<') {
                    escaped.append("&lt;");
                } else if (value == '>') {
                    escaped.append("&gt;");
                } else if (value == '"') {
                    escaped.append("&quot;");
                } else if (value == '\'') {
                    escaped.append("&#39;");
                } else {
                    escaped.append(value);
                }
            }
            return escaped.toString();
        }

        private long requiredId(Map<String, String> params, String name) {
            String value = params.get(name);
            try {
                return Long.parseLong(value);
            } catch (Exception e) {
                throw new IllegalArgumentException(name + " must be a number");
            }
        }

        private String sessionId(FullHttpRequest request) {
            String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
            if (cookieHeader == null) {
                return null;
            }
            for (io.netty.handler.codec.http.cookie.Cookie cookie : ServerCookieDecoder.STRICT.decode(cookieHeader)) {
                if (AdminSessionManager.COOKIE_NAME.equals(cookie.name())) {
                    return cookie.value();
                }
            }
            return null;
        }

        private String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private String stringParam(Map<String, List<String>> params, String name) {
            List<String> values = params.get(name);
            if (values == null || values.size() == 0) {
                return null;
            }
            String value = values.get(0);
            return value == null || value.trim().length() == 0 ? null : value.trim();
        }

        private Long longParam(Map<String, List<String>> params, String name) {
            String value = stringParam(params, name);
            if (value == null) {
                return null;
            }
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number");
            }
        }

        private int intParam(Map<String, List<String>> params, String name, int defaultValue) {
            String value = stringParam(params, name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number");
            }
        }

        private int intValue(Object value, String name) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (Exception e) {
                throw new IllegalArgumentException(name + " must be a number");
            }
        }

        private boolean booleanValue(Object value) {
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }

        private void write(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            if (keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            ChannelFuture future = ctx.writeAndFlush(response);
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }
}
