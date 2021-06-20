package com.core;


import java.io.File;

public class GlobalConfig {

    public static GlobalConfig DEFAULT_INSTANT;

    private boolean consolePrint = false;

    private boolean logDump = false;

    private boolean ignoreHex = false;

    private boolean ignoreString = false;

    private String dumpPath;

    private File dumpFile;

    private int listenPort;

    private int forwardPort;


    public void verifyConfiguration() {
        if (listenPort < 0 || listenPort > 65536 || forwardPort < 0 || forwardPort > 65536) {
            throw new RuntimeException("unSupport port");
        }


        if (new File(dumpPath).exists()) {
            dumpFile = new File(dumpPath + File.separator + "dump");
        } else {
            throw new RuntimeException("direct is not exist");
        }
    }

    public boolean isConsolePrint() {
        return consolePrint;
    }

    public void setConsolePrint(boolean consolePrint) {
        this.consolePrint = consolePrint;
    }

    public boolean isLogDump() {
        return logDump;
    }

    public void setLogDump(boolean logDump) {
        this.logDump = logDump;
    }

    public String getDumpPath() {
        return dumpPath;
    }

    public void setDumpPath(String dumpPath) {
        this.dumpPath = dumpPath;
    }


    public static GlobalConfig getDefaultInstant() {
        return DEFAULT_INSTANT;
    }

    public static void setDefaultInstant(GlobalConfig defaultInstant) {
        DEFAULT_INSTANT = defaultInstant;
    }

    public File getDumpFile() {
        return dumpFile;
    }

    public void setDumpFile(File dumpFile) {
        this.dumpFile = dumpFile;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public int getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(int forwardPort) {
        this.forwardPort = forwardPort;
    }

    public boolean isIgnoreHex() {
        return ignoreHex;
    }

    public void setIgnoreHex(boolean ignoreHex) {
        this.ignoreHex = ignoreHex;
    }

    public boolean isIgnoreString() {
        return ignoreString;
    }

    public void setIgnoreString(boolean ignoreString) {
        this.ignoreString = ignoreString;
    }
}
