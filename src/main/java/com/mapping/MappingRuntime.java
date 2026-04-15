package com.mapping;

import com.core.DataReceiver;
import com.model.Mapping;

import java.time.Instant;

public class MappingRuntime {

    private final long mappingId;

    private Mapping mappingSnapshot;

    private DataReceiver dataReceiver;

    private volatile MappingStatus status = MappingStatus.STOPPED;

    private volatile String lastError;

    private volatile Instant startedAt;

    private volatile Instant stoppedAt;

    public MappingRuntime(long mappingId, Mapping mappingSnapshot) {
        this.mappingId = mappingId;
        this.mappingSnapshot = mappingSnapshot;
    }

    public long getMappingId() {
        return mappingId;
    }

    public Mapping getMappingSnapshot() {
        return mappingSnapshot;
    }

    public void setMappingSnapshot(Mapping mappingSnapshot) {
        this.mappingSnapshot = mappingSnapshot;
    }

    public DataReceiver getDataReceiver() {
        return dataReceiver;
    }

    public void setDataReceiver(DataReceiver dataReceiver) {
        this.dataReceiver = dataReceiver;
    }

    public MappingStatus getStatus() {
        return status;
    }

    public void setStatus(MappingStatus status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    public void setStoppedAt(Instant stoppedAt) {
        this.stoppedAt = stoppedAt;
    }

    public int getActiveConnections() {
        return dataReceiver == null ? 0 : dataReceiver.getActiveConnectionCount();
    }
}
