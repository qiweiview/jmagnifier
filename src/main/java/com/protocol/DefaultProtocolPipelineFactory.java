package com.protocol;

import com.capture.PacketCaptureService;
import com.core.ConnectionContext;
import com.model.Mapping;
import com.protocol.raw.RawTcpBridge;
import io.netty.channel.Channel;

public class DefaultProtocolPipelineFactory implements ProtocolPipelineFactory {

    @Override
    public ProtocolBridge createBridge(Mapping mapping, ConnectionContext connectionContext, PacketCaptureService packetCaptureService) {
        mapping.applyDefaults();
        if (mapping.isRawTcpPath()) {
            return new RawTcpBridge(mapping, connectionContext, packetCaptureService);
        }
        throw new UnsupportedOperationException("protocol pipeline is not implemented yet: "
                + mapping.getListenMode() + " -> " + mapping.getForwardMode());
    }

    @Override
    public void initListenPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge) {
        channel.pipeline().addLast(bridge.getListenHandler());
    }

    @Override
    public void initForwardPipeline(Channel channel, Mapping mapping, ConnectionContext connectionContext, ProtocolBridge bridge) {
        channel.pipeline().addLast(bridge.getForwardHandler());
    }
}
