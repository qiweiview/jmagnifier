package com.mapping;

import com.capture.PacketCaptureService;
import com.core.DataReceiver;
import com.model.EndpointConfig;
import com.model.Mapping;
import com.model.TlsConfig;
import com.protocol.DefaultProtocolPipelineFactory;
import com.protocol.ProtocolPipelineFactory;
import com.runtime.NettyGroups;
import com.store.ConnectionRepository;
import com.store.MappingEntity;
import com.store.MappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RuntimeMappingManager {

    private static final Logger log = LoggerFactory.getLogger(RuntimeMappingManager.class);

    private final NettyGroups nettyGroups;

    private final MappingRepository mappingRepository;

    private final ConnectionRepository connectionRepository;

    private final PacketCaptureService packetCaptureService;

    private final ProtocolPipelineFactory protocolPipelineFactory;

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
        this.protocolPipelineFactory = new DefaultProtocolPipelineFactory();
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
            try {
                started.add(startExistingMapping(mappingEntity.getId(), mappingEntity.toMapping(), true));
            } catch (RuntimeException e) {
                log.warn("restore mapping {} failed:{}", mappingEntity.getId(), e.getMessage());
                MappingRuntime runtime = runtimes.get(mappingEntity.getId());
                if (runtime != null) {
                    started.add(runtime);
                }
            }
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
        return startExistingMapping(mappingId, mapping, false);
    }

    private MappingRuntime startExistingMapping(long mappingId, Mapping mapping, boolean tolerateStartFailure) {
        synchronized (mappingMutationLock) {
            validateMapping(mapping, mappingId);
            MappingRuntime runtime = new MappingRuntime(mappingId, mapping);
            runtimes.put(mappingId, runtime);
            try {
                startRuntime(runtime);
            } catch (RuntimeException e) {
                if (!tolerateStartFailure) {
                    throw e;
                }
            }
            return runtime;
        }
    }

    public void stopMapping(long mappingId) {
        synchronized (mappingMutationLock) {
            MappingRuntime runtime = getRequiredRuntime(mappingId);
            stopRuntime(runtime);
            Mapping mapping = runtime.getMappingSnapshot();
            mapping.setEnable(false);
            if (mappingRepository != null) {
                mappingRepository.update(mappingId, mapping);
            }
        }
    }

    public MappingRuntime startMapping(long mappingId) {
        synchronized (mappingMutationLock) {
            MappingRuntime runtime = getRequiredRuntime(mappingId);
            Mapping mapping = runtime.getMappingSnapshot();
            if (runtime.getStatus() == MappingStatus.RUNNING) {
                return runtime;
            }
            mapping.setEnable(true);
            validateMapping(mapping, mappingId);
            if (mappingRepository != null) {
                mappingRepository.update(mappingId, mapping);
            }
            runtime.setLastError(null);
            startRuntime(runtime);
            return runtime;
        }
    }

    public MappingRuntime updateMapping(long mappingId, Mapping newMapping) {
        synchronized (mappingMutationLock) {
            MappingRuntime runtime = getRequiredRuntime(mappingId);
            validateMapping(newMapping, mappingId);
            stopRuntime(runtime);
            if (mappingRepository != null) {
                mappingRepository.update(mappingId, newMapping);
            }
            runtime.setMappingSnapshot(newMapping);
            runtime.setLastError(null);
            if (Boolean.TRUE.equals(newMapping.getEnable())) {
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
                packetCaptureService,
                protocolPipelineFactory);
        runtime.setDataReceiver(dataReceiver);
        try {
            dataReceiver.start();
            runtime.setStatus(MappingStatus.RUNNING);
            runtime.setStartedAt(Instant.now());
            log.info("mapping {} started on port {}", mapping.getName(), dataReceiver.getBoundPort());
        } catch (Exception e) {
            runtime.setStatus(MappingStatus.FAILED);
            String message = "bind listen port failed: " + mapping.getListenPort();
            runtime.setLastError(message);
            log.error("mapping {} start failed", mapping.getName(), e);
            throw new MappingOperationException("BIND_FAILED", message, e);
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
            throw new MappingOperationException("MAPPING_NOT_FOUND", "mapping not found: " + mappingId);
        }
        return runtime;
    }

    private void validateMapping(Mapping mapping, Long currentMappingId) {
        if (mapping == null) {
            throw new MappingOperationException("BAD_REQUEST", "mapping is null");
        }
        mapping.applyDefaults();
        if (mapping.getListenPort() < 0 || mapping.getListenPort() > 65535
                || mapping.getForwardPort() < 0 || mapping.getForwardPort() > 65535) {
            throw new MappingOperationException("INVALID_PORT", "listenPort and forwardPort must be between 0 and 65535");
        }
        if (mapping.getForwardHost() == null || mapping.getForwardHost().trim().length() == 0) {
            throw new MappingOperationException("INVALID_FORWARD_HOST", "forwardHost is required");
        }
        validateProtocolConfiguration(mapping);
        if (!Boolean.TRUE.equals(mapping.getEnable())) {
            return;
        }
        for (MappingRuntime runtime : runtimes.values()) {
            if (currentMappingId != null && runtime.getMappingId() == currentMappingId) {
                continue;
            }
            if (runtime.getStatus() == MappingStatus.RUNNING
                    && runtime.getMappingSnapshot().getListenPort() == mapping.getListenPort()) {
                throw new MappingOperationException("PORT_ALREADY_CONFIGURED",
                        "listen port already configured: " + mapping.getListenPort());
            }
        }
    }

    private void validateProtocolConfiguration(Mapping mapping) {
        String listenProtocol = mapping.getListen() == null ? null : mapping.getListen().getApplicationProtocol();
        String forwardProtocol = mapping.getForward() == null ? null : mapping.getForward().getApplicationProtocol();
        if (!isSupportedApplicationProtocol(listenProtocol) || !isSupportedApplicationProtocol(forwardProtocol)) {
            throw new MappingOperationException("INVALID_PROTOCOL", "applicationProtocol must be tcp or http");
        }
        if (mapping.isHttpPath() && (!"http".equalsIgnoreCase(listenProtocol) || !"http".equalsIgnoreCase(forwardProtocol))) {
            throw new MappingOperationException("INVALID_PROTOCOL_COMBINATION",
                    "http mappings require both listen.applicationProtocol and forward.applicationProtocol to be http");
        }
        if (!mapping.isRawTcpPath() && !mapping.isHttpPath()) {
            throw new MappingOperationException("UNSUPPORTED_PROTOCOL",
                    "unsupported protocol pipeline: " + mapping.getListenMode() + " -> " + mapping.getForwardMode());
        }
        validateTlsConfiguration(mapping.getListen(), true);
        validateTlsConfiguration(mapping.getForward(), false);
    }

    private void validateTlsConfiguration(EndpointConfig endpointConfig, boolean listenSide) {
        if (endpointConfig == null || endpointConfig.getTls() == null || !Boolean.TRUE.equals(endpointConfig.getTls().getEnabled())) {
            return;
        }
        if (!"http".equalsIgnoreCase(endpointConfig.getApplicationProtocol())) {
            throw new MappingOperationException("UNSUPPORTED_PROTOCOL", "TLS is currently supported only for HTTP mappings");
        }
        if (listenSide) {
            TlsConfig tls = endpointConfig.getTls();
            if (tls.getCertificateFile() == null || tls.getCertificateFile().trim().length() == 0
                    || tls.getPrivateKeyFile() == null || tls.getPrivateKeyFile().trim().length() == 0) {
                throw new MappingOperationException("INVALID_TLS_CONFIG",
                        "listen TLS requires certificateFile and privateKeyFile");
            }
        }
    }

    private boolean isSupportedApplicationProtocol(String protocol) {
        return "tcp".equalsIgnoreCase(protocol) || "http".equalsIgnoreCase(protocol);
    }
}
