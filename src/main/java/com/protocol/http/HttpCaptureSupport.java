package com.protocol.http;

import com.capture.PacketCaptureService;
import com.capture.PacketDirection;
import com.capture.PacketEvent;
import com.core.ConnectionContext;
import com.model.Mapping;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

final class HttpCaptureSupport {

    private HttpCaptureSupport() {
    }

    static void captureRequest(PacketCaptureService packetCaptureService, Mapping mapping, ConnectionContext connectionContext,
                               FullHttpRequest request, byte[] payload) {
        if (packetCaptureService == null || connectionContext == null || request == null || payload == null) {
            return;
        }
        PacketEvent event = baseEvent(mapping, connectionContext, PacketDirection.REQUEST);
        event.setProtocolFamily("HTTP");
        event.setApplicationProtocol("http1");
        event.setContentType(request.headers().get(HttpHeaderNames.CONTENT_TYPE));
        event.setHttpMethod(request.method().name());
        event.setHttpUri(request.uri());
        packetCaptureService.capture(event, payload);
    }

    static void captureResponse(PacketCaptureService packetCaptureService, Mapping mapping, ConnectionContext connectionContext,
                                FullHttpResponse response, HttpRequestState requestState, byte[] payload, InetSocketAddress remoteAddress) {
        if (packetCaptureService == null || connectionContext == null || response == null || payload == null) {
            return;
        }
        PacketEvent event = baseEvent(mapping, connectionContext, PacketDirection.RESPONSE);
        event.setProtocolFamily("HTTP");
        event.setApplicationProtocol("http1");
        event.setContentType(response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        if (requestState != null) {
            event.setHttpMethod(requestState.getMethod());
            event.setHttpUri(requestState.getUri());
        }
        event.setHttpStatus(response.status().code());
        event.setRemoteIp(host(remoteAddress));
        event.setRemotePort(port(remoteAddress));
        packetCaptureService.capture(event, payload);
    }

    static byte[] toRequestBytes(FullHttpRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append(request.method().name())
                .append(' ')
                .append(request.uri())
                .append(' ')
                .append(request.protocolVersion().text())
                .append("\r\n");
        appendHeaders(builder, request.headers());
        return combine(builder, request.content());
    }

    static byte[] toResponseBytes(FullHttpResponse response) {
        StringBuilder builder = new StringBuilder();
        builder.append(response.protocolVersion().text())
                .append(' ')
                .append(response.status().code())
                .append(' ')
                .append(response.status().reasonPhrase())
                .append("\r\n");
        appendHeaders(builder, response.headers());
        return combine(builder, response.content());
    }

    private static void appendHeaders(StringBuilder builder, Iterable<Map.Entry<String, String>> headers) {
        for (Map.Entry<String, String> header : headers) {
            builder.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        builder.append("\r\n");
    }

    private static byte[] combine(StringBuilder builder, ByteBuf content) {
        byte[] head = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] body = ByteBufUtil.getBytes(content, content.readerIndex(), content.readableBytes(), false);
        byte[] payload = new byte[head.length + body.length];
        System.arraycopy(head, 0, payload, 0, head.length);
        System.arraycopy(body, 0, payload, head.length, body.length);
        return payload;
    }

    private static PacketEvent baseEvent(Mapping mapping, ConnectionContext connectionContext, PacketDirection direction) {
        PacketEvent event = new PacketEvent();
        event.setMappingId(connectionContext.getMappingId());
        event.setConnectionId(connectionContext.getConnectionId());
        event.setDirection(direction);
        event.setSequenceNo(connectionContext.nextSequenceNo());
        event.setReceivedAt(Instant.now().toString());
        event.setClientIp(connectionContext.getClientIp());
        event.setClientPort(connectionContext.getClientPort());
        InetSocketAddress listenAddress = toInetSocketAddress(connectionContext.getLocalChannel().localAddress());
        event.setListenIp(host(listenAddress));
        event.setListenPort(port(listenAddress));
        event.setTargetHost(mapping.getForwardHost());
        event.setTargetPort(mapping.getForwardPort());
        return event;
    }

    private static InetSocketAddress toInetSocketAddress(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            return (InetSocketAddress) socketAddress;
        }
        return null;
    }

    private static String host(InetSocketAddress socketAddress) {
        if (socketAddress == null) {
            return null;
        }
        return socketAddress.getAddress() == null ? socketAddress.getHostString() : socketAddress.getAddress().getHostAddress();
    }

    private static int port(InetSocketAddress socketAddress) {
        return socketAddress == null ? -1 : socketAddress.getPort();
    }
}
