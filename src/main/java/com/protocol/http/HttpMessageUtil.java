package com.protocol.http;

import com.model.Mapping;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;

public final class HttpMessageUtil {

    private HttpMessageUtil() {
    }

    public static FullHttpRequest copyRequest(FullHttpRequest request) {
        DefaultFullHttpRequest copy = new DefaultFullHttpRequest(
                request.protocolVersion(),
                request.method(),
                request.uri(),
                Unpooled.copiedBuffer(request.content()));
        copy.headers().set(request.headers());
        copy.trailingHeaders().set(request.trailingHeaders());
        return copy;
    }

    public static FullHttpResponse copyResponse(FullHttpResponse response) {
        DefaultFullHttpResponse copy = new DefaultFullHttpResponse(
                response.protocolVersion(),
                response.status(),
                Unpooled.copiedBuffer(response.content()));
        copy.headers().set(response.headers());
        copy.trailingHeaders().set(response.trailingHeaders());
        return copy;
    }

    public static boolean isPayloadTooLarge(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof TooLongFrameException) {
                return true;
            }
            if (current instanceof DecoderException && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            current = current.getCause();
        }
        return false;
    }

    public static String hostHeaderValue(Mapping mapping) {
        String host = mapping.getForwardHost();
        int port = mapping.getForwardPort();
        boolean defaultPort = (Boolean.TRUE.equals(mapping.getForward().getTls().getEnabled()) && port == 443)
                || (!Boolean.TRUE.equals(mapping.getForward().getTls().getEnabled()) && port == 80);
        return defaultPort ? host : host + ":" + port;
    }
}
