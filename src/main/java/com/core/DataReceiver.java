package com.core;

import com.capture.PacketCaptureService;
import com.model.Mapping;
import com.protocol.DefaultProtocolPipelineFactory;
import com.protocol.ProtocolBridge;
import com.protocol.ProtocolPipelineFactory;
import com.store.ConnectionRepository;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
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

    private ProtocolPipelineFactory protocolPipelineFactory;


    public DataReceiver(Mapping mapping) {
        this.listenPort = mapping.getListenPort();
        this.mapping = mapping;
        this.protocolPipelineFactory = new DefaultProtocolPipelineFactory();
    }

    public DataReceiver(long mappingId, Mapping mapping, EventLoopGroup bossGroup, EventLoopGroup workerGroup, EventLoopGroup clientGroup,
                        ConnectionRepository connectionRepository, PacketCaptureService packetCaptureService,
                        ProtocolPipelineFactory protocolPipelineFactory) {
        this(mapping);
        this.mappingId = mappingId;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.clientGroup = clientGroup;
        this.connectionRepository = connectionRepository;
        this.packetCaptureService = packetCaptureService;
        this.protocolPipelineFactory = protocolPipelineFactory;
        this.ownEventLoopGroups = false;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
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
                ProtocolBridge protocolBridge = protocolPipelineFactory.createBridge(mapping, connectionContext, packetCaptureService);
                protocolPipelineFactory.initListenPipeline(channel, mapping, connectionContext, protocolBridge);

                //连接器
                TCPForWardContext forWardContext = new TCPForWardContext(mapping, connectionContext, protocolBridge,
                        protocolPipelineFactory, clientGroup, reason -> closeConnection(connectionContext, reason));
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
