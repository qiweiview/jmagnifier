package com.core;

import com.model.DumpConfig;
import com.model.Mapping;
import com.util.NettyComponentConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

@Slf4j
public class TCPForWardContext implements VComponent {

    private Mapping mapping;

    private String forwardHost;

    private int forwardPort;

    private ByteReadHandler byteReadHandler;

    private ByteReadHandler forwardByteReadHandler;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();

    public TCPForWardContext(Mapping mapping, ByteReadHandler byteReadHandler) {
        this.mapping = mapping;
        this.forwardHost = mapping.getForwardHost();
        this.forwardPort = mapping.getForwardPort();
        this.byteReadHandler = byteReadHandler;
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
            Boolean printRequest = mapping.getConsole().getPrintRequest();
            if (printRequest) {
                log.info(printPrefix + ":\n{}", new String(bytes));
            } else {
                log.warn("收到响应，但未配置打印，修改配置中的printResponse为true");
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
