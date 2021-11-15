package com;

import ch.qos.logback.classic.Level;
import com.core.DataReceiver;
import com.core.GlobalConfig;
import com.util.ApplicationExit;
import com.util.YmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;


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
            logger.error("missing startup parameters");
            ApplicationExit.exit();
        }

        String configFile = args[0];


        File file = new File(configFile);
        if (!file.exists()) {
            logger.error("can not found:" + file);
            ApplicationExit.exit();
        }


        try {
            GlobalConfig globalConfig = ymlParser.parseFile(file, GlobalConfig.class);
            globalConfig.verifyConfiguration();
            GlobalConfig.DEFAULT_INSTANT = globalConfig;

            System.out.println("\n\rForward data from port " + GlobalConfig.DEFAULT_INSTANT.getListenPort() + " ------- to -----> port " + GlobalConfig.DEFAULT_INSTANT.getForwardPort());

            if (GlobalConfig.DEFAULT_INSTANT.isLogDump()) {
                System.out.println("\n\rDump file output to " + GlobalConfig.DEFAULT_INSTANT.getDumpFile().getAbsolutePath());
            }

            if (GlobalConfig.DEFAULT_INSTANT.isIgnoreString()) {
                System.out.println("\n\rDump will not print string");
            }

            if (GlobalConfig.DEFAULT_INSTANT.isIgnoreHex()) {
                System.out.println("\n\rDump will not print hex");
            }

        } catch (Exception e) {
            e.printStackTrace();
            logger.error("parse config file:" + file + "fail " + e);
            ApplicationExit.exit();
        }


        new DataReceiver(GlobalConfig.DEFAULT_INSTANT.getListenPort(),GlobalConfig.DEFAULT_INSTANT.getForwardHost(), GlobalConfig.DEFAULT_INSTANT.getForwardPort()).start();
    }
}
