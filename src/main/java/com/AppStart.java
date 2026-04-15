package com;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.model.GlobalConfig;
import com.model.Mapping;
import com.runtime.AppRuntime;
import com.util.ApplicationExit;
import com.util.YmlParser;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;

import static org.slf4j.Logger.ROOT_LOGGER_NAME;


@Slf4j
public class AppStart {
    public static final YmlParser ymlParser = new YmlParser();

    private static volatile AppRuntime appRuntime;

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (appRuntime != null) {
                appRuntime.shutdown();
            }
            log.info("Good night everyone ");
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

        Logger root = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel("INFO"));

        GlobalConfig globalConfig;
        if (args.length < 1) {
            globalConfig = new GlobalConfig();
        } else if (args.length == 1) {
            String configFile = args[0];
            //todo 配置文件模式
            if (configFile.contains(".yml")) {
                globalConfig = configFileMode(configFile);
            } else {
                throw new RuntimeException("不支持的配置文件格式");
            }
        } else if (args.length >= 3) {
            //todo 命令行模式
            globalConfig = paramMode(args);
        } else {
            throw new RuntimeException("参数错误");
        }

        GlobalConfig.DEFAULT_INSTANT = globalConfig;

        //启动映射服务
        startMappingServer(GlobalConfig.DEFAULT_INSTANT);

    }

    private static GlobalConfig paramMode(String[] args) {
        try {
            String listenPortSting = args[0];
            String forwardHost = args[1];
            String forwardPortString = args[2];
            Integer listenPort = Integer.valueOf(listenPortSting);
            Integer forwardPort = Integer.valueOf(forwardPortString);

            GlobalConfig globalConfig = new GlobalConfig();
            Mapping mapping = Mapping.createDefaultMapping();
            mapping.setListenPort(listenPort);
            mapping.setForwardHost(forwardHost);
            mapping.setForwardPort(forwardPort);
            globalConfig.setMappings(Arrays.asList(mapping));
            return globalConfig;
        } catch (NumberFormatException e) {
            throw new RuntimeException("参数错误");
        }

    }

    private static GlobalConfig configFileMode(String configFile) {
        File file = new File(configFile);
        if (!file.exists()) {
            log.error("无法找到配置文件:" + file);
            ApplicationExit.exit();
        }


        try {
            return ymlParser.parseFile(file, GlobalConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("解析配置文件失败:" + configFile + ":" + e.getMessage());
        }
    }

    /**
     * 启动映射服务
     *
     * @param globalConfig
     */
    private static void startMappingServer(GlobalConfig globalConfig) {
        appRuntime = new AppRuntime(globalConfig);
        appRuntime.start();
    }
}
