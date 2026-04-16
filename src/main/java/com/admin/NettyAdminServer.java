package com.admin;

import com.capture.PacketCaptureService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mapping.MappingRuntime;
import com.mapping.MappingStatus;
import com.mapping.RuntimeMappingManager;
import com.model.AdminConfig;
import com.model.Mapping;
import com.runtime.NettyGroups;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.*;

@Slf4j
public class NettyAdminServer {

    private final AdminConfig adminConfig;

    private final RuntimeMappingManager mappingManager;

    private final PacketCaptureService packetCaptureService;

    private final NettyGroups nettyGroups;

    private final AdminSessionManager sessionManager;

    private Channel serverChannel;

    public NettyAdminServer(AdminConfig adminConfig, RuntimeMappingManager mappingManager,
                            PacketCaptureService packetCaptureService, NettyGroups nettyGroups) {
        this.adminConfig = adminConfig;
        this.mappingManager = mappingManager;
        this.packetCaptureService = packetCaptureService;
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

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            FullHttpResponse response;
            try {
                response = route(request);
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
            HttpMethod method = request.method();

            if (HttpMethod.GET.equals(method) && "/login".equals(path)) {
                return JsonResponse.text(HttpResponseStatus.OK, "<!doctype html><title>jmagnifier login</title>", "text/html");
            }
            if (HttpMethod.POST.equals(method) && "/api/login".equals(path)) {
                return login(request);
            }

            String sessionId = sessionId(request);
            boolean authenticated = sessionManager.isValid(sessionId);
            if (!authenticated) {
                if (path.startsWith("/api/")) {
                    return JsonResponse.error(HttpResponseStatus.UNAUTHORIZED, "UNAUTHORIZED", "login required");
                }
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
                response.headers().set(HttpHeaderNames.LOCATION, "/login");
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                return response;
            }

            if (HttpMethod.POST.equals(method) && "/api/logout".equals(path)) {
                sessionManager.remove(sessionId);
                FullHttpResponse response = JsonResponse.success();
                DefaultCookie cookie = new DefaultCookie(AdminSessionManager.COOKIE_NAME, "");
                cookie.setPath("/");
                cookie.setMaxAge(0);
                cookie.setHttpOnly(true);
                response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
                return response;
            }
            if (HttpMethod.GET.equals(method) && "/api/me".equals(path)) {
                Map<String, Object> data = new HashMap<>();
                data.put("authenticated", true);
                return JsonResponse.success(data);
            }
            if (HttpMethod.GET.equals(method) && "/api/runtime".equals(path)) {
                return JsonResponse.success(runtimeSummary());
            }
            if (HttpMethod.GET.equals(method) && "/api/mappings".equals(path)) {
                return JsonResponse.success(mappingList());
            }
            if (HttpMethod.POST.equals(method) && "/api/mappings".equals(path)) {
                MappingRuntime runtime = mappingManager.startMapping(readMapping(request));
                return JsonResponse.success(toMappingResponse(runtime));
            }

            Long mappingId = mappingId(path, "/api/mappings/");
            if (mappingId != null) {
                if (HttpMethod.PUT.equals(method) && path.equals("/api/mappings/" + mappingId)) {
                    MappingRuntime runtime = mappingManager.updateMapping(mappingId, readMapping(request));
                    return JsonResponse.success(toMappingResponse(runtime));
                }
                if (HttpMethod.POST.equals(method) && path.equals("/api/mappings/" + mappingId + "/start")) {
                    MappingRuntime runtime = mappingManager.startMapping(mappingId);
                    return JsonResponse.success(toMappingResponse(runtime));
                }
                if (HttpMethod.POST.equals(method) && path.equals("/api/mappings/" + mappingId + "/stop")) {
                    mappingManager.stopMapping(mappingId);
                    return JsonResponse.success();
                }
                if (HttpMethod.DELETE.equals(method) && path.equals("/api/mappings/" + mappingId)) {
                    mappingManager.deleteMapping(mappingId);
                    return JsonResponse.success();
                }
            }

            if (HttpMethod.GET.equals(method) && "/".equals(path)) {
                return JsonResponse.text(HttpResponseStatus.OK, "jmagnifier admin API", "text/plain");
            }
            return JsonResponse.error(HttpResponseStatus.NOT_FOUND, "NOT_FOUND", "route not found");
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

        private Mapping readMapping(FullHttpRequest request) {
            Map<String, Object> body = readJsonMap(request);
            Mapping mapping = Mapping.createDefaultMapping();
            if (body.containsKey("name")) {
                mapping.setName(stringValue(body.get("name")));
            }
            if (body.containsKey("enabled")) {
                mapping.setEnable(booleanValue(body.get("enabled")));
            }
            if (body.containsKey("enable")) {
                mapping.setEnable(booleanValue(body.get("enable")));
            }
            mapping.setListenPort(intValue(body.get("listenPort"), "listenPort"));
            mapping.setForwardHost(stringValue(body.get("forwardHost")));
            mapping.setForwardPort(intValue(body.get("forwardPort"), "forwardPort"));
            mapping.applyDefaults();
            return mapping;
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
            data.put("status", runtime.getStatus().name());
            data.put("activeConnections", runtime.getActiveConnections());
            data.put("lastError", runtime.getLastError());
            data.put("createdAt", null);
            data.put("updatedAt", null);
            return data;
        }

        private Long mappingId(String path, String prefix) {
            if (!path.startsWith(prefix)) {
                return null;
            }
            String rest = path.substring(prefix.length());
            String idPart = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            try {
                return Long.valueOf(idPart);
            } catch (NumberFormatException e) {
                return null;
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
