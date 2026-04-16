package com.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class Mapping {
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @JsonAlias("enabled")
    private Boolean enable = true;

    private int listenPort;

    private int forwardPort;

    private String name;

    private String forwardHost;

    private ConsoleConfig console;

    private DumpConfig dump;

    private EndpointConfig listen;

    private EndpointConfig forward;

    private HttpProxyConfig http;

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

    public EndpointConfig getListen() {
        return listen;
    }

    public void setListen(EndpointConfig listen) {
        this.listen = listen;
        if (listen != null && listen.getPort() != null) {
            this.listenPort = listen.getPort();
        }
    }

    public EndpointConfig getForward() {
        return forward;
    }

    public void setForward(EndpointConfig forward) {
        this.forward = forward;
        if (forward != null) {
            if (forward.getHost() != null) {
                this.forwardHost = forward.getHost();
            }
            if (forward.getPort() != null) {
                this.forwardPort = forward.getPort();
            }
        }
    }

    public HttpProxyConfig getHttp() {
        return http;
    }

    public void setHttp(HttpProxyConfig http) {
        this.http = http;
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
        mapping.setListen(new EndpointConfig());
        mapping.setForward(new EndpointConfig());
        mapping.setHttp(new HttpProxyConfig());
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
        if (listen == null) {
            listen = defaultMapping.getListen();
        }
        if (forward == null) {
            forward = defaultMapping.getForward();
        }
        if (http == null) {
            http = defaultMapping.getHttp();
        }
        listen.applyDefaults();
        forward.applyDefaults();
        http.applyDefaults();

        if (listen.getPort() == null) {
            listen.setPort(listenPort);
        }
        if (forward.getPort() == null) {
            forward.setPort(forwardPort);
        }
        if (isBlank(forward.getHost())) {
            forward.setHost(forwardHost);
        }

        listenPort = listen.getPort() == null ? 0 : listen.getPort();
        forwardPort = forward.getPort() == null ? 0 : forward.getPort();
        forwardHost = forward.getHost();
    }

    public String format() {
        return String.format("%s_listen:%d_forward:%s:%d", name, listenPort, forwardHost, forwardPort);
    }

    public String dumpName() {
        LocalDate now = LocalDate.now();
        return getName() + "_" + now.format(DATE_TIME_FORMATTER) + ".log";
    }

    public boolean isRawTcpPath() {
        return isProtocol(listen, "tcp")
                && isProtocol(forward, "tcp")
                && !isTlsEnabled(listen)
                && !isTlsEnabled(forward);
    }

    public boolean isHttpPath() {
        return isProtocol(listen, "http") || isProtocol(forward, "http");
    }

    public String getListenMode() {
        return modeOf(listen);
    }

    public String getForwardMode() {
        return modeOf(forward);
    }

    private String modeOf(EndpointConfig endpointConfig) {
        if (endpointConfig == null) {
            return "tcp";
        }
        if (isProtocol(endpointConfig, "http")) {
            return isTlsEnabled(endpointConfig) ? "https" : "http";
        }
        return isTlsEnabled(endpointConfig) ? "tls" : "tcp";
    }

    private boolean isProtocol(EndpointConfig endpointConfig, String protocol) {
        return endpointConfig != null
                && endpointConfig.getApplicationProtocol() != null
                && protocol.equalsIgnoreCase(endpointConfig.getApplicationProtocol());
    }

    private boolean isTlsEnabled(EndpointConfig endpointConfig) {
        return endpointConfig != null
                && endpointConfig.getTls() != null
                && Boolean.TRUE.equals(endpointConfig.getTls().getEnabled());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

}
