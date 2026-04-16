package com.protocol;

import com.capture.PacketCaptureService;
import com.core.ConnectionContext;
import com.model.Mapping;
import io.netty.channel.Channel;

public interface ProtocolPipelineFactory {

    ProtocolBridge createBridge(Mapping mapping, ConnectionContext connectionContext, PacketCaptureService packetCaptureService);

    void initListenPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge);

    void initForwardPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge);
}
