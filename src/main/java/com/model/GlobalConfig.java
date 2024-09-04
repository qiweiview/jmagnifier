package com.model;


import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Data
public class GlobalConfig {

    public static GlobalConfig DEFAULT_INSTANT;

    private List<Mapping> mappings;


    /**
     * 验证
     */
    public void verifyConfiguration() {
        if (mappings != null && mappings.size() > 0) {
            mappings = mappings.stream().filter(x -> {
                int listenPort = x.getListenPort();
                int forwardPort = x.getForwardPort();
                if (listenPort < 0 || listenPort > 65536 || forwardPort < 0 || forwardPort > 65536) {
                    log.warn("过滤策略{}-->{}:{}", x.getListenPort(), x.getForwardHost(), x.getForwardPort());
                    return false;
                }
                return true;
            }).collect(Collectors.toList());
        }


    }


}
