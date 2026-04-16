package com.model;

public class DumpConfig {

    private Boolean enable = false;

    private String dumpPath = "/tmp/j_magnifier";

    public Boolean getEnable() {
        return enable;
    }

    public void setEnable(Boolean enable) {
        this.enable = enable;
    }

    public String getDumpPath() {
        return dumpPath;
    }

    public void setDumpPath(String dumpPath) {
        this.dumpPath = dumpPath;
    }
}
