package com.core;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ByteDataProcessor {


    private static ExecutorService executorService = Executors.newFixedThreadPool(2);


    public static void checkUnifiedOutput(Runnable consolePrint, Runnable logDum) {
        if (GlobalConfig.DEFAULT_INSTANT.isConsoleDump()) {
            //todo 写控制台
            consolePrint.run();
        }

        if (GlobalConfig.DEFAULT_INSTANT.isLogDump()) {
            //写日志
            logDum.run();
        }
    }


    public static void dump2File(byte[] bytes) {
        try {
            FileUtils.writeByteArrayToFile(GlobalConfig.DEFAULT_INSTANT.getDumpFile(), bytes, true);
        } catch (IOException e) {
            log.error("dump error cause " + e);
        }
    }


    public static void dump2Console(String key, String bytes) {
        Runnable runnable = () -> {
            System.out.println("\n\r========================= " + key + " ====================\n\r" + bytes);
        };
        executorService.execute(runnable);
    }
}
