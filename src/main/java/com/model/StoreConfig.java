package com.model;

public class StoreConfig {

    private String sqlitePath = "./data/jmagnifier.db";

    private String spillDir = "./data/spill";

    private String payloadDir = "./data/payload";

    private long payloadSegmentBytes = 128L * 1024L * 1024L;

    public String getSqlitePath() {
        return sqlitePath;
    }

    public void setSqlitePath(String sqlitePath) {
        this.sqlitePath = sqlitePath;
    }

    public String getSpillDir() {
        return spillDir;
    }

    public void setSpillDir(String spillDir) {
        this.spillDir = spillDir;
    }

    public String getPayloadDir() {
        return payloadDir;
    }

    public void setPayloadDir(String payloadDir) {
        this.payloadDir = payloadDir;
    }

    public long getPayloadSegmentBytes() {
        return payloadSegmentBytes;
    }

    public void setPayloadSegmentBytes(long payloadSegmentBytes) {
        this.payloadSegmentBytes = payloadSegmentBytes;
    }
}
