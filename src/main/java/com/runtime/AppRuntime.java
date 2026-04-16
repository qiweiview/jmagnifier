package com.runtime;

import com.admin.NettyAdminServer;
import com.capture.CaptureOptions;
import com.capture.PacketCaptureService;
import com.capture.SpillFileManager;
import com.mapping.RuntimeMappingManager;
import com.model.GlobalConfig;
import com.store.ConnectionRepository;
import com.store.DatabaseInitializer;
import com.store.MappingEntity;
import com.store.MappingRepository;
import com.store.PacketRepository;
import com.store.SqliteDatabase;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class AppRuntime {

    private final GlobalConfig globalConfig;

    private final NettyGroups nettyGroups;

    private final RuntimeMappingManager runtimeMappingManager;

    private final SqliteDatabase sqliteDatabase;

    private final MappingRepository mappingRepository;

    private final PacketCaptureService packetCaptureService;

    private final NettyAdminServer nettyAdminServer;

    public AppRuntime(GlobalConfig globalConfig) {
        this.globalConfig = globalConfig;
        if (this.globalConfig.getAdmin() == null) {
            this.globalConfig.setAdmin(new com.model.AdminConfig());
        }
        if (this.globalConfig.getStore() == null) {
            this.globalConfig.setStore(new com.model.StoreConfig());
        }
        if (this.globalConfig.getCapture() == null) {
            this.globalConfig.setCapture(new com.model.CaptureConfig());
        }
        this.sqliteDatabase = new SqliteDatabase(globalConfig.getStore().getSqlitePath());
        new DatabaseInitializer(sqliteDatabase).initialize();
        this.mappingRepository = new MappingRepository(sqliteDatabase);
        ConnectionRepository connectionRepository = new ConnectionRepository(sqliteDatabase);
        PacketRepository packetRepository = new PacketRepository(sqliteDatabase);
        this.packetCaptureService = new PacketCaptureService(
                new CaptureOptions(globalConfig.getCapture()),
                packetRepository,
                new SpillFileManager(globalConfig.getStore().getSpillDir()));
        this.nettyGroups = new NettyGroups();
        this.runtimeMappingManager = new RuntimeMappingManager(nettyGroups, mappingRepository, connectionRepository, packetCaptureService);
        this.nettyAdminServer = new NettyAdminServer(globalConfig.getAdmin(), runtimeMappingManager, packetCaptureService, nettyGroups);
    }

    public void start() {
        packetCaptureService.start();
        List<MappingEntity> persistedMappings = mappingRepository.findAllActive();
        if (persistedMappings.size() > 0) {
            log.info("从 SQLite 恢复 {} 个 mapping", persistedMappings.size());
            runtimeMappingManager.restoreAll(persistedMappings);
        } else if (globalConfig.getMappings() == null || globalConfig.getMappings().size() == 0) {
            log.info("SQLite 和启动配置中都没有 mapping，当前仅启动 runtime 基础服务");
        } else {
            log.info("SQLite 中没有 mapping，导入 yml 初始配置");
            globalConfig.verifyConfiguration();
            runtimeMappingManager.startAll(globalConfig.getMappings());
        }
        nettyAdminServer.start();
    }

    public void shutdown() {
        nettyAdminServer.stop();
        runtimeMappingManager.shutdown();
        packetCaptureService.shutdown();
        nettyGroups.shutdown();
    }

    public RuntimeMappingManager getRuntimeMappingManager() {
        return runtimeMappingManager;
    }
}
