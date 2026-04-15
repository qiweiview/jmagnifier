package com.core;

import com.model.Mapping;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ConnectionContext {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

    private final long connectionId;

    private final long mappingId;

    private final Mapping mappingSnapshot;

    private final Channel localChannel;

    private final String clientIp;

    private final int clientPort;

    private final Instant openedAt;

    private volatile TCPForWardContext forwardContext;

    private volatile Instant closedAt;

    private volatile String closeReason;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConnectionContext(long mappingId, Mapping mappingSnapshot, Channel localChannel) {
        this(mappingId, ID_GENERATOR.getAndIncrement(), mappingSnapshot, localChannel);
    }

    public ConnectionContext(long mappingId, long connectionId, Mapping mappingSnapshot, Channel localChannel) {
        this.mappingId = mappingId;
        this.connectionId = connectionId;
        this.mappingSnapshot = mappingSnapshot;
        this.localChannel = localChannel;
        this.openedAt = Instant.now();
        SocketAddress remoteAddress = localChannel.remoteAddress();
        if (remoteAddress instanceof InetSocketAddress) {
            InetSocketAddress socketAddress = (InetSocketAddress) remoteAddress;
            this.clientIp = socketAddress.getAddress() == null ? socketAddress.getHostString() : socketAddress.getAddress().getHostAddress();
            this.clientPort = socketAddress.getPort();
        } else {
            this.clientIp = null;
            this.clientPort = -1;
        }
    }

    public long getConnectionId() {
        return connectionId;
    }

    public long getMappingId() {
        return mappingId;
    }

    public Mapping getMappingSnapshot() {
        return mappingSnapshot;
    }

    public Channel getLocalChannel() {
        return localChannel;
    }

    public String getClientIp() {
        return clientIp;
    }

    public int getClientPort() {
        return clientPort;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void setForwardContext(TCPForWardContext forwardContext) {
        this.forwardContext = forwardContext;
    }

    public boolean close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        this.closeReason = reason;
        this.closedAt = Instant.now();
        TCPForWardContext currentForwardContext = forwardContext;
        if (currentForwardContext != null) {
            currentForwardContext.close();
        }
        if (localChannel != null && localChannel.isOpen()) {
            localChannel.close();
        }
        return true;
    }
}
