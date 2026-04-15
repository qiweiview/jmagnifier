package com.runtime;

import com.mapping.RuntimeMappingManager;
import com.model.GlobalConfig;

public class AppRuntime {

    private final GlobalConfig globalConfig;

    private final NettyGroups nettyGroups;

    private final RuntimeMappingManager runtimeMappingManager;

    public AppRuntime(GlobalConfig globalConfig) {
        this.globalConfig = globalConfig;
        this.nettyGroups = new NettyGroups();
        this.runtimeMappingManager = new RuntimeMappingManager(nettyGroups);
    }

    public void start() {
        runtimeMappingManager.startAll(globalConfig.getMappings());
    }

    public void shutdown() {
        runtimeMappingManager.shutdown();
        nettyGroups.shutdown();
    }

    public RuntimeMappingManager getRuntimeMappingManager() {
        return runtimeMappingManager;
    }
}
