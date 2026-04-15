package com.core;

import com.model.DumpConfig;
import com.model.Mapping;
import com.store.ConnectionRepository;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class DataReceiver implements VComponent {

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


    public DataReceiver(Mapping mapping) {
        this.listenPort = mapping.getListenPort();
        this.mapping = mapping;
    }

    public DataReceiver(long mappingId, Mapping mapping, EventLoopGroup bossGroup, EventLoopGroup workerGroup, EventLoopGroup clientGroup, ConnectionRepository connectionRepository) {
        this(mapping);
        this.mappingId = mappingId;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.clientGroup = clientGroup;
        this.connectionRepository = connectionRepository;
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


                    //dump部分逻辑
                    DumpConfig dumpConfig = mapping.getDump();
                    if (Boolean.TRUE.equals(dumpConfig.getEnable())) {
                        String dumpPath = dumpConfig.getDumpPath();

                        String file = dumpPath + File.separator + mapping.dumpName();
                        try {
                            FileUtils.writeStringToFile(new File(file), printPrefix + ":\n", true);
                            FileUtils.writeByteArrayToFile(new File(file), bytes, true);
                        } catch (IOException e) {
                            log.warn("dump数据写入失败:{}", e.getMessage());
                        }
                    }
                });
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast(ByteReadHandler.NAME, byteReadHandler);

                //连接器
                TCPForWardContext forWardContext = new TCPForWardContext(mapping, byteReadHandler, clientGroup,
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
