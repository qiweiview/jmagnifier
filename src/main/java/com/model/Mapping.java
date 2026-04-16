package com.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class Mapping {
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Boolean enable = true;

    private int listenPort;

    private int forwardPort;

    private String name;

    private String forwardHost;

    private ConsoleConfig console;

    private DumpConfig dump;

    public Boolean getEnable() {
        return enable;
    }

    public void setEnable(Boolean enable) {
        this.enable = enable;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public int getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(int forwardPort) {
        this.forwardPort = forwardPort;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getForwardHost() {
        return forwardHost;
    }

    public void setForwardHost(String forwardHost) {
        this.forwardHost = forwardHost;
    }

    public ConsoleConfig getConsole() {
        return console;
    }

    public void setConsole(ConsoleConfig console) {
        this.console = console;
    }

    public DumpConfig getDump() {
        return dump;
    }

    public void setDump(DumpConfig dump) {
        this.dump = dump;
    }

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

    public void applyDefaults() {
        Mapping defaultMapping = createDefaultMapping();
        if (enable == null) {
            enable = defaultMapping.getEnable();
        }
        if (name == null || name.trim().length() == 0) {
            name = defaultMapping.getName();
        }
        if (console == null) {
            console = defaultMapping.getConsole();
        } else {
            if (console.getPrintRequest() == null) {
                console.setPrintRequest(defaultMapping.getConsole().getPrintRequest());
            }
            if (console.getPrintResponse() == null) {
                console.setPrintResponse(defaultMapping.getConsole().getPrintResponse());
            }
        }
        if (dump == null) {
            dump = defaultMapping.getDump();
        } else {
            if (dump.getEnable() == null) {
                dump.setEnable(defaultMapping.getDump().getEnable());
            }
            if (dump.getDumpPath() == null || dump.getDumpPath().trim().length() == 0) {
                dump.setDumpPath(defaultMapping.getDump().getDumpPath());
            }
        }
    }

    public String format() {
        return String.format("%s_listen:%d_forward:%s:%d", name, listenPort, forwardHost, forwardPort);
    }

    public String dumpName() {
        LocalDate now = LocalDate.now();
        return getName() + "_" + now.format(DATE_TIME_FORMATTER) + ".log";
    }

}
