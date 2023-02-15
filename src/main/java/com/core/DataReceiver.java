package com.core;

import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class DataReceiver implements VComponent {

    private GlobalConfig globalConfig;

//    private String forwardHost;
//
//    private int listenPort;
//
//    private int forwardPort;

    private ServerBootstrap serverBootstrap;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();


    public DataReceiver(GlobalConfig globalConfig) {
        this.globalConfig = globalConfig;
//        this.forwardHost = forwardHost;
//        this.listenPort = listenPort;
//        this.forwardPort = forwardPort;
    }

    @Override
    public void start() {


        //create  Initializer
        ChannelInitializer<Channel> channelInitializer = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {

                ChannelPipeline pipeline = channel.pipeline();
                if ("HTTP".equalsIgnoreCase(globalConfig.getType())) {
                    //todo http包处理

                    String http = "http";//HttpServerCodec
                    String oag = "oag";//HttpObjectAggregator
                    HttpReadHandle httpReadHandle = new HttpReadHandle(globalConfig);
                    pipeline.addLast(http, new HttpServerCodec());
                    pipeline.addAfter(http, oag, new HttpObjectAggregator(2 * 1024 * 1024));//限制缓冲最大值为2mb
                    pipeline.addAfter(oag, HttpReadHandle.NAME, httpReadHandle);
//                    HTTPForWardContext httpForWardContext = new HTTPForWardContext(globalConfig.getForwardHost(), globalConfig.getForwardPort(), httpReadHandle);
//                    httpForWardContext.start();
                } else {
                    //TODO 默认tcp包处理
                    ByteReadHandler byteReadHandler = new ByteReadHandler();
                    pipeline.addLast(ByteReadHandler.NAME, byteReadHandler);
                    TCPForWardContext TCPForWardContext = new TCPForWardContext(globalConfig.getForwardHost(), globalConfig.getForwardPort(), byteReadHandler);
                    TCPForWardContext.start();
                }


            }
        };

        serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(eventLoopGroup)
                .channel(NioServerSocketChannel.class)//
                .localAddress(new InetSocketAddress(globalConfig.getListenPort()))//　
                .childHandler(channelInitializer);

        try {
            serverBootstrap.bind().sync();
        } catch (Exception e) {
            log.error("bind server port:" + globalConfig.getListenPort() + " fail cause:" + e);
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
