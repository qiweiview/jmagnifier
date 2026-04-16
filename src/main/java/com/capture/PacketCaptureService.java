package com.capture;

import com.store.PacketRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class PacketCaptureService {

    private final CaptureOptions options;

    private final PacketRepository packetRepository;

    private final SpillFileManager spillFileManager;

    private final ArrayBlockingQueue<PacketEvent> queue;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicLong packetsWritten = new AtomicLong(0);

    private final AtomicLong packetsDropped = new AtomicLong(0);

    private final AtomicLong packetsSpilled = new AtomicLong(0);

    private final AtomicLong spillErrors = new AtomicLong(0);

    private final Object spillLock = new Object();

    private volatile String lastWriterError;

    private Thread writerThread;

    public PacketCaptureService(CaptureOptions options, PacketRepository packetRepository) {
        this(options, packetRepository, null);
    }

    public PacketCaptureService(CaptureOptions options, PacketRepository packetRepository, SpillFileManager spillFileManager) {
        this.options = options;
        this.packetRepository = packetRepository;
        this.spillFileManager = spillFileManager;
        this.queue = new ArrayBlockingQueue<>(options.getQueueCapacity());
    }

    public void start() {
        if (!options.isEnabled()) {
            log.info("packet capture disabled");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        writerThread = new Thread(this::writeLoop, "packet-capture-writer");
        writerThread.setDaemon(false);
        writerThread.start();
    }

    public void capture(PacketEvent event, byte[] bytes) {
        if (!options.isEnabled() || !running.get() || event == null || bytes == null) {
            return;
        }
        try {
            PacketEvent captured = truncate(event, bytes);
            if (!queue.offer(captured)) {
                spillQueueToDisk();
                if (!queue.offer(captured)) {
                    packetsDropped.incrementAndGet();
                    log.warn("packet capture queue is full after spill, dropped one packet event");
                }
            }
        } catch (Exception e) {
            packetsDropped.incrementAndGet();
            log.warn("capture packet failed:{}", e.getMessage());
        }
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (writerThread != null) {
            try {
                writerThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (writerThread.isAlive()) {
                log.warn("packet capture writer did not stop within timeout");
                return;
            }
        }
        drainAndWrite();
        while (spillFileManager != null && spillFileManager.nextFile() != null) {
            replayOneSpillFile();
        }
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getQueueCapacity() {
        return options.getQueueCapacity();
    }

    public long getPacketsWritten() {
        return packetsWritten.get();
    }

    public long getPacketsDropped() {
        return packetsDropped.get();
    }

    public long getPacketsSpilled() {
        return packetsSpilled.get();
    }

    public long getSpillErrors() {
        return spillErrors.get();
    }

    public int getSpillFileCount() {
        return spillFileManager == null ? 0 : spillFileManager.countFiles();
    }

    public long getSpillBytes() {
        return spillFileManager == null ? 0 : spillFileManager.totalBytes();
    }

    public String getLastWriterError() {
        return lastWriterError;
    }

    private PacketEvent truncate(PacketEvent event, byte[] bytes) {
        int payloadSize = bytes.length;
        int capturedSize = Math.min(payloadSize, options.getMaxCaptureBytes());
        event.setPayloadSize(payloadSize);
        event.setCapturedSize(capturedSize);
        event.setTruncated(payloadSize > capturedSize);
        event.setPayload(capturedSize == 0 ? new byte[0] : Arrays.copyOf(bytes, capturedSize));
        return event;
    }

    private void writeLoop() {
        while (running.get() || queue.size() > 0) {
            drainAndWrite();
        }
    }

    private void drainAndWrite() {
        List<PacketEvent> batch = new ArrayList<>(options.getBatchSize());
        try {
            PacketEvent first = queue.poll(options.getFlushIntervalMillis(), TimeUnit.MILLISECONDS);
            if (first != null) {
                batch.add(first);
                queue.drainTo(batch, options.getBatchSize() - 1);
            }
            if (batch.size() > 0) {
                packetRepository.insertBatch(batch);
                packetsWritten.addAndGet(batch.size());
                lastWriterError = null;
            } else {
                replayOneSpillFile();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            packetsDropped.addAndGet(batch.size());
            lastWriterError = e.getMessage();
            log.warn("write packet batch failed:{}", e.getMessage());
        }
    }

    private void spillQueueToDisk() {
        if (spillFileManager == null) {
            packetsDropped.addAndGet(queue.size());
            queue.clear();
            return;
        }
        synchronized (spillLock) {
            List<PacketEvent> events = new ArrayList<>(queue.size());
            queue.drainTo(events);
            if (events.size() == 0) {
                return;
            }
            try {
                File spillFile = spillFileManager.write(events);
                packetsSpilled.addAndGet(events.size());
                log.warn("packet capture queue spilled {} events to {}", events.size(), spillFile == null ? "none" : spillFile.getAbsolutePath());
            } catch (Exception e) {
                spillErrors.incrementAndGet();
                packetsDropped.addAndGet(events.size());
                log.warn("spill packet queue failed, dropped {} events cause:{}", events.size(), e.getMessage());
            }
        }
    }

    private void replayOneSpillFile() {
        if (spillFileManager == null) {
            return;
        }
        File spillFile = spillFileManager.nextFile();
        if (spillFile == null) {
            return;
        }
        try {
            List<PacketEvent> events = spillFileManager.read(spillFile);
            packetRepository.insertBatch(events);
            packetsWritten.addAndGet(events.size());
            spillFileManager.delete(spillFile);
            lastWriterError = null;
        } catch (Exception e) {
            lastWriterError = e.getMessage();
            spillErrors.incrementAndGet();
            log.warn("replay spill file failed:{} cause:{}", spillFile.getAbsolutePath(), e.getMessage());
            spillFileManager.moveToBad(spillFile);
        }
    }
}
