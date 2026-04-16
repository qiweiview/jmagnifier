package com.model;


import com.util.ApplicationExit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Data
public class GlobalConfig {

    public static GlobalConfig DEFAULT_INSTANT;

    private StoreConfig store = new StoreConfig();

    private AdminConfig admin = new AdminConfig();

    private CaptureConfig capture = new CaptureConfig();

    private List<Mapping> mappings;


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
