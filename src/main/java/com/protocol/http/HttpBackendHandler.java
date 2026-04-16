package com.protocol.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.ReferenceCountUtil;

public class HttpBackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

    private final HttpProxyBridge bridge;

    public HttpBackendHandler(HttpProxyBridge bridge) {
        super(false);
        this.bridge = bridge;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
        try {
            bridge.handleBackendResponse(msg);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        bridge.handleBackendException(cause);
    }
}
