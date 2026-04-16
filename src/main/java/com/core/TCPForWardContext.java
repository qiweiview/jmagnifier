package com.core;

import com.model.Mapping;
import com.protocol.ProtocolBridge;
import com.protocol.ProtocolPipelineFactory;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class TCPForWardContext implements VComponent {

    private static final Logger log = LoggerFactory.getLogger(TCPForWardContext.class);

    private Mapping mapping;

    private ConnectionContext connectionContext;

    private String forwardHost;

    private int forwardPort;

    private ProtocolBridge protocolBridge;

    private ProtocolPipelineFactory protocolPipelineFactory;

    private Channel remoteChannel;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();

    private boolean ownEventLoopGroup = true;

    private Consumer<String> closeCallback;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TCPForWardContext(Mapping mapping, ConnectionContext connectionContext, ProtocolBridge protocolBridge,
                             ProtocolPipelineFactory protocolPipelineFactory) {
        this.mapping = mapping;
        this.connectionContext = connectionContext;
        this.forwardHost = mapping.getForwardHost();
        this.forwardPort = mapping.getForwardPort();
        this.protocolBridge = protocolBridge;
        this.protocolPipelineFactory = protocolPipelineFactory;
    }

    public TCPForWardContext(Mapping mapping, ConnectionContext connectionContext, ProtocolBridge protocolBridge,
                             ProtocolPipelineFactory protocolPipelineFactory, EventLoopGroup eventLoopGroup,
                             Consumer<String> closeCallback) {
        this(mapping, connectionContext, protocolBridge, protocolPipelineFactory);
        this.eventLoopGroup = eventLoopGroup;
        this.ownEventLoopGroup = false;
        this.closeCallback = closeCallback;
    }

    @Override
    public void start() {
        Bootstrap b = new Bootstrap();
        ChannelInitializer channelInitializer = new ChannelInitializer() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                protocolPipelineFactory.initForwardPipeline(channel, mapping, connectionContext, protocolBridge);
            }
        };

        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)//
                .handler(channelInitializer);


        InetSocketAddress inetSocketAddress = new InetSocketAddress(forwardHost, this.forwardPort);
        ChannelFuture connect = b.connect(inetSocketAddress);

        try {
            connect.sync();
            if (!connect.isSuccess()) {
                log.error("connect to {} fail cause:{}", inetSocketAddress, connect.cause() == null ? "unknown" : connect.cause().getMessage());
                protocolBridge.onForwardConnectFailure(connect.cause());
                if (mapping.isRawTcpPath()) {
                    closeWithReason("ERROR");
                }
                return;
            }
            remoteChannel = connect.channel();
            protocolBridge.onForwardChannelActive(remoteChannel);
            remoteChannel.closeFuture().addListener(x -> closeWithReason("REMOTE_CLOSED"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("connect to {} interrupted", inetSocketAddress, e);
            protocolBridge.onForwardConnectFailure(e);
            if (mapping.isRawTcpPath()) {
                closeWithReason("ERROR");
            }
        } catch (Exception e) {
            log.error("connect to {} fail", inetSocketAddress, e);
            protocolBridge.onForwardConnectFailure(e);
            if (mapping.isRawTcpPath()) {
                closeWithReason("ERROR");
            }
        }
    }

    public boolean isConnected() {
        return remoteChannel != null && remoteChannel.isActive() && !closed.get();
    }

    public Channel getRemoteChannel() {
        return remoteChannel;
    }

    public void close() {
        closeWithReason("REMOTE_CLOSED");
    }

    public void closeWithReason(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (remoteChannel != null && remoteChannel.isOpen()) {
            remoteChannel.close();
        }
        if (closeCallback != null) {
            closeCallback.accept(reason);
        }
    }

    @Override
    public void release() {
        close();
        if (ownEventLoopGroup && eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }

}
