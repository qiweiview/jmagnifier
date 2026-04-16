package com.protocol.raw;

import com.capture.PacketCaptureService;
import com.capture.PacketDirection;
import com.capture.PacketEvent;
import com.core.ByteReadHandler;
import com.core.ConnectionContext;
import com.model.Mapping;
import com.protocol.ProtocolBridge;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;

public class RawTcpBridge implements ProtocolBridge {

    private static final Logger log = LoggerFactory.getLogger(RawTcpBridge.class);

    private final Mapping mapping;

    private final ConnectionContext connectionContext;

    private final PacketCaptureService packetCaptureService;

    private final ByteReadHandler localHandler;

    private final ByteReadHandler remoteHandler;

    private volatile Channel remoteChannel;

    public RawTcpBridge(final Mapping mapping, final ConnectionContext connectionContext, final PacketCaptureService packetCaptureService) {
        this.mapping = mapping;
        this.connectionContext = connectionContext;
        this.packetCaptureService = packetCaptureService;

        final String localPrintPrefix = ByteReadHandler.LOCAL_TAG + "127.0.0.1:" + mapping.getListenPort();
        final String remotePrintPrefix = ByteReadHandler.REMOTE_TAG + mapping.getForwardHost() + ":" + mapping.getForwardPort();

        this.localHandler = new ByteReadHandler(localPrintPrefix, bytes -> {
            if (bytes == null) {
                log.warn("{}数据为空", localPrintPrefix);
                return;
            }
            if (Boolean.TRUE.equals(mapping.getConsole().getPrintRequest())) {
                log.info("{}:\n{}", localPrintPrefix, new String(bytes));
            } else {
                log.warn("收到请求，但未配置打印，修改配置中的printRequest为true");
            }
            captureRequest(bytes);
        });

        this.remoteHandler = new ByteReadHandler(remotePrintPrefix, bytes -> {
            if (bytes == null) {
                log.warn("{}数据为空", remotePrintPrefix);
                return;
            }
            if (Boolean.TRUE.equals(mapping.getConsole().getPrintResponse())) {
                log.info("{}:\n{}", remotePrintPrefix, new String(bytes));
            } else {
                log.warn("收到响应，但未配置打印，修改配置中的printResponse为true");
            }
            captureResponse(bytes);
        });
    }

    @Override
    public ChannelHandler getListenHandler() {
        return localHandler;
    }

    @Override
    public ChannelHandler getForwardHandler() {
        return remoteHandler;
    }

    @Override
    public void onForwardChannelActive(Channel channel) {
        this.remoteChannel = channel;
        remoteHandler.setTarget(localHandler);
        localHandler.setTarget(remoteHandler);
    }

    @Override
    public void onForwardConnectFailure(Throwable cause) {
        localHandler.closeSwap();
        remoteHandler.closeSwap();
    }

    private void captureRequest(byte[] bytes) {
        if (packetCaptureService == null || connectionContext == null) {
            return;
        }
        PacketEvent event = new PacketEvent();
        event.setMappingId(connectionContext.getMappingId());
        event.setConnectionId(connectionContext.getConnectionId());
        event.setDirection(PacketDirection.REQUEST);
        event.setSequenceNo(connectionContext.nextSequenceNo());
        event.setReceivedAt(Instant.now().toString());
        event.setClientIp(connectionContext.getClientIp());
        event.setClientPort(connectionContext.getClientPort());
        InetSocketAddress listenAddress = toInetSocketAddress(connectionContext.getLocalChannel().localAddress());
        event.setListenIp(host(listenAddress));
        event.setListenPort(port(listenAddress));
        event.setTargetHost(mapping.getForwardHost());
        event.setTargetPort(mapping.getForwardPort());
        packetCaptureService.capture(event, bytes);
    }

    private void captureResponse(byte[] bytes) {
        if (packetCaptureService == null || connectionContext == null) {
            return;
        }
        PacketEvent event = new PacketEvent();
        event.setMappingId(connectionContext.getMappingId());
        event.setConnectionId(connectionContext.getConnectionId());
        event.setDirection(PacketDirection.RESPONSE);
        event.setSequenceNo(connectionContext.nextSequenceNo());
        event.setReceivedAt(Instant.now().toString());
        event.setClientIp(connectionContext.getClientIp());
        event.setClientPort(connectionContext.getClientPort());
        InetSocketAddress listenAddress = toInetSocketAddress(connectionContext.getLocalChannel().localAddress());
        event.setListenIp(host(listenAddress));
        event.setListenPort(port(listenAddress));
        event.setTargetHost(mapping.getForwardHost());
        event.setTargetPort(mapping.getForwardPort());
        InetSocketAddress remoteAddress = remoteChannel == null ? null : toInetSocketAddress(remoteChannel.remoteAddress());
        event.setRemoteIp(host(remoteAddress));
        event.setRemotePort(port(remoteAddress));
        packetCaptureService.capture(event, bytes);
    }

    private InetSocketAddress toInetSocketAddress(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            return (InetSocketAddress) socketAddress;
        }
        return null;
    }

    private String host(InetSocketAddress socketAddress) {
        if (socketAddress == null) {
            return null;
        }
        return socketAddress.getAddress() == null ? socketAddress.getHostString() : socketAddress.getAddress().getHostAddress();
    }

    private int port(InetSocketAddress socketAddress) {
        return socketAddress == null ? -1 : socketAddress.getPort();
    }
}
