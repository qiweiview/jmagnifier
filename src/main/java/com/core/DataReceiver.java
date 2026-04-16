package com.core;

import com.capture.PacketCaptureService;
import com.capture.PacketDirection;
import com.capture.PacketEvent;
import com.model.Mapping;
import com.store.ConnectionRepository;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataReceiver implements VComponent {

    private static final Logger log = LoggerFactory.getLogger(DataReceiver.class);

    private Mapping mapping;

    private long mappingId = -1;

    private int listenPort;

    private ServerBootstrap serverBootstrap;

    private EventLoopGroup bossGroup = NettyComponentConfig.getNioEventLoopGroup();

    private EventLoopGroup workerGroup = NettyComponentConfig.getNioEventLoopGroup();

    private EventLoopGroup clientGroup = NettyComponentConfig.getNioEventLoopGroup();

    private boolean ownEventLoopGroups = true;

    private Channel serverChannel;

    private ChannelFuture bindFuture;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Set<ConnectionContext> activeConnections = Collections.newSetFromMap(new ConcurrentHashMap<ConnectionContext, Boolean>());

    private ConnectionRepository connectionRepository;

    private PacketCaptureService packetCaptureService;


    public DataReceiver(Mapping mapping) {
        this.listenPort = mapping.getListenPort();
        this.mapping = mapping;
    }

    public DataReceiver(long mappingId, Mapping mapping, EventLoopGroup bossGroup, EventLoopGroup workerGroup, EventLoopGroup clientGroup,
                        ConnectionRepository connectionRepository, PacketCaptureService packetCaptureService) {
        this(mapping);
        this.mappingId = mappingId;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.clientGroup = clientGroup;
        this.connectionRepository = connectionRepository;
        this.packetCaptureService = packetCaptureService;
        this.ownEventLoopGroups = false;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        String printPrefix = ByteReadHandler.LOCAL_TAG + "127.0.0.1:" + listenPort;

        //create  Initializer
        ChannelInitializer<Channel> channelInitializer = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                long connectionId = connectionRepository == null
                        ? -1
                        : connectionRepository.openConnection(mappingId, mapping, channel);
                ConnectionContext connectionContext = connectionId > 0
                        ? new ConnectionContext(mappingId, connectionId, mapping, channel)
                        : new ConnectionContext(mappingId, mapping, channel);
                activeConnections.add(connectionContext);


                ByteReadHandler byteReadHandler = new ByteReadHandler(printPrefix, (bytes) -> {
                    if (bytes == null) {
                        log.warn(printPrefix + "数据为空");
                        return;
                    }

                    //打印部分逻辑
                    Boolean printRequest = mapping.getConsole().getPrintRequest();
                    if (Boolean.TRUE.equals(printRequest)) {
                        log.info(printPrefix + ":\n{}", new String(bytes));
                    } else {
                        log.warn("收到请求，但未配置打印，修改配置中的printRequest为true");
                    }

                    captureRequest(connectionContext, bytes);
                });
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast(ByteReadHandler.NAME, byteReadHandler);

                //连接器
                TCPForWardContext forWardContext = new TCPForWardContext(mappingId, mapping, connectionContext, packetCaptureService, byteReadHandler, clientGroup,
                        reason -> closeConnection(connectionContext, reason));
                connectionContext.setForwardContext(forWardContext);
                channel.closeFuture().addListener(x -> closeConnection(connectionContext, "LOCAL_CLOSED"));
                forWardContext.start();
                if (connectionRepository != null && forWardContext.isConnected()) {
                    connectionRepository.markOpen(connectionContext.getConnectionId(), forWardContext.getRemoteChannel());
                }

            }
        };

        serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)//
                .localAddress(new InetSocketAddress(listenPort))//　
                .childHandler(channelInitializer);

        try {
            bindFuture = serverBootstrap.bind().sync();
            serverChannel = bindFuture.channel();
            running.set(serverChannel != null && serverChannel.isActive());
        } catch (Exception e) {
            log.error("bind server port:" + listenPort + " fail cause:" + e);
            throw new RuntimeException("bind server port:" + listenPort + " fail", e);
        }

    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().syncUninterruptibly();
        }
        for (ConnectionContext connectionContext : new ArrayList<>(activeConnections)) {
            closeConnection(connectionContext, "MAPPING_STOPPED");
        }
        serverChannel = null;
        bindFuture = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getBoundPort() {
        if (serverChannel != null && serverChannel.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        }
        return listenPort;
    }

    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    private void closeConnection(ConnectionContext connectionContext, String reason) {
        if (connectionContext == null) {
            return;
        }
        boolean changed = connectionContext.close(reason);
        if (changed) {
            activeConnections.remove(connectionContext);
            if (connectionRepository != null && connectionContext.getConnectionId() > 0) {
                String status = "ERROR".equals(reason) ? "FAILED" : "CLOSED";
                connectionRepository.closeConnection(connectionContext.getConnectionId(), status, reason, null);
            }
        }
    }

    private void captureRequest(ConnectionContext connectionContext, byte[] bytes) {
        if (packetCaptureService == null || connectionContext == null) {
            return;
        }
        PacketEvent event = new PacketEvent();
        event.setMappingId(mappingId);
        event.setConnectionId(connectionContext.getConnectionId());
        event.setDirection(PacketDirection.REQUEST);
        event.setSequenceNo(connectionContext.nextSequenceNo());
        event.setReceivedAt(java.time.Instant.now().toString());
        event.setClientIp(connectionContext.getClientIp());
        event.setClientPort(connectionContext.getClientPort());
        InetSocketAddress listenAddress = toInetSocketAddress(connectionContext.getLocalChannel().localAddress());
        event.setListenIp(host(listenAddress));
        event.setListenPort(port(listenAddress));
        event.setTargetHost(mapping.getForwardHost());
        event.setTargetPort(mapping.getForwardPort());
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

    @Override
    public void release() {
        stop();
        if (ownEventLoopGroups) {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (clientGroup != null) {
                clientGroup.shutdownGracefully();
            }
        }
        bossGroup = null;
        workerGroup = null;
        clientGroup = null;
        serverBootstrap = null;
    }


}
