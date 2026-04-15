package com.runtime;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

public class NettyGroups {

    private final EventLoopGroup tcpBossGroup;

    private final EventLoopGroup tcpWorkerGroup;

    private final EventLoopGroup tcpClientGroup;

    public NettyGroups() {
        this(new NioEventLoopGroup(1), new NioEventLoopGroup(), new NioEventLoopGroup());
    }

    public NettyGroups(EventLoopGroup tcpBossGroup, EventLoopGroup tcpWorkerGroup, EventLoopGroup tcpClientGroup) {
        this.tcpBossGroup = tcpBossGroup;
        this.tcpWorkerGroup = tcpWorkerGroup;
        this.tcpClientGroup = tcpClientGroup;
    }

    public EventLoopGroup getTcpBossGroup() {
        return tcpBossGroup;
    }

    public EventLoopGroup getTcpWorkerGroup() {
        return tcpWorkerGroup;
    }

    public EventLoopGroup getTcpClientGroup() {
        return tcpClientGroup;
    }

    public void shutdown() {
        tcpBossGroup.shutdownGracefully();
        tcpWorkerGroup.shutdownGracefully();
        tcpClientGroup.shutdownGracefully();
    }
}
