package com.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class ByteReadHandler extends ChannelInboundHandlerAdapter implements DataSwap {


    public static final String NAME = "BYTE_READER";

    private ChannelHandlerContext channelHandlerContext;

    private DataSwap dataSwap;

    private Boolean consolePrint;



    private volatile boolean close = false;


    public ByteReadHandler(Boolean consolePrint) {
        this.consolePrint = consolePrint;

    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.channelHandlerContext = ctx;
        Channel channel = channelHandlerContext.channel();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf byteBuf = (ByteBuf) msg;
        byte[] bytes = ByteBufUtil.getBytes(byteBuf);
        byteBuf.discardReadBytes();
        byteBuf.release();

        //处理数据
        handleData(bytes);

        //发送数据
        sendData(bytes);


    }

    public void handleData(byte[] bytes) {
        if (bytes == null) {
            return;
        }

        if (consolePrint != null && consolePrint) {
            log.info("receive data from client:\n{}", new String(bytes));
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
