package com.core;

import com.model.GlobalConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;


@Slf4j
public class ByteReadHandler extends ChannelInboundHandlerAdapter implements DataSwap {


    public static final String NAME = "BYTE_READER";

    private ChannelHandlerContext channelHandlerContext;

    private DataSwap dataSwap;

    private int local;

    private int remote;

    private volatile boolean close = false;

    private int listenPort;

    private int forwardPort;

    public ByteReadHandler(int listenPort, int forwardPort) {
        this.listenPort = listenPort;
        this.forwardPort = forwardPort;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.channelHandlerContext = ctx;
        Channel channel = channelHandlerContext.channel();
        remote = ((InetSocketAddress) channel.remoteAddress()).getPort();

        local = ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf byteBuf = (ByteBuf) msg;
        byte[] bytes = ByteBufUtil.getBytes(byteBuf);
        byteBuf.discardReadBytes();
        byteBuf.release();


        handleData(bytes);

        sendData(bytes);


    }

    public void handleData(byte[] bytes) {
        if (bytes == null) {
            return;
        }

        //控制台输出
        if (GlobalConfig.DEFAULT_INSTANT.isConsolePrint()) {
            String key = "";

            if (remote == listenPort || local == listenPort) {
                key = "request";
            }


            if (remote == forwardPort || local == forwardPort) {
                key = "response";
            }
            ByteDataProcessor.dump2Console(key, bytes);
        }

        if (GlobalConfig.DEFAULT_INSTANT.isLogDump()) {
            ByteDataProcessor.dump2File(bytes, remote, local, listenPort, forwardPort);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("get exception", cause);
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        dataSwap.closeSwap();
    }

    @Override
    public void setTarget(DataSwap dataSwap) {
        if (dataSwap == this) {
            throw new RuntimeException("the option may cause stack overflow");
        }
        this.dataSwap = dataSwap;
    }

    @Override
    public void sendData(byte[] bytes) {
        if (dataSwap != null) {

            dataSwap.receiveData(bytes);
        } else {
            log.error("the dataSwap is null");
        }
    }


    @Override
    public void receiveData(byte[] bytes) {
        if (channelHandlerContext != null) {
            channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(bytes));
        } else {
            log.error("the context is null");
        }
    }

    @Override
    public boolean hasClosed() {
        return close;
    }

    @Override
    public void closeSwap() {
        close = true;
        channelHandlerContext.close().addListeners(x -> {
            if (!x.isSuccess()) {
                close = false;
            }
        });
    }


}
