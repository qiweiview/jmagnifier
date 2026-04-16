package com.capture;

import com.model.CaptureConfig;

public class CaptureOptions {

    private final boolean enabled;

    private final int previewBytes;

    private final PayloadStoreType payloadStoreType;

    private final int maxPayloadBytes;

    private final int queueCapacity;

    private final int batchSize;

    private final long flushIntervalMillis;

    public CaptureOptions(CaptureConfig captureConfig) {
        CaptureConfig config = captureConfig == null ? new CaptureConfig() : captureConfig;
        this.enabled = Boolean.TRUE.equals(config.getEnabled());
        Integer configuredPreviewBytes = config.getPreviewBytes();
        this.previewBytes = Math.max(0, configuredPreviewBytes == null ? config.getMaxCaptureBytes() : configuredPreviewBytes);
        this.payloadStoreType = PayloadStoreType.fromConfig(config.getPayloadStoreType());
        this.maxPayloadBytes = Math.max(0, config.getMaxPayloadBytes());
        this.queueCapacity = Math.max(1, config.getQueueCapacity());
        this.batchSize = Math.max(1, config.getBatchSize());
        this.flushIntervalMillis = Math.max(1, config.getFlushIntervalMillis());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPreviewBytes() {
        return previewBytes;
    }

    public PayloadStoreType getPayloadStoreType() {
        return payloadStoreType;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public long getFlushIntervalMillis() {
        return flushIntervalMillis;
    }
}
