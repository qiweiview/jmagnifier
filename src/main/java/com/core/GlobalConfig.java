package com.core;


import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

@Slf4j
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


    /**
     * 验证
     */
    public void verifyConfiguration() {
        if (listenPort < 0 || listenPort > 65536 || forwardPort < 0 || forwardPort > 65536) {
            throw new RuntimeException("unSupport port");
        }

        if (logDump) {
            //todo 磁盘输出
            if (!new File(dumpPath).exists()) {
                throw new RuntimeException("目标输出路径: " + dumpPath + " 不存在");
            }
            dumpFile = new File(dumpPath + File.separator + "dump");

        } else {
            //todo 磁盘不输出
            log.info("配置不写入日志到磁盘中");
        }
    }


}
