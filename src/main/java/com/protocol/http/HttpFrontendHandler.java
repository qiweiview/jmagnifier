package com.protocol.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.ReferenceCountUtil;

public class HttpFrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final HttpProxyBridge bridge;

    public HttpFrontendHandler(HttpProxyBridge bridge) {
        super(false);
        this.bridge = bridge;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
        try {
            bridge.handleFrontendRequest(msg);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        bridge.handleFrontendException(cause);
    }
}
