package com.protocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

public interface ProtocolBridge {

    ChannelHandler getListenHandler();

    ChannelHandler getForwardHandler();

    void onForwardChannelActive(Channel channel);

    void onForwardConnectFailure(Throwable cause);
}
