package com.core;


import lombok.Data;

import java.io.File;

@Data
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

    private String forwardHost;




    public void verifyConfiguration() {
        if (listenPort < 0 || listenPort > 65536 || forwardPort < 0 || forwardPort > 65536) {
            throw new RuntimeException("unSupport port");
        }


        //磁盘打印才验证地址
        if (logDump && new File(dumpPath).exists()) {
            dumpFile = new File(dumpPath + File.separator + "dump");
        } else {
            throw new RuntimeException("direct is not exist");
        }
    }


}
