package com.core;

import com.util.NettyComponentConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;


public  abstract class AbstractDataResolver implements VComponent {

    private int port;

    private ServerBootstrap serverBootstrap;

    private EventLoopGroup eventLoopGroup = NettyComponentConfig.getNioEventLoopGroup();


    private ByteReadHandler byteReadHandler;




    @Override
    public void release() {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        eventLoopGroup = null;
        serverBootstrap = null;
    }





    /*getter setter*/

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public ServerBootstrap getServerBootstrap() {
        return serverBootstrap;
    }

    public void setServerBootstrap(ServerBootstrap serverBootstrap) {
        this.serverBootstrap = serverBootstrap;
    }

    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    public void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
    }



    public ByteReadHandler getByteReadHandler() {
        return byteReadHandler;
    }

    public void setByteReadHandler(ByteReadHandler byteReadHandler) {
        this.byteReadHandler = byteReadHandler;
    }
}
