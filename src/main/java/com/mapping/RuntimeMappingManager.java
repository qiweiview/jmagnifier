package com.mapping;

import com.capture.PacketCaptureService;
import com.core.DataReceiver;
import com.model.Mapping;
import com.runtime.NettyGroups;
import com.store.ConnectionRepository;
import com.store.MappingEntity;
import com.store.MappingRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class RuntimeMappingManager {

    private final NettyGroups nettyGroups;

    private final MappingRepository mappingRepository;

    private final ConnectionRepository connectionRepository;

    private final PacketCaptureService packetCaptureService;

    private final AtomicLong mappingIdGenerator = new AtomicLong(1);

    private final Map<Long, MappingRuntime> runtimes = new ConcurrentHashMap<>();

    private final Object mappingMutationLock = new Object();

    public RuntimeMappingManager(NettyGroups nettyGroups) {
        this(nettyGroups, null, null, null);
    }

    public RuntimeMappingManager(NettyGroups nettyGroups, MappingRepository mappingRepository, ConnectionRepository connectionRepository,
                                 PacketCaptureService packetCaptureService) {
        this.nettyGroups = nettyGroups;
        this.mappingRepository = mappingRepository;
        this.connectionRepository = connectionRepository;
        this.packetCaptureService = packetCaptureService;
    }

    public List<MappingRuntime> startAll(List<Mapping> mappings) {
        if (mappings == null || mappings.size() == 0) {
            return Collections.emptyList();
        }
        List<MappingRuntime> started = new ArrayList<>();
        for (Mapping mapping : mappings) {
            started.add(startMapping(mapping));
        }
        return started;
    }

    public List<MappingRuntime> restoreAll(List<MappingEntity> mappings) {
        if (mappings == null || mappings.size() == 0) {
            return Collections.emptyList();
        }
        List<MappingRuntime> started = new ArrayList<>();
        for (MappingEntity mappingEntity : mappings) {
            started.add(startExistingMapping(mappingEntity.getId(), mappingEntity.toMapping()));
        }
        return started;
    }

    public MappingRuntime startMapping(Mapping mapping) {
        synchronized (mappingMutationLock) {
            validateMapping(mapping, null);
            long mappingId = mappingRepository == null ? mappingIdGenerator.getAndIncrement() : mappingRepository.insert(mapping);
            return startExistingMapping(mappingId, mapping);
        }
    }

    public MappingRuntime startExistingMapping(long mappingId, Mapping mapping) {
        synchronized (mappingMutationLock) {
            validateMapping(mapping, mappingId);
            MappingRuntime runtime = new MappingRuntime(mappingId, mapping);
            runtimes.put(mappingId, runtime);
            startRuntime(runtime);
            return runtime;
        }
    }

    public void stopMapping(long mappingId) {
        synchronized (mappingMutationLock) {
            MappingRuntime runtime = getRequiredRuntime(mappingId);
            stopRuntime(runtime);
        }
    }

    public MappingRuntime updateMapping(long mappingId, Mapping newMapping) {
        synchronized (mappingMutationLock) {
            MappingRuntime runtime = getRequiredRuntime(mappingId);
            validateMapping(newMapping, mappingId);
            boolean wasRunning = runtime.getStatus() == MappingStatus.RUNNING;
            stopRuntime(runtime);
            if (mappingRepository != null) {
                mappingRepository.update(mappingId, newMapping);
            }
            runtime.setMappingSnapshot(newMapping);
            runtime.setLastError(null);
            if (wasRunning && Boolean.TRUE.equals(newMapping.getEnable())) {
                startRuntime(runtime);
            } else {
                runtime.setStatus(MappingStatus.STOPPED);
            }
            return runtime;
        }
    }

    public void deleteMapping(long mappingId) {
        synchronized (mappingMutationLock) {
            MappingRuntime runtime = getRequiredRuntime(mappingId);
            stopRuntime(runtime);
            if (mappingRepository != null) {
                mappingRepository.softDelete(mappingId);
            }
            runtimes.remove(mappingId);
        }
    }

    public List<MappingRuntime> listMappingsWithStatus() {
        return new ArrayList<>(runtimes.values());
    }

    public void shutdown() {
        synchronized (mappingMutationLock) {
            for (MappingRuntime runtime : new ArrayList<>(runtimes.values())) {
                stopRuntime(runtime);
            }
            runtimes.clear();
        }
    }

    private void startRuntime(MappingRuntime runtime) {
        Mapping mapping = runtime.getMappingSnapshot();
        if (!Boolean.TRUE.equals(mapping.getEnable())) {
            runtime.setStatus(MappingStatus.STOPPED);
            runtime.setStoppedAt(Instant.now());
            return;
        }
        runtime.setStatus(MappingStatus.STARTING);
        runtime.setLastError(null);
        DataReceiver dataReceiver = new DataReceiver(runtime.getMappingId(), mapping,
                nettyGroups.getTcpBossGroup(),
                nettyGroups.getTcpWorkerGroup(),
                nettyGroups.getTcpClientGroup(),
                connectionRepository,
                packetCaptureService);
        runtime.setDataReceiver(dataReceiver);
        try {
            dataReceiver.start();
            runtime.setStatus(MappingStatus.RUNNING);
            runtime.setStartedAt(Instant.now());
            log.info("mapping {} started on port {}", mapping.getName(), dataReceiver.getBoundPort());
        } catch (Exception e) {
            runtime.setStatus(MappingStatus.FAILED);
            runtime.setLastError(e.getMessage());
            log.error("mapping {} start failed", mapping.getName(), e);
            throw e;
        }
    }

    private void stopRuntime(MappingRuntime runtime) {
        DataReceiver dataReceiver = runtime.getDataReceiver();
        if (dataReceiver == null || runtime.getStatus() == MappingStatus.STOPPED) {
            runtime.setStatus(MappingStatus.STOPPED);
            runtime.setStoppedAt(Instant.now());
            return;
        }
        runtime.setStatus(MappingStatus.STOPPING);
        try {
            dataReceiver.stop();
            runtime.setStatus(MappingStatus.STOPPED);
            runtime.setStoppedAt(Instant.now());
        } catch (Exception e) {
            runtime.setStatus(MappingStatus.FAILED);
            runtime.setLastError(e.getMessage());
            log.error("mapping {} stop failed", runtime.getMappingSnapshot().getName(), e);
            throw e;
        }
    }

    private MappingRuntime getRequiredRuntime(long mappingId) {
        MappingRuntime runtime = runtimes.get(mappingId);
        if (runtime == null) {
            throw new RuntimeException("mapping not found: " + mappingId);
        }
        return runtime;
    }

    private void validateMapping(Mapping mapping, Long currentMappingId) {
        if (mapping == null) {
            throw new RuntimeException("mapping is null");
        }
        mapping.applyDefaults();
        if (mapping.getListenPort() < 0 || mapping.getListenPort() > 65535
                || mapping.getForwardPort() < 0 || mapping.getForwardPort() > 65535) {
            throw new RuntimeException("invalid port");
        }
        if (mapping.getForwardHost() == null || mapping.getForwardHost().trim().length() == 0) {
            throw new RuntimeException("forward host is empty");
        }
        if (!Boolean.TRUE.equals(mapping.getEnable())) {
            return;
        }
        for (MappingRuntime runtime : runtimes.values()) {
            if (currentMappingId != null && runtime.getMappingId() == currentMappingId) {
                continue;
            }
            if (runtime.getStatus() == MappingStatus.RUNNING
                    && runtime.getMappingSnapshot().getListenPort() == mapping.getListenPort()) {
                throw new RuntimeException("listen port already configured: " + mapping.getListenPort());
            }
        }
    }
}
