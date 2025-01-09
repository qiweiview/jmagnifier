package com.core;

import com.model.DumpConfig;
import com.model.Mapping;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

@Slf4j
public class DataReceiver implements VComponent {

    private Mapping mapping;

    private int listenPort;

    private ServerBootstrap serverBootstrap;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();


    public DataReceiver(Mapping mapping) {
        this.listenPort = mapping.getListenPort();
        this.mapping = mapping;
    }

    @Override
    public void start() {
        String printPrefix = ByteReadHandler.LOCAL_TAG + "127.0.0.1:" + listenPort;

        //create  Initializer
        ChannelInitializer<Channel> channelInitializer = new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {


                ByteReadHandler byteReadHandler = new ByteReadHandler(printPrefix, (bytes) -> {
                    if (bytes == null) {
                        log.warn(printPrefix + "数据为空");
                        return;
                    }

                    //打印部分逻辑
                    Boolean printRequest = mapping.getConsole().getPrintRequest();
                    if (printRequest) {
                        log.info(printPrefix + ":\n{}", new String(bytes));
                    } else {
                        log.warn("收到请求，但未配置打印，修改配置中的printRequest为true");
                    }


                    //dump部分逻辑
                    DumpConfig dumpConfig = mapping.getDump();
                    if (dumpConfig.getEnable()) {
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
                TCPForWardContext forWardContext = new TCPForWardContext(mapping, byteReadHandler);
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
