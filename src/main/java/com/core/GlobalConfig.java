package com.core;


import lombok.Data;

import java.io.File;
import java.util.List;

@Data
public class GlobalConfig {


    public static GlobalConfig DEFAULT_INSTANT;

    private boolean consoleDump = false;

    private boolean logDump = false;

    private boolean dumpHex = false;

    private boolean dumpString = false;

    private String type;

    private String dumpPath;

    private File dumpFile;

    private int listenPort;

    private int forwardPort;

    private String forwardHost;

    /*镜像返回，将入参打印*/
    private boolean mirrorResponse;

    private List<String> matchLocation;


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


}
