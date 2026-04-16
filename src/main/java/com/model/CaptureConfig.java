package com.model;

import lombok.Data;

@Data
public class CaptureConfig {

    private Boolean enabled = true;

    private int maxCaptureBytes = 4096;

    private int queueCapacity = 10000;

    private int batchSize = 100;

    private long flushIntervalMillis = 200;
}
