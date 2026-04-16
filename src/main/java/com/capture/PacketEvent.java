package com.capture;

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

    private byte[] payloadPreview;

    private int payloadSize;

    private int capturedSize;

    private boolean truncated;

    private long sequenceNo;

    private String receivedAt;

    private String protocolFamily;

    private String applicationProtocol;

    private String contentType;

    private String httpMethod;

    private String httpUri;

    private Integer httpStatus;

    private String payloadStoreType;

    private String payloadFilePath;

    private Long payloadFileOffset;

    private Integer payloadFileLength;

    private int payloadPreviewSize;

    private boolean payloadComplete;

    private String payloadSha256;

    public long getMappingId() {
        return mappingId;
    }

    public void setMappingId(long mappingId) {
        this.mappingId = mappingId;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(long connectionId) {
        this.connectionId = connectionId;
    }

    public PacketDirection getDirection() {
        return direction;
    }

    public void setDirection(PacketDirection direction) {
        this.direction = direction;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public int getClientPort() {
        return clientPort;
    }

    public void setClientPort(int clientPort) {
        this.clientPort = clientPort;
    }

    public String getListenIp() {
        return listenIp;
    }

    public void setListenIp(String listenIp) {
        this.listenIp = listenIp;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    public String getRemoteIp() {
        return remoteIp;
    }

    public void setRemoteIp(String remoteIp) {
        this.remoteIp = remoteIp;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public byte[] getPayloadPreview() {
        return payloadPreview;
    }

    public void setPayloadPreview(byte[] payloadPreview) {
        this.payloadPreview = payloadPreview;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public void setPayloadSize(int payloadSize) {
        this.payloadSize = payloadSize;
    }

    public int getCapturedSize() {
        return capturedSize;
    }

    public void setCapturedSize(int capturedSize) {
        this.capturedSize = capturedSize;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(String receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getProtocolFamily() {
        return protocolFamily;
    }

    public void setProtocolFamily(String protocolFamily) {
        this.protocolFamily = protocolFamily;
    }

    public String getApplicationProtocol() {
        return applicationProtocol;
    }

    public void setApplicationProtocol(String applicationProtocol) {
        this.applicationProtocol = applicationProtocol;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getHttpUri() {
        return httpUri;
    }

    public void setHttpUri(String httpUri) {
        this.httpUri = httpUri;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getPayloadStoreType() {
        return payloadStoreType;
    }

    public void setPayloadStoreType(String payloadStoreType) {
        this.payloadStoreType = payloadStoreType;
    }

    public String getPayloadFilePath() {
        return payloadFilePath;
    }

    public void setPayloadFilePath(String payloadFilePath) {
        this.payloadFilePath = payloadFilePath;
    }

    public Long getPayloadFileOffset() {
        return payloadFileOffset;
    }

    public void setPayloadFileOffset(Long payloadFileOffset) {
        this.payloadFileOffset = payloadFileOffset;
    }

    public Integer getPayloadFileLength() {
        return payloadFileLength;
    }

    public void setPayloadFileLength(Integer payloadFileLength) {
        this.payloadFileLength = payloadFileLength;
    }

    public int getPayloadPreviewSize() {
        return payloadPreviewSize;
    }

    public void setPayloadPreviewSize(int payloadPreviewSize) {
        this.payloadPreviewSize = payloadPreviewSize;
    }

    public boolean isPayloadComplete() {
        return payloadComplete;
    }

    public void setPayloadComplete(boolean payloadComplete) {
        this.payloadComplete = payloadComplete;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public void setPayloadSha256(String payloadSha256) {
        this.payloadSha256 = payloadSha256;
    }
}
