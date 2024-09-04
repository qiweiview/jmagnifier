package com;

import ch.qos.logback.classic.Level;
import com.core.DataReceiver;
import com.model.GlobalConfig;
import com.model.Mapping;
import com.util.ApplicationExit;
import com.util.YmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;


public class AppStart {
    private static final Logger logger = LoggerFactory.getLogger(AppStart.class);
    public static final YmlParser ymlParser = new YmlParser();

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Good night everyone ");
        }));


        System.out.println("" +
                "       _   __  __                   _  __ _           \n" +
                "      | | |  \\/  |                 (_)/ _(_)          \n" +
                "      | | | \\  / | __ _  __ _ _ __  _| |_ _  ___ _ __ \n" +
                "  _   | | | |\\/| |/ _` |/ _` | '_ \\| |  _| |/ _ \\ '__|\n" +
                " | |__| | | |  | | (_| | (_| | | | | | | | |  __/ |   \n" +
                "  \\____/  |_|  |_|\\__,_|\\__, |_| |_|_|_| |_|\\___|_|   \n" +
                "                         __/ |                        \n" +
                "                        |___/                         " +
                "\n\r");

        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel("INFO"));

        if (args.length < 1) {
            logger.error("缺失启动参数");
            ApplicationExit.exit();
        }

        String configFile = args[0];


        File file = new File(configFile);
        if (!file.exists()) {
            logger.error("无法找到配置文件:" + file);
            ApplicationExit.exit();
        }


        try {
            GlobalConfig globalConfig = ymlParser.parseFile(file, GlobalConfig.class);
            //配置验证
            globalConfig.verifyConfiguration();
            GlobalConfig.DEFAULT_INSTANT = globalConfig;

            printMapping(globalConfig.getMappings());

        } catch (Exception e) {
            logger.error("parse config file:" + file + " fail ", e);
            ApplicationExit.exit();
        }


        //启动映射服务
        startMappingServer(GlobalConfig.DEFAULT_INSTANT.getMappings());

    }

    /**
     * 启动映射服务
     *
     * @param mappingList
     */
    private static void startMappingServer(List<Mapping> mappingList) {
        //循环启动
        mappingList.forEach(x -> {
            DataReceiver dataReceiver = new DataReceiver(x);
            dataReceiver.start();
        });
    }

    /**
     * 打印映射关系
     *
     * @param mappingList
     */
    private static void printMapping(List<Mapping> mappingList) {
        mappingList.forEach(x -> {
            System.out.println("\n\r" + x.getName() + " rule: listen local port " + x.getListenPort() + " ------- to -----> port " + x.getForwardPort());

        });

    }
}
