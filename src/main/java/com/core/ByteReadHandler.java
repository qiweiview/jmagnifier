package com.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.HexDumpEncoder;

import java.net.InetSocketAddress;


public class ByteReadHandler extends ChannelInboundHandlerAdapter implements DataSwap {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public static final String NAME = "BYTE_READER";

    private ChannelHandlerContext channelHandlerContext;

    private DataSwap dataSwap;

    private int local;

    private int remote;

    private volatile boolean close = false;

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

        if (GlobalConfig.DEFAULT_INSTANT.isConsolePrint()) {
            String key = "";
            int listenPort = GlobalConfig.DEFAULT_INSTANT.getListenPort();
            if (remote == listenPort || local == listenPort) {
                key = "request";
            }

            int forwardPort = GlobalConfig.DEFAULT_INSTANT.getForwardPort();
            if (remote == forwardPort || local == forwardPort) {
                key = "response";
            }
            ByteDataProcessor.dump2Console(key, bytes);
        }

        if (GlobalConfig.DEFAULT_INSTANT.isLogDump()) {
            ByteDataProcessor.dump2File(bytes, remote, local);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("get exception cause: " + cause);
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
            logger.error("the dataSwap is null");
        }
    }


    @Override
    public void receiveData(byte[] bytes) {
        if (channelHandlerContext != null) {
            channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(bytes));
        } else {
            logger.error("the context is null");
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
