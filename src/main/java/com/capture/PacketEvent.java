package com.capture;

import lombok.Data;

@Data
public class PacketEvent {

    private long mappingId;

    private long connectionId;

    private PacketDirection direction;

    private String clientIp;

    private int clientPort;

    private String listenIp;

    private int listenPort;

    private String targetHost;

    private int targetPort;

    private String remoteIp;

    private int remotePort;

    private byte[] payload;

    private int payloadSize;

    private int capturedSize;

    private boolean truncated;

    private long sequenceNo;

    private String receivedAt;
}
