package com.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import sun.misc.HexDumpEncoder;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 入参处理器
 */
@Slf4j
public class ByteReadHandler extends ChannelInboundHandlerAdapter implements DataSwap {
    private static final ExecutorService executorService = Executors.newFixedThreadPool(2);

    private static final HexDumpEncoder hexDumpEncoder = new HexDumpEncoder();

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

    /**
     * 读取消息
     *
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf byteBuf = (ByteBuf) msg;
        byte[] bytes = ByteBufUtil.getBytes(byteBuf);
        byteBuf.discardReadBytes();
        byteBuf.release();


        if (byteBuf != null) {
            //处理入参数据

            ByteDataProcessor.checkUnifiedOutput(() -> {
                //todo 打印详情

                String key = "";
                int listenPort = GlobalConfig.DEFAULT_INSTANT.getListenPort();
                if (remote == listenPort || local == listenPort) {
                    key = "request";
                }

                int forwardPort = GlobalConfig.DEFAULT_INSTANT.getForwardPort();
                if (remote == forwardPort || local == forwardPort) {
                    key = "response";
                }

                HexDumpEncoder hexDumpEncoder = new HexDumpEncoder();
                String encode = hexDumpEncoder.encode(bytes);
                ByteDataProcessor.dump2Console(key, encode);

            }, () -> {
                //todo 存储详情
                StringBuilder stringBuilder = new StringBuilder();

                String key = "";
                int listenPort = GlobalConfig.DEFAULT_INSTANT.getListenPort();
                if (remote == listenPort || local == listenPort) {
                    key = "request";
                }

                int forwardPort = GlobalConfig.DEFAULT_INSTANT.getForwardPort();
                if (remote == forwardPort || local == forwardPort) {
                    key = "response";
                }

                if (GlobalConfig.DEFAULT_INSTANT.isDumpHex()) {
                    stringBuilder.append("========================= hex dump " + key + " ====================\n\r");
                    stringBuilder.append(hexDumpEncoder.encode(bytes) + "\n\r");

                }

                if (GlobalConfig.DEFAULT_INSTANT.isDumpString()) {
                    stringBuilder.append("========================= str dump " + key + " ====================\n\r");
                    stringBuilder.append(new String(bytes) + "\n\r");
                }

                byte[] dump = stringBuilder.toString().getBytes();
                ByteDataProcessor.dump2File(dump);

            });
        }

        //发送数据至下游
        sendData(bytes);


    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("get exception cause: " + cause);
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
            //todo 响应写出
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
