package com.core;

import com.model.DumpConfig;
import com.model.Mapping;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TCPForWardContext implements VComponent {

    private Mapping mapping;

    private String forwardHost;

    private int forwardPort;

    private ByteReadHandler byteReadHandler;

    private ByteReadHandler forwardByteReadHandler;

    private Channel remoteChannel;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();

    private boolean ownEventLoopGroup = true;

    private Runnable closeCallback;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TCPForWardContext(Mapping mapping, ByteReadHandler byteReadHandler) {
        this.mapping = mapping;
        this.forwardHost = mapping.getForwardHost();
        this.forwardPort = mapping.getForwardPort();
        this.byteReadHandler = byteReadHandler;
    }

    public TCPForWardContext(Mapping mapping, ByteReadHandler byteReadHandler, EventLoopGroup eventLoopGroup, Runnable closeCallback) {
        this(mapping, byteReadHandler);
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
                close();
                return;
            }
            remoteChannel = connect.channel();
            forwardByteReadHandler.setTarget(byteReadHandler);
            byteReadHandler.setTarget(forwardByteReadHandler);
            remoteChannel.closeFuture().addListener(x -> close());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("connect to {} interrupted", inetSocketAddress, e);
            close();
        } catch (Exception e) {
            log.error("connect to {} fail", inetSocketAddress, e);
            close();
        }
    }

    public boolean isConnected() {
        return remoteChannel != null && remoteChannel.isActive() && !closed.get();
    }

    public void close() {
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
            closeCallback.run();
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
