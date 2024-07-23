package com.core;

import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class DataReceiver implements VComponent {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private String forwardHost;

    private int listenPort;

    private int forwardPort;

    private ServerBootstrap serverBootstrap;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();


    public DataReceiver(int listenPort,String forwardHost, int forwardPort) {
        this.forwardHost = forwardHost;
        this.listenPort = listenPort;
        this.forwardPort = forwardPort;
    }

    @Override
    public void start() {


        //create  Initializer
        ChannelInitializer<Channel> channelInitializer = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {


                ByteReadHandler byteReadHandler = new ByteReadHandler(listenPort, forwardPort);
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast(ByteReadHandler.NAME, byteReadHandler);

                ForWardContext forWardContext = new ForWardContext(forwardHost,forwardPort,byteReadHandler);
                forWardContext.start();

            }
        };

        serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(eventLoopGroup)
                .channel(NioServerSocketChannel.class)//
                .localAddress(new InetSocketAddress(listenPort))//　
                .childHandler(channelInitializer);

        try {
            serverBootstrap.bind().sync();
        } catch (Exception e) {
            logger.error("bind server port:" + listenPort + " fail cause:" + e);
        }

    }

    @Override
    public void release() {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        eventLoopGroup = null;
        serverBootstrap = null;
    }


}
