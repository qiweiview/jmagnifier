package com.model;

public class StoreConfig {

    private String sqlitePath = "./data/jmagnifier.db";

    private String spillDir = "./data/spill";

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
}
