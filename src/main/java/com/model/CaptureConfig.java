package com.model;

public class CaptureConfig {

    private Boolean enabled = true;

    private int maxCaptureBytes = 5*1024*1024;//5MB

    private int queueCapacity = 10000;

    private int batchSize = 100;

    private long flushIntervalMillis = 200;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxCaptureBytes() {
        return maxCaptureBytes;
    }

    public void setMaxCaptureBytes(int maxCaptureBytes) {
        this.maxCaptureBytes = maxCaptureBytes;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getFlushIntervalMillis() {
        return flushIntervalMillis;
    }

    public void setFlushIntervalMillis(long flushIntervalMillis) {
        this.flushIntervalMillis = flushIntervalMillis;
    }
}
