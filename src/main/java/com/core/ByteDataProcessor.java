package com.core;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.HexDumpEncoder;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ByteDataProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ByteDataProcessor.class);
    private static final HexDumpEncoder hexDumpEncoder = new HexDumpEncoder();
    private static ExecutorService executorService = Executors.newFixedThreadPool(2);



    public static void dump2File(byte[] bytes, int remote, int local) {

        Runnable runnable=() -> {
            StringBuilder stringBuilder = new StringBuilder();

            String key ="";
            int listenPort = GlobalConfig.DEFAULT_INSTANT.getListenPort();
            if (remote==listenPort||local==listenPort){
                key="request";
            }

            int forwardPort = GlobalConfig.DEFAULT_INSTANT.getForwardPort();
            if (remote==forwardPort||local==forwardPort){
                key="response";
            }

            if (!GlobalConfig.DEFAULT_INSTANT.isIgnoreHex()){
                stringBuilder.append("========================= hex dump " + key + " ====================\n\r");
                stringBuilder.append(hexDumpEncoder.encode(bytes)+"\n\r");

            }

            if (!GlobalConfig.DEFAULT_INSTANT.isIgnoreString()){
                stringBuilder.append("========================= str dump " + key + " ====================\n\r");
                stringBuilder.append(new String(bytes)+"\n\r");
            }


            byte[] dump = stringBuilder.toString().getBytes();

            try {
                FileUtils.writeByteArrayToFile(GlobalConfig.DEFAULT_INSTANT.getDumpFile(), dump, true);
            } catch (IOException e) {
                logger.error("dump error cause " + e);
            }
        };

        executorService.execute(runnable);
    }

    public static void dump2Console(String key, byte[] bytes) {
        Runnable runnable = () -> {
            HexDumpEncoder hexDumpEncoder = new HexDumpEncoder();
            String encode = hexDumpEncoder.encode(bytes);
            System.out.println("\n\r========================= " + key + " ====================\n\r" + encode);
        };
        executorService.execute(runnable);
    }
}
