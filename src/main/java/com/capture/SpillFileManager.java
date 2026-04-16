package com.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class SpillFileManager {

    private static final Logger log = LoggerFactory.getLogger(SpillFileManager.class);

    private static final byte[] MAGIC = new byte[]{'J', 'M', 'S', 'P'};

    private static final byte VERSION = 2;

    private final File spillDir;

    private final File badDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicLong sequence = new AtomicLong(0);

    public SpillFileManager(String spillDir) {
        this.spillDir = new File(spillDir == null || spillDir.trim().length() == 0 ? "./data/spill" : spillDir);
        this.badDir = new File(this.spillDir, "bad");
    }

    public File write(List<PacketEvent> events) {
        if (events == null || events.size() == 0) {
            return null;
        }
        ensureDirectory(spillDir);
        String baseName = "spill-" + System.currentTimeMillis() + "-" + sequence.incrementAndGet();
        File tmpFile = new File(spillDir, baseName + ".tmp");
        File binFile = new File(spillDir, baseName + ".bin");
        try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile);
             BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
             DataOutputStream outputStream = new DataOutputStream(bufferedOutputStream)) {
            outputStream.write(MAGIC);
            outputStream.writeByte(VERSION);
            outputStream.writeInt(events.size());
            for (PacketEvent event : events) {
                byte[] payload = event.getPayload() == null ? new byte[0] : event.getPayload();
                byte[] preview = event.getPayloadPreview() == null ? new byte[0] : event.getPayloadPreview();
                PacketEvent metadata = copyWithoutPayload(event);
                byte[] metadataBytes = objectMapper.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8);
                outputStream.writeInt(metadataBytes.length + preview.length + payload.length + 8);
                outputStream.writeInt(metadataBytes.length);
                outputStream.write(metadataBytes);
                outputStream.writeInt(preview.length);
                outputStream.write(preview);
                outputStream.writeInt(payload.length);
                outputStream.write(payload);
            }
            outputStream.flush();
            fileOutputStream.getFD().sync();
        } catch (IOException e) {
            tmpFile.delete();
            throw new RuntimeException("write spill file failed", e);
        }
        try {
            Files.move(tmpFile.toPath(), binFile.toPath());
        } catch (IOException e) {
            tmpFile.delete();
            throw new RuntimeException("rename spill file failed", e);
        }
        return binFile;
    }

    public File nextFile() {
        ensureDirectory(spillDir);
        File[] files = spillDir.listFiles((dir, name) -> name.endsWith(".bin"));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files[0];
    }

    public List<PacketEvent> read(File file) {
        try (DataInputStream inputStream = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            byte[] magic = new byte[4];
            inputStream.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("invalid spill magic");
            }
            byte version = inputStream.readByte();
            if (version != 1 && version != VERSION) {
                throw new IOException("unsupported spill version: " + version);
            }
            int count = inputStream.readInt();
            if (count < 0) {
                throw new IOException("invalid spill count: " + count);
            }
            List<PacketEvent> events = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int frameLength = inputStream.readInt();
                int metadataLength = inputStream.readInt();
                if (frameLength < 0 || metadataLength < 0 || metadataLength > frameLength) {
                    throw new IOException("invalid spill frame length");
                }
                byte[] metadataBytes = new byte[metadataLength];
                inputStream.readFully(metadataBytes);
                PacketEvent event = objectMapper.readValue(new String(metadataBytes, StandardCharsets.UTF_8), PacketEvent.class);
                if (version == 1) {
                    int payloadLength = frameLength - metadataLength;
                    byte[] payload = new byte[payloadLength];
                    inputStream.readFully(payload);
                    event.setPayload(payload);
                    event.setPayloadPreview(payload);
                } else {
                    int previewLength = inputStream.readInt();
                    if (previewLength < 0) {
                        throw new IOException("invalid spill preview length");
                    }
                    byte[] preview = new byte[previewLength];
                    inputStream.readFully(preview);
                    int payloadLength = inputStream.readInt();
                    if (payloadLength < 0) {
                        throw new IOException("invalid spill payload length");
                    }
                    byte[] payload = new byte[payloadLength];
                    inputStream.readFully(payload);
                    event.setPayloadPreview(preview);
                    event.setPayload(payload);
                }
                events.add(event);
            }
            return events;
        } catch (IOException e) {
            throw new RuntimeException("read spill file failed:" + file, e);
        }
    }

    public void delete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("delete spill file failed:{}", file.getAbsolutePath());
        }
    }

    public void moveToBad(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        ensureDirectory(badDir);
        File badFile = new File(badDir, file.getName() + ".bad");
        try {
            Files.move(file.toPath(), badFile.toPath());
        } catch (IOException e) {
            log.warn("move bad spill file failed:{} cause:{}", file.getAbsolutePath(), e.getMessage());
        }
    }

    public int countFiles() {
        File[] files = spillDir.listFiles((dir, name) -> name.endsWith(".bin"));
        return files == null ? 0 : files.length;
    }

    public long totalBytes() {
        File[] files = spillDir.listFiles((dir, name) -> name.endsWith(".bin"));
        if (files == null) {
            return 0;
        }
        long bytes = 0;
        for (File file : files) {
            bytes += file.length();
        }
        return bytes;
    }

    private PacketEvent copyWithoutPayload(PacketEvent event) {
        PacketEvent copy = new PacketEvent();
        copy.setMappingId(event.getMappingId());
        copy.setConnectionId(event.getConnectionId());
        copy.setDirection(event.getDirection());
        copy.setClientIp(event.getClientIp());
        copy.setClientPort(event.getClientPort());
        copy.setListenIp(event.getListenIp());
        copy.setListenPort(event.getListenPort());
        copy.setTargetHost(event.getTargetHost());
        copy.setTargetPort(event.getTargetPort());
        copy.setRemoteIp(event.getRemoteIp());
        copy.setRemotePort(event.getRemotePort());
        copy.setPayloadSize(event.getPayloadSize());
        copy.setCapturedSize(event.getCapturedSize());
        copy.setTruncated(event.isTruncated());
        copy.setSequenceNo(event.getSequenceNo());
        copy.setReceivedAt(event.getReceivedAt());
        copy.setProtocolFamily(event.getProtocolFamily());
        copy.setApplicationProtocol(event.getApplicationProtocol());
        copy.setContentType(event.getContentType());
        copy.setHttpMethod(event.getHttpMethod());
        copy.setHttpUri(event.getHttpUri());
        copy.setHttpStatus(event.getHttpStatus());
        copy.setPayloadStoreType(event.getPayloadStoreType());
        copy.setPayloadPreviewSize(event.getPayloadPreviewSize());
        copy.setPayloadComplete(event.isPayloadComplete());
        return copy;
    }

    private void ensureDirectory(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new RuntimeException("create directory failed: " + directory.getAbsolutePath());
        }
    }
}
