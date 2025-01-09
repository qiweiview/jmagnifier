package com.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class ByteReadHandler extends ChannelInboundHandlerAdapter implements DataSwap {

    public static final String LOCAL_TAG = "[local]";

    public static final String REMOTE_TAG = "[remote]";


    public static final String NAME = "BYTE_READER";

    private String printPrefix;

    private ChannelHandlerContext channelHandlerContext;

    private DataSwap dataSwap;

    private Boolean consolePrint;



    private volatile boolean close = false;


    public ByteReadHandler(String printPrefix, Boolean consolePrint) {
        this.consolePrint = consolePrint;
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
        handleData(bytes);

        //发送数据
        sendData(bytes);
    }

    public void handleData(byte[] bytes) {
        if (bytes == null) {
            log.warn(printPrefix + "数据为空");
            return;
        }

        if (consolePrint != null && consolePrint) {
            log.info(printPrefix + ":\n{}", new String(bytes));
        } else {
            if (printPrefix.contains(LOCAL_TAG)) {
                log.warn("收到请求，但未配置打印，修改配置中的printRequest为true");
            }

            if (printPrefix.contains(REMOTE_TAG)) {
                log.warn("收到响应，但未配置打印，修改配置中的printResponse为true");
            }
        }

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
