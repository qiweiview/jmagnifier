package com.model;


import com.util.ApplicationExit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class GlobalConfig {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfig.class);

    public static GlobalConfig DEFAULT_INSTANT;

    private StoreConfig store = new StoreConfig();

    private AdminConfig admin = new AdminConfig();

    private CaptureConfig capture = new CaptureConfig();

    private List<Mapping> mappings;

    public StoreConfig getStore() {
        return store;
    }

    public void setStore(StoreConfig store) {
        this.store = store;
    }

    public AdminConfig getAdmin() {
        return admin;
    }

    public void setAdmin(AdminConfig admin) {
        this.admin = admin;
    }

    public CaptureConfig getCapture() {
        return capture;
    }

    public void setCapture(CaptureConfig capture) {
        this.capture = capture;
    }

    public List<Mapping> getMappings() {
        return mappings;
    }

    public void setMappings(List<Mapping> mappings) {
        this.mappings = mappings;
    }

    /**
     * 验证
     */
    public void verifyConfiguration() {
        if (mappings == null || mappings.size() == 0) {
            log.error("配置文件中没有配置任何映射策略");
            ApplicationExit.exit();
        }

        mappings = mappings.stream().filter(x -> {
            x.applyDefaults();
            if (!Boolean.TRUE.equals(x.getEnable())) {
                log.info("映射策略未启用，启动时不会监听:{}", x.format());
            }
            int listenPort = x.getListenPort();
            int forwardPort = x.getForwardPort();
            if (listenPort < 0 || listenPort > 65535 || forwardPort < 0 || forwardPort > 65535) {
                log.warn("过滤策略{}-->{}:{}", x.getListenPort(), x.getForwardHost(), x.getForwardPort());
                return false;
            }
            return true;
        }).collect(Collectors.toList());

        if (mappings.size() == 0) {
            log.error("没有可启动的映射策略");
            ApplicationExit.exit();
        }
        if (store == null) {
            store = new StoreConfig();
        }
        if (admin == null) {
            admin = new AdminConfig();
        }
        if (capture == null) {
            capture = new CaptureConfig();
        }
    }


}
