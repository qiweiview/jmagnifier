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
import com.store.PayloadFileStore;
import com.store.PacketRepository;
import com.store.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class AppRuntime {

    private static final Logger log = LoggerFactory.getLogger(AppRuntime.class);

    private final GlobalConfig globalConfig;

    private final NettyGroups nettyGroups;

    private final RuntimeMappingManager runtimeMappingManager;

    private final SqliteDatabase sqliteDatabase;

    private final MappingRepository mappingRepository;

    private final PayloadFileStore payloadFileStore;

    private final PacketRepository packetRepository;

    private final PacketCaptureService packetCaptureService;

    private final NettyAdminServer nettyAdminServer;

    private ScheduledExecutorService payloadRetentionExecutor;

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
        this.payloadFileStore = new PayloadFileStore(
                globalConfig.getStore().getPayloadDir(),
                globalConfig.getStore().getPayloadSegmentBytes());
        this.packetRepository = new PacketRepository(sqliteDatabase, payloadFileStore);
        this.packetCaptureService = new PacketCaptureService(
                new CaptureOptions(globalConfig.getCapture()),
                this.packetRepository,
                new SpillFileManager(globalConfig.getStore().getSpillDir()));
        this.nettyGroups = new NettyGroups();
        this.runtimeMappingManager = new RuntimeMappingManager(nettyGroups, mappingRepository, connectionRepository, packetCaptureService);
        this.nettyAdminServer = new NettyAdminServer(globalConfig.getAdmin(), runtimeMappingManager, packetCaptureService, nettyGroups,
                connectionRepository, this.packetRepository, this.payloadFileStore);
    }

    public void start() {
        log.info("抓包配置生效值：enabled={}, previewBytes={}, payloadStoreType={}, maxPayloadBytes={}, queueCapacity={}, batchSize={}, flushIntervalMillis={}, sqlitePath={}, spillDir={}, payloadDir={}, payloadSegmentBytes={}, payloadRetentionDays={}, payloadRetentionBytes={}",
                packetCaptureService.isEnabled(),
                packetCaptureService.getPreviewBytes(),
                packetCaptureService.getPayloadStoreType(),
                packetCaptureService.getMaxPayloadBytes(),
                packetCaptureService.getQueueCapacity(),
                packetCaptureService.getBatchSize(),
                packetCaptureService.getFlushIntervalMillis(),
                globalConfig.getStore().getSqlitePath(),
                globalConfig.getStore().getSpillDir(),
                globalConfig.getStore().getPayloadDir(),
                globalConfig.getStore().getPayloadSegmentBytes(),
                globalConfig.getStore().getPayloadRetentionDays(),
                globalConfig.getStore().getPayloadRetentionBytes());
        packetCaptureService.start();
        runPayloadRetention("startup");
        startPayloadRetentionScheduler();
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
        stopPayloadRetentionScheduler();
        nettyGroups.shutdown();
    }

    public RuntimeMappingManager getRuntimeMappingManager() {
        return runtimeMappingManager;
    }

    private void startPayloadRetentionScheduler() {
        if (!isPayloadRetentionEnabled()) {
            return;
        }
        payloadRetentionExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "payload-retention-cleaner");
                thread.setDaemon(true);
                return thread;
            }
        });
        payloadRetentionExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                runPayloadRetention("scheduled");
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    private void stopPayloadRetentionScheduler() {
        if (payloadRetentionExecutor == null) {
            return;
        }
        payloadRetentionExecutor.shutdownNow();
        payloadRetentionExecutor = null;
    }

    private boolean isPayloadRetentionEnabled() {
        return globalConfig.getStore().getPayloadRetentionDays() > 0
                || globalConfig.getStore().getPayloadRetentionBytes() > 0;
    }

    private void runPayloadRetention(String trigger) {
        if (!isPayloadRetentionEnabled()) {
            return;
        }
        try {
            PayloadFileStore.RetentionResult result = payloadFileStore.enforceRetention(
                    globalConfig.getStore().getPayloadRetentionDays(),
                    globalConfig.getStore().getPayloadRetentionBytes());
            if (result.getDeletedFiles() > 0) {
                packetRepository.markPayloadFilesDeleted(result.getDeletedRelativePaths());
                log.info("payload retention {} deletedFiles={}, deletedBytes={}, payloadFilesActive={}, payloadBytesOnDisk={}",
                        trigger,
                        result.getDeletedFiles(),
                        result.getDeletedBytes(),
                        payloadFileStore.countSegmentFiles(),
                        payloadFileStore.totalPayloadBytes());
            }
        } catch (RuntimeException e) {
            log.warn("payload retention {} failed cause:{}", trigger, e.getMessage());
        }
    }
}
