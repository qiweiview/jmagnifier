package com.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Data
public class Mapping {
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Boolean enable = true;

    private int listenPort;

    private int forwardPort;

    private String name;

    private String forwardHost;

    private ConsoleConfig console;

    private DumpConfig dump;


    public static Mapping createDefaultMapping() {
        Mapping mapping = new Mapping();
        mapping.setName("mapping-" + UUID.randomUUID());
        ConsoleConfig defaultConsoleConfig = new ConsoleConfig();
        defaultConsoleConfig.setPrintRequest(true);
        defaultConsoleConfig.setPrintResponse(true);
        mapping.setConsole(defaultConsoleConfig);
        DumpConfig defaultDumpConfig = new DumpConfig();
        defaultDumpConfig.setEnable(false);
        defaultDumpConfig.setDumpPath("/tmp/j_magnifier");
        mapping.setDump(defaultDumpConfig);
        return mapping;
    }

    public String format() {
        return String.format("%s_listen:%d_forward:%s:%d", name, listenPort, forwardHost, forwardPort);
    }

    public String dumpName() {
        LocalDate now = LocalDate.now();
        return getName() + "_" + now.format(DATE_TIME_FORMATTER) + ".log";
    }

}
