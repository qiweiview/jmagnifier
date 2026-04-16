package com.store;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

public class PayloadFileStore {

    private static final byte[] MAGIC = new byte[]{'J', 'M', 'P', 'L'};

    private static final byte VERSION = 1;

    private static final int HEADER_BYTES = 17;

    private final File payloadDir;

    private final long payloadSegmentBytes;

    public PayloadFileStore(String payloadDir, long payloadSegmentBytes) {
        this.payloadDir = new File(payloadDir == null || payloadDir.trim().length() == 0 ? "./data/payload" : payloadDir);
        this.payloadSegmentBytes = payloadSegmentBytes <= 0 ? 128L * 1024L * 1024L : payloadSegmentBytes;
    }

    public synchronized PayloadWriteResult write(long mappingId, String receivedAt, byte[] payload, boolean payloadComplete) {
        byte[] data = payload == null ? new byte[0] : payload;
        int frameBytes = HEADER_BYTES + data.length;
        File mappingDir = resolveMappingDir(mappingId, receivedAt);
        ensureDirectory(mappingDir);
        File segmentFile = resolveWritableSegment(mappingDir, frameBytes);
        long offset;
        try (RandomAccessFile file = new RandomAccessFile(segmentFile, "rw")) {
            offset = file.length();
            file.seek(offset);
            file.write(MAGIC);
            file.writeByte(VERSION);
            file.writeLong(data.length);
            file.writeInt(payloadComplete ? 1 : 0);
            file.write(data);
            file.getFD().sync();
        } catch (IOException e) {
            throw new RuntimeException("write payload segment failed", e);
        }
        return new PayloadWriteResult(relativePath(segmentFile), offset, data.length, payloadComplete, sha256(data));
    }

    public byte[] read(String relativePath, long offset, long expectedLength) {
        File file = resolve(relativePath);
        if (!file.exists()) {
            throw new RuntimeException("payload file does not exist:" + file.getAbsolutePath());
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            randomAccessFile.seek(offset);
            byte[] magic = new byte[4];
            randomAccessFile.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("invalid payload frame magic");
            }
            byte version = randomAccessFile.readByte();
            if (version != VERSION) {
                throw new IOException("unsupported payload frame version:" + version);
            }
            long actualLength = randomAccessFile.readLong();
            randomAccessFile.readInt();
            if (actualLength < 0 || actualLength > Integer.MAX_VALUE) {
                throw new IOException("invalid payload frame length:" + actualLength);
            }
            if (expectedLength >= 0 && actualLength != expectedLength) {
                throw new IOException("payload frame length mismatch expected:" + expectedLength + " actual:" + actualLength);
            }
            byte[] payload = new byte[(int) actualLength];
            randomAccessFile.readFully(payload);
            return payload;
        } catch (IOException e) {
            throw new RuntimeException("read payload file failed", e);
        }
    }

    public boolean exists(String relativePath) {
        return relativePath != null && relativePath.trim().length() > 0 && resolve(relativePath).exists();
    }

    public File resolve(String relativePath) {
        if (relativePath == null || relativePath.trim().length() == 0) {
            return payloadDir;
        }
        return new File(payloadDir, relativePath.replace('/', File.separatorChar));
    }

    private File resolveMappingDir(long mappingId, String receivedAt) {
        return new File(new File(payloadDir, resolveDate(receivedAt)), "mapping-" + Math.max(0, mappingId));
    }

    private String resolveDate(String receivedAt) {
        if (receivedAt != null && receivedAt.length() >= 10) {
            return receivedAt.substring(0, 10);
        }
        try {
            if (receivedAt == null || receivedAt.trim().length() == 0) {
                throw new DateTimeParseException("empty receivedAt", "", 0);
            }
            return Instant.parse(receivedAt).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (DateTimeParseException ignore) {
            return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
    }

    private File resolveWritableSegment(File mappingDir, int frameBytes) {
        File[] files = mappingDir.listFiles((dir, name) -> name.endsWith(".seg"));
        if (files == null || files.length == 0) {
            return new File(mappingDir, "packet-000001.seg");
        }
        Arrays.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        File lastFile = files[files.length - 1];
        if (lastFile.length() + frameBytes <= payloadSegmentBytes) {
            return lastFile;
        }
        String name = lastFile.getName();
        int start = name.indexOf('-');
        int end = name.lastIndexOf('.');
        int next = 1;
        if (start >= 0 && end > start) {
            try {
                next = Integer.parseInt(name.substring(start + 1, end)) + 1;
            } catch (NumberFormatException ignore) {
                next = files.length + 1;
            }
        }
        return new File(mappingDir, String.format("packet-%06d.seg", next));
    }

    private String relativePath(File file) {
        String base = payloadDir.getAbsolutePath();
        String value = file.getAbsolutePath();
        if (value.startsWith(base)) {
            value = value.substring(base.length());
        }
        while (value.startsWith(File.separator)) {
            value = value.substring(1);
        }
        return value.replace(File.separatorChar, '/');
    }

    private String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload == null ? new byte[0] : payload);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                int unsigned = value & 0xff;
                if (unsigned < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(unsigned));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("sha-256 unavailable", e);
        }
    }

    private void ensureDirectory(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new RuntimeException("create payload directory failed:" + directory.getAbsolutePath());
        }
    }

    public static class PayloadWriteResult {

        private final String relativePath;

        private final long offset;

        private final int length;

        private final boolean complete;

        private final String sha256;

        public PayloadWriteResult(String relativePath, long offset, int length, boolean complete, String sha256) {
            this.relativePath = relativePath;
            this.offset = offset;
            this.length = length;
            this.complete = complete;
            this.sha256 = sha256;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public long getOffset() {
            return offset;
        }

        public int getLength() {
            return length;
        }

        public boolean isComplete() {
            return complete;
        }

        public String getSha256() {
            return sha256;
        }
    }
}
