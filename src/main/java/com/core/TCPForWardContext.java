package com.core;

import com.capture.PacketCaptureService;
import com.capture.PacketDirection;
import com.capture.PacketEvent;
import com.model.Mapping;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class TCPForWardContext implements VComponent {

    private static final Logger log = LoggerFactory.getLogger(TCPForWardContext.class);

    private Mapping mapping;

    private long mappingId = -1;

    private ConnectionContext connectionContext;

    private PacketCaptureService packetCaptureService;

    private String forwardHost;

    private int forwardPort;

    private ByteReadHandler byteReadHandler;

    private ByteReadHandler forwardByteReadHandler;

    private Channel remoteChannel;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();

    private boolean ownEventLoopGroup = true;

    private Consumer<String> closeCallback;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TCPForWardContext(Mapping mapping, ByteReadHandler byteReadHandler) {
        this.mapping = mapping;
        this.forwardHost = mapping.getForwardHost();
        this.forwardPort = mapping.getForwardPort();
        this.byteReadHandler = byteReadHandler;
    }

    public TCPForWardContext(long mappingId, Mapping mapping, ConnectionContext connectionContext, PacketCaptureService packetCaptureService,
                             ByteReadHandler byteReadHandler, EventLoopGroup eventLoopGroup, Consumer<String> closeCallback) {
        this(mapping, byteReadHandler);
        this.mappingId = mappingId;
        this.connectionContext = connectionContext;
        this.packetCaptureService = packetCaptureService;
        this.eventLoopGroup = eventLoopGroup;
        this.ownEventLoopGroup = false;
        this.closeCallback = closeCallback;
    }

    @Override
    public void start() {
        Bootstrap b = new Bootstrap();
        String printPrefix = ByteReadHandler.REMOTE_TAG + forwardHost + ":" + forwardPort;
        forwardByteReadHandler = new ByteReadHandler(printPrefix, (bytes) -> {
            if (bytes == null) {
                log.warn(printPrefix + "数据为空");
                return;
            }

            //打印部分逻辑
            Boolean printResponse = mapping.getConsole().getPrintResponse();
            if (Boolean.TRUE.equals(printResponse)) {
                log.info(printPrefix + ":\n{}", new String(bytes));
            } else {
                        log.warn("收到响应，但未配置打印，修改配置中的printResponse为true");
                    }

            captureResponse(bytes);
        });

        ChannelInitializer channelInitializer = new ChannelInitializer() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast(ByteReadHandler.NAME, forwardByteReadHandler);
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
                closeWithReason("ERROR");
                return;
            }
            remoteChannel = connect.channel();
            forwardByteReadHandler.setTarget(byteReadHandler);
            byteReadHandler.setTarget(forwardByteReadHandler);
            remoteChannel.closeFuture().addListener(x -> closeWithReason("REMOTE_CLOSED"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("connect to {} interrupted", inetSocketAddress, e);
            closeWithReason("ERROR");
        } catch (Exception e) {
            log.error("connect to {} fail", inetSocketAddress, e);
            closeWithReason("ERROR");
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
        if (forwardByteReadHandler != null && !forwardByteReadHandler.hasClosed()) {
            forwardByteReadHandler.closeSwap();
        }
        if (byteReadHandler != null && !byteReadHandler.hasClosed()) {
            byteReadHandler.closeSwap();
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

    private void captureResponse(byte[] bytes) {
        if (packetCaptureService == null || connectionContext == null) {
            return;
        }
        PacketEvent event = new PacketEvent();
        event.setMappingId(mappingId);
        event.setConnectionId(connectionContext.getConnectionId());
        event.setDirection(PacketDirection.RESPONSE);
        event.setSequenceNo(connectionContext.nextSequenceNo());
        event.setReceivedAt(java.time.Instant.now().toString());
        event.setClientIp(connectionContext.getClientIp());
        event.setClientPort(connectionContext.getClientPort());
        InetSocketAddress listenAddress = toInetSocketAddress(connectionContext.getLocalChannel().localAddress());
        event.setListenIp(host(listenAddress));
        event.setListenPort(port(listenAddress));
        event.setTargetHost(mapping.getForwardHost());
        event.setTargetPort(mapping.getForwardPort());
        InetSocketAddress remoteAddress = remoteChannel == null ? null : toInetSocketAddress(remoteChannel.remoteAddress());
        event.setRemoteIp(host(remoteAddress));
        event.setRemotePort(port(remoteAddress));
        packetCaptureService.capture(event, bytes);
    }

    private InetSocketAddress toInetSocketAddress(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            return (InetSocketAddress) socketAddress;
        }
        return null;
    }

    private String host(InetSocketAddress socketAddress) {
        if (socketAddress == null) {
            return null;
        }
        return socketAddress.getAddress() == null ? socketAddress.getHostString() : socketAddress.getAddress().getHostAddress();
    }

    private int port(InetSocketAddress socketAddress) {
        return socketAddress == null ? -1 : socketAddress.getPort();
    }

}
