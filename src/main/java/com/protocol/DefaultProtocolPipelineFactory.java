package com.protocol;

import com.capture.PacketCaptureService;
import com.core.ConnectionContext;
import com.model.Mapping;
import com.protocol.http.HttpProxyBridge;
import com.protocol.raw.RawTcpBridge;
import com.protocol.tls.SslContextFactory;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;

public class DefaultProtocolPipelineFactory implements ProtocolPipelineFactory {

    private final SslContextFactory sslContextFactory = new SslContextFactory();

    @Override
    public ProtocolBridge createBridge(Mapping mapping, ConnectionContext connectionContext, PacketCaptureService packetCaptureService) {
        mapping.applyDefaults();
        if (mapping.isRawTcpPath()) {
            return new RawTcpBridge(mapping, connectionContext, packetCaptureService);
        }
        if (mapping.isHttpPath()) {
            return new HttpProxyBridge(mapping, connectionContext, packetCaptureService);
        }
        throw new UnsupportedOperationException("unsupported protocol pipeline: " + mapping.getListenMode() + " -> " + mapping.getForwardMode());
    }

    @Override
    public void initListenPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge) {
        if (mapping.isRawTcpPath()) {
            channel.pipeline().addLast(bridge.getListenHandler());
            return;
        }
        if (mapping.isHttpPath()) {
            SslContext sslContext = sslContextFactory.createServerContext(mapping);
            if (sslContext != null) {
                channel.pipeline().addLast(sslContext.newHandler(channel.alloc()));
            }
            channel.pipeline().addLast(new HttpServerCodec());
            channel.pipeline().addLast(new HttpObjectAggregator(mapping.getHttp().getMaxObjectSizeBytes()));
            channel.pipeline().addLast(bridge.getListenHandler());
            return;
        }
        throw new UnsupportedOperationException("unsupported listen pipeline: " + mapping.getListenMode());
    }

    @Override
    public void initForwardPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge) {
        if (mapping.isRawTcpPath()) {
            channel.pipeline().addLast(bridge.getForwardHandler());
            return;
        }
        if (mapping.isHttpPath()) {
            SslContext sslContext = sslContextFactory.createClientContext(mapping);
            if (sslContext != null) {
                String sniHost = mapping.getForward().getTls().getSniHost();
                String peerHost = sniHost == null || sniHost.trim().length() == 0 ? mapping.getForwardHost() : sniHost.trim();
                channel.pipeline().addLast(sslContext.newHandler(channel.alloc(), peerHost, mapping.getForwardPort()));
            }
            channel.pipeline().addLast(new HttpClientCodec());
            channel.pipeline().addLast(new HttpObjectAggregator(mapping.getHttp().getMaxObjectSizeBytes()));
            channel.pipeline().addLast(bridge.getForwardHandler());
            return;
        }
        throw new UnsupportedOperationException("unsupported forward pipeline: " + mapping.getForwardMode());
    }
}
