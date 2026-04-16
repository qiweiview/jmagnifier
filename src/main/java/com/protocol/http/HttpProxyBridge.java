package com.protocol.http;

import com.capture.PacketCaptureService;
import com.core.ConnectionContext;
import com.model.EndpointConfig;
import com.model.Mapping;
import com.protocol.ProtocolBridge;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class HttpProxyBridge implements ProtocolBridge {

    private static final Logger log = LoggerFactory.getLogger(HttpProxyBridge.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = new LinkedHashSet<>(Arrays.asList(
            HttpHeaderNames.CONNECTION.toString(),
            "Proxy-Connection",
            HttpHeaderNames.KEEP_ALIVE.toString(),
            HttpHeaderNames.TRANSFER_ENCODING.toString(),
            HttpHeaderNames.UPGRADE.toString(),
            HttpHeaderNames.TRAILER.toString(),
            HttpHeaderNames.PROXY_AUTHENTICATE.toString(),
            HttpHeaderNames.PROXY_AUTHORIZATION.toString(),
            HttpHeaderNames.TE.toString()
    ));

    private final Mapping mapping;

    private final ConnectionContext connectionContext;

    private final PacketCaptureService packetCaptureService;

    private final HttpFrontendHandler frontendHandler;

    private final HttpBackendHandler backendHandler;

    private final Queue<HttpRequestState> requests = new ConcurrentLinkedQueue<>();

    private volatile Channel remoteChannel;

    private volatile Throwable forwardConnectFailure;

    public HttpProxyBridge(Mapping mapping, ConnectionContext connectionContext, PacketCaptureService packetCaptureService) {
        this.mapping = mapping;
        this.connectionContext = connectionContext;
        this.packetCaptureService = packetCaptureService;
        this.frontendHandler = new HttpFrontendHandler(this);
        this.backendHandler = new HttpBackendHandler(this);
    }

    @Override
    public ChannelHandler getListenHandler() {
        return frontendHandler;
    }

    @Override
    public ChannelHandler getForwardHandler() {
        return backendHandler;
    }

    @Override
    public void onForwardChannelActive(Channel channel) {
        this.remoteChannel = channel;
        this.forwardConnectFailure = null;
    }

    @Override
    public void onForwardConnectFailure(Throwable cause) {
        this.forwardConnectFailure = cause;
    }

    void handleFrontendRequest(FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            sendLocalError(HttpResponseStatus.BAD_REQUEST, true);
            return;
        }

        Channel target = remoteChannel;
        if (target == null || !target.isActive()) {
            log.warn("upstream unavailable for {} {} cause:{}", request.method(), request.uri(),
                    forwardConnectFailure == null ? "unknown" : forwardConnectFailure.getMessage());
            sendLocalError(HttpResponseStatus.BAD_GATEWAY, true);
            return;
        }

        FullHttpRequest outbound = HttpMessageUtil.copyRequest(request);
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        requests.add(new HttpRequestState(request.method().name(), request.uri(), keepAlive));
        rewriteRequest(outbound);
        byte[] payload = HttpCaptureSupport.toRequestBytes(outbound);
        HttpCaptureSupport.captureRequest(packetCaptureService, mapping, connectionContext, outbound, payload);
        log.info("[http][request] {} {} Host={} size={}",
                outbound.method().name(),
                outbound.uri(),
                outbound.headers().get(HttpHeaderNames.HOST, "-"),
                outbound.content().readableBytes());
        target.writeAndFlush(outbound).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.warn("forward http request failed:{}", future.cause() == null ? "unknown" : future.cause().getMessage());
                sendLocalError(HttpResponseStatus.BAD_GATEWAY, true);
            }
        });
    }

    void handleBackendResponse(FullHttpResponse response) {
        if (!response.decoderResult().isSuccess()) {
            sendLocalError(HttpResponseStatus.BAD_GATEWAY, true);
            return;
        }
        HttpRequestState requestState = requests.poll();
        FullHttpResponse outbound = HttpMessageUtil.copyResponse(response);
        rewriteResponse(outbound, requestState);
        byte[] payload = HttpCaptureSupport.toResponseBytes(outbound);
        InetSocketAddress remoteAddress = remoteChannel == null ? null : (InetSocketAddress) remoteChannel.remoteAddress();
        HttpCaptureSupport.captureResponse(packetCaptureService, mapping, connectionContext, outbound, requestState, payload, remoteAddress);
        log.info("[http][response] {} {} Content-Type={} size={}",
                outbound.status().code(),
                outbound.status().reasonPhrase(),
                outbound.headers().get(HttpHeaderNames.CONTENT_TYPE, "-"),
                outbound.content().readableBytes());

        final boolean keepAlive = requestState != null && requestState.isKeepAlive() && HttpUtil.isKeepAlive(response);
        connectionContext.getLocalChannel().writeAndFlush(outbound).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                connectionContext.close("ERROR");
                return;
            }
            if (!keepAlive) {
                connectionContext.close("HTTP_CLOSE");
            }
        });
    }

    void handleFrontendException(Throwable cause) {
        if (HttpMessageUtil.isPayloadTooLarge(cause)) {
            sendLocalError(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, true);
        } else {
            log.warn("frontend http handler failed:{}", cause == null ? "unknown" : cause.getMessage());
            sendLocalError(HttpResponseStatus.BAD_REQUEST, true);
        }
    }

    void handleBackendException(Throwable cause) {
        log.warn("backend http handler failed:{}", cause == null ? "unknown" : cause.getMessage());
        sendLocalError(HttpResponseStatus.BAD_GATEWAY, true);
    }

    private void rewriteRequest(FullHttpRequest request) {
        removeHopByHopHeaders(request);
        if (Boolean.TRUE.equals(mapping.getHttp().getRewriteHost())) {
            request.headers().set(HttpHeaderNames.HOST, HttpMessageUtil.hostHeaderValue(mapping));
        }
        if (Boolean.TRUE.equals(mapping.getHttp().getAddXForwardedHeaders())) {
            String clientIp = connectionContext.getClientIp();
            if (clientIp != null && clientIp.trim().length() > 0) {
                String current = request.headers().get("X-Forwarded-For");
                request.headers().set("X-Forwarded-For", current == null || current.trim().length() == 0
                        ? clientIp
                        : current + ", " + clientIp);
            }
            request.headers().set("X-Forwarded-Proto", Boolean.TRUE.equals(mapping.getListen().getTls().getEnabled()) ? "https" : "http");
            request.headers().set("X-Forwarded-Port", String.valueOf(mapping.getListenPort()));
        }
        HttpUtil.setContentLength(request, request.content().readableBytes());
        HttpUtil.setKeepAlive(request, HttpUtil.isKeepAlive(request));
    }

    private void rewriteResponse(FullHttpResponse response, HttpRequestState requestState) {
        removeHopByHopHeaders(response);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        HttpUtil.setKeepAlive(response, requestState != null && requestState.isKeepAlive());
    }

    private void removeHopByHopHeaders(HttpObject object) {
        if (!(object instanceof LastHttpContent)) {
            return;
        }
        HttpHeaders headers = object instanceof FullHttpRequest
                ? ((FullHttpRequest) object).headers()
                : ((FullHttpResponse) object).headers();
        for (String header : HOP_BY_HOP_HEADERS) {
            headers.remove(header);
        }
        String connectionHeader = headers.get(HttpHeaderNames.CONNECTION);
        if (connectionHeader != null) {
            for (String token : connectionHeader.split(",")) {
                headers.remove(token.trim());
            }
        }
    }

    private void sendLocalError(HttpResponseStatus status, boolean close) {
        Channel localChannel = connectionContext.getLocalChannel();
        if (localChannel == null || !localChannel.isActive()) {
            connectionContext.close("ERROR");
            return;
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, close ? HttpHeaderValues.CLOSE : HttpHeaderValues.KEEP_ALIVE);
        localChannel.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (close) {
                connectionContext.close("ERROR");
            }
        });
    }
}
