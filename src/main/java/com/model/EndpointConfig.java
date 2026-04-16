package com.model;

public class EndpointConfig {

    private Integer port;

    private String host;

    private String applicationProtocol = "tcp";

    private TlsConfig tls = new TlsConfig();

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getApplicationProtocol() {
        return applicationProtocol;
    }

    public void setApplicationProtocol(String applicationProtocol) {
        this.applicationProtocol = applicationProtocol;
    }

    public TlsConfig getTls() {
        return tls;
    }

    public void setTls(TlsConfig tls) {
        this.tls = tls;
    }

    public void applyDefaults() {
        if (applicationProtocol == null || applicationProtocol.trim().length() == 0) {
            applicationProtocol = "tcp";
        } else {
            applicationProtocol = applicationProtocol.trim().toLowerCase();
        }
        if (tls == null) {
            tls = new TlsConfig();
        }
        tls.applyDefaults();
    }
}
