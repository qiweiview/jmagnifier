package com;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.core.DataReceiver;
import com.model.GlobalConfig;
import com.model.Mapping;
import com.util.ApplicationExit;
import com.util.YmlParser;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.function.Function;

import static org.slf4j.Logger.ROOT_LOGGER_NAME;


@Slf4j
public class AppStart {
    public static final YmlParser ymlParser = new YmlParser();

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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
            //todo 等待输入模式
            //等待客户端输入
            Scanner scanner = new Scanner(System.in);


            String listenPortSting = circleWait(scanner, "输入监听端口，回车结束", "端口必须是纯数字，例如8001", (x) -> {
                try {
                    Integer.parseInt(x);
                    return x;
                } catch (NumberFormatException e) {
                    return null;
                }
            });

            String forwardHost = circleWait(scanner, "输入输入远程域名，回车结束", "域名长度必须大于0，例如jmagnifier.com", (x) -> {
                if (x.length() > 0) {
                    return x;
                } else {
                    return null;
                }
            });

            String forwardPortString = circleWait(scanner, "输入远程端口，回车结束", "端口必须是纯数字,例如80", (x) -> {
                try {
                    Integer.parseInt(x);
                    return x;
                } catch (NumberFormatException e) {
                    return null;
                }
            });

            Integer listenPort = Integer.valueOf(listenPortSting);
            Integer forwardPort = Integer.valueOf(forwardPortString);

            globalConfig = new GlobalConfig();
            Mapping mapping = new Mapping();
            mapping.setName("自定义映射");
            mapping.setListenPort(listenPort);
            mapping.setForwardHost(forwardHost);
            mapping.setForwardPort(forwardPort);
            mapping.setPrintRequest(true);
            mapping.setPrintResponse(true);
            globalConfig.setMappings(Arrays.asList(mapping));
        } else if (args.length == 1) {
            String configFile = args[0];
            //todo 配置文件模式
            if (configFile.contains(".yml")) {
                globalConfig = configFileMode(configFile);
            } else {
                throw new RuntimeException("不支持的配置文件格式");
            }
        } else if (args.length > 3) {
            //todo 命令行模式
            globalConfig = paramMode(args);
        } else {
            throw new RuntimeException("参数错误");
        }

        //配置验证
        globalConfig.verifyConfiguration();
        GlobalConfig.DEFAULT_INSTANT = globalConfig;
        printMapping(globalConfig.getMappings());

        //启动映射服务
        startMappingServer(GlobalConfig.DEFAULT_INSTANT.getMappings());

    }

    private static String circleWait(Scanner scanner, String tip, String formatTip, Function<String, String> o) {

        String matchValue;
        while (true) {
            log.info(tip);
            matchValue = o.apply(scanner.nextLine());
            if (matchValue == null) {
                log.warn(formatTip);
            } else {
                return matchValue;
            }

        }
    }

    private static GlobalConfig paramMode(String[] args) {
        try {
            String listenPortSting = args[0];
            String forwardHost = args[1];
            String forwardPortString = args[2];
            Integer listenPort = Integer.valueOf(listenPortSting);
            Integer forwardPort = Integer.valueOf(forwardPortString);

            GlobalConfig globalConfig = new GlobalConfig();
            Mapping mapping = new Mapping();
            mapping.setName("自定义映射");
            mapping.setListenPort(listenPort);
            mapping.setForwardHost(forwardHost);
            mapping.setForwardPort(forwardPort);
            mapping.setPrintRequest(true);
            mapping.setPrintResponse(true);
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
            throw new RuntimeException("解析配置文件失败:" + configFile);
        }
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
        System.out.println("启动映射成功↓↓↓");
        mappingList.forEach(x -> {
            System.out.println("[" + x.getName() + "]规则: 监听本地端口：" + x.getListenPort() + "------------>" + x.getForwardHost() + ":" + x.getForwardPort());
        });

    }
}
