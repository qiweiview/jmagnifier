package com.runtime;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

public class NettyGroups {

    private final EventLoopGroup tcpBossGroup;

    private final EventLoopGroup tcpWorkerGroup;

    private final EventLoopGroup tcpClientGroup;

    private final EventLoopGroup adminBossGroup;

    private final EventLoopGroup adminWorkerGroup;

    public NettyGroups() {
        this(new NioEventLoopGroup(1), new NioEventLoopGroup(), new NioEventLoopGroup(), new NioEventLoopGroup(1), new NioEventLoopGroup());
    }

    public NettyGroups(EventLoopGroup tcpBossGroup, EventLoopGroup tcpWorkerGroup, EventLoopGroup tcpClientGroup,
                       EventLoopGroup adminBossGroup, EventLoopGroup adminWorkerGroup) {
        this.tcpBossGroup = tcpBossGroup;
        this.tcpWorkerGroup = tcpWorkerGroup;
        this.tcpClientGroup = tcpClientGroup;
        this.adminBossGroup = adminBossGroup;
        this.adminWorkerGroup = adminWorkerGroup;
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

    public EventLoopGroup getAdminBossGroup() {
        return adminBossGroup;
    }

    public EventLoopGroup getAdminWorkerGroup() {
        return adminWorkerGroup;
    }

    public void shutdown() {
        tcpBossGroup.shutdownGracefully();
        tcpWorkerGroup.shutdownGracefully();
        tcpClientGroup.shutdownGracefully();
        adminBossGroup.shutdownGracefully();
        adminWorkerGroup.shutdownGracefully();
    }
}
