package com.capture;

import com.model.CaptureConfig;

public class CaptureOptions {

    private final boolean enabled;

    private final int maxCaptureBytes;

    private final int queueCapacity;

    private final int batchSize;

    private final long flushIntervalMillis;

    public CaptureOptions(CaptureConfig captureConfig) {
        CaptureConfig config = captureConfig == null ? new CaptureConfig() : captureConfig;
        this.enabled = Boolean.TRUE.equals(config.getEnabled());
        this.maxCaptureBytes = Math.max(0, config.getMaxCaptureBytes());
        this.queueCapacity = Math.max(1, config.getQueueCapacity());
        this.batchSize = Math.max(1, config.getBatchSize());
        this.flushIntervalMillis = Math.max(1, config.getFlushIntervalMillis());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxCaptureBytes() {
        return maxCaptureBytes;
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
