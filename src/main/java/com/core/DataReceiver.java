package com.core;

import com.model.Mapping;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class DataReceiver implements VComponent {

    private Mapping mapping;

    private String forwardHost;

    private int listenPort;

    private int forwardPort;

    private ServerBootstrap serverBootstrap;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();


    public DataReceiver(Mapping mapping) {
        this.forwardHost = mapping.getForwardHost();
        this.listenPort = mapping.getListenPort();
        this.forwardPort = mapping.getForwardPort();
        this.mapping = mapping;
    }

    @Override
    public void start() {


        //create  Initializer
        ChannelInitializer<Channel> channelInitializer = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {


                ByteReadHandler byteReadHandler = new ByteReadHandler(mapping.getConsolePrint());
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast(ByteReadHandler.NAME, byteReadHandler);

                //连接器
                TCPForWardContext forWardContext = new TCPForWardContext(forwardHost, forwardPort, byteReadHandler);
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
            log.error("bind server port:" + listenPort + " fail cause:" + e);
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
