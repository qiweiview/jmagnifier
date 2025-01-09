package com.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;


@Slf4j
public class ByteReadHandler extends ChannelInboundHandlerAdapter implements DataSwap {

    public static final String LOCAL_TAG = "[local]";

    public static final String REMOTE_TAG = "[remote]";

    public static final String NAME = "BYTE_READER";

    private String printPrefix;

    private ChannelHandlerContext channelHandlerContext;

    private DataSwap dataSwap;

    private Consumer<byte[]> consumer;


    private volatile boolean close = false;


    public ByteReadHandler(String printPrefix, Consumer<byte[]> consumer) {
        this.consumer = consumer;
        this.printPrefix = printPrefix;

    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.channelHandlerContext = ctx;
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf byteBuf = (ByteBuf) msg;
        byte[] bytes = ByteBufUtil.getBytes(byteBuf);
        byteBuf.discardReadBytes();
        byteBuf.release();

        //处理数据
        if (consumer != null) {
            consumer.accept(bytes);
        }

        //发送数据
        sendData(bytes);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error(printPrefix + "get exception", cause);
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        dataSwap.closeSwap();
        log.warn(printPrefix + "连接关闭");
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
