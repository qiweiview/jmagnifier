package com.core;

import com.util.NettyComponentConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class TCPForWardContext implements VComponent {

    private String forwardHost;

    private int forwardPort;

    private ByteReadHandler byteReadHandler;

    private ByteReadHandler forwardByteReadHandler;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();

    public TCPForWardContext(String forwardHost, int forwardPort, ByteReadHandler byteReadHandler) {
        this.forwardPort = forwardPort;
        this.forwardHost = forwardHost;
        this.byteReadHandler = byteReadHandler;
    }

    @Override
    public void start() {
        Bootstrap b = new Bootstrap();

        forwardByteReadHandler = new ByteReadHandler(-1, forwardPort);

        //两互绑
        forwardByteReadHandler.setTarget(byteReadHandler);
        byteReadHandler.setTarget(forwardByteReadHandler);


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
        } catch (InterruptedException e) {
            logger.error("connect to " + inetSocketAddress + "fail cause" + e);
        }
    }

    @Override
    public void release() {

    }

}
