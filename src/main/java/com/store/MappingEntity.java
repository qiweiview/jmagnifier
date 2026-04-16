package com.store;

import com.model.Mapping;

public class MappingEntity {

    private static final MappingConfigJsonCodec CONFIG_JSON_CODEC = new MappingConfigJsonCodec();

    private long id;

    private String name;

    private boolean enabled;

    private int listenPort;

    private String forwardHost;

    private int forwardPort;

    private String createdAt;

    private String updatedAt;

    private String configJson;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public String getForwardHost() {
        return forwardHost;
    }

    public void setForwardHost(String forwardHost) {
        this.forwardHost = forwardHost;
    }

    public int getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(int forwardPort) {
        this.forwardPort = forwardPort;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public Mapping toMapping() {
        if (configJson != null && configJson.trim().length() > 0) {
            return CONFIG_JSON_CODEC.fromJson(configJson);
        }
        Mapping mapping = Mapping.createDefaultMapping();
        mapping.setName(name);
        mapping.setEnable(enabled);
        mapping.setListenPort(listenPort);
        mapping.setForwardHost(forwardHost);
        mapping.setForwardPort(forwardPort);
        mapping.applyDefaults();
        return mapping;
    }
}
