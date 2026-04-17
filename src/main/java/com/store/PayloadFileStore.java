package com.store;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class PayloadFileStore {

    private static final byte[] MAGIC = new byte[]{'J', 'M', 'P', 'L'};

    private static final byte VERSION = 1;

    public static final int FRAME_HEADER_BYTES = 17;

    private static final Pattern DATE_DIR_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final File payloadDir;

    private final long payloadSegmentBytes;

    public PayloadFileStore(String payloadDir, long payloadSegmentBytes) {
        this.payloadDir = new File(payloadDir == null || payloadDir.trim().length() == 0 ? "./data/payload" : payloadDir);
        this.payloadSegmentBytes = payloadSegmentBytes <= 0 ? 128L * 1024L * 1024L : payloadSegmentBytes;
    }

    public synchronized PayloadWriteResult write(long mappingId, String receivedAt, byte[] payload, boolean payloadComplete) {
        byte[] data = payload == null ? new byte[0] : payload;
        int frameBytes = FRAME_HEADER_BYTES + data.length;
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

    public synchronized void rollback(List<PayloadWriteResult> writes) {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        List<PayloadWriteResult> reversed = new ArrayList<>(writes);
        Collections.reverse(reversed);
        for (PayloadWriteResult write : reversed) {
            if (write == null || write.getRelativePath() == null) {
                continue;
            }
            File file = resolve(write.getRelativePath());
            if (!file.exists()) {
                continue;
            }
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
                if (randomAccessFile.length() < write.getOffset()) {
                    continue;
                }
                randomAccessFile.setLength(write.getOffset());
            } catch (IOException e) {
                throw new RuntimeException("rollback payload segment failed", e);
            }
            if (file.length() == 0 && !file.delete()) {
                throw new RuntimeException("delete empty payload segment failed:" + file.getAbsolutePath());
            }
            cleanupEmptyParents(file.getParentFile());
        }
    }

    public byte[] read(String relativePath, long offset, long expectedLength) {
        PayloadReadHandle handle = openReadHandle(relativePath, offset, expectedLength);
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(handle.getFile(), "r")) {
            randomAccessFile.seek(handle.getPayloadOffset());
            byte[] payload = new byte[handle.getLength()];
            randomAccessFile.readFully(payload);
            return payload;
        } catch (IOException e) {
            throw new RuntimeException("read payload file failed", e);
        }
    }

    public PayloadReadHandle openReadHandle(String relativePath, long offset, long expectedLength) {
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
            int flags = randomAccessFile.readInt();
            if (actualLength < 0 || actualLength > Integer.MAX_VALUE) {
                throw new IOException("invalid payload frame length:" + actualLength);
            }
            if (expectedLength >= 0 && actualLength != expectedLength) {
                throw new IOException("payload frame length mismatch expected:" + expectedLength + " actual:" + actualLength);
            }
            return new PayloadReadHandle(file, offset + FRAME_HEADER_BYTES, (int) actualLength, (flags & 1) == 1);
        } catch (IOException e) {
            throw new RuntimeException("open payload file failed", e);
        }
    }

    public synchronized RetentionResult enforceRetention(int retentionDays, long retentionBytes) {
        List<File> files = listSegmentFiles();
        if (files.isEmpty()) {
            return new RetentionResult(0, 0, Collections.<String>emptyList());
        }
        long cutoffMillis = retentionDays > 0
                ? Instant.now().minusSeconds(retentionDays * 24L * 60L * 60L).toEpochMilli()
                : Long.MIN_VALUE;
        Set<String> deletedPaths = new LinkedHashSet<>();
        long deletedBytes = 0;
        for (File file : files) {
            if (retentionDays > 0 && file.lastModified() < cutoffMillis) {
                deletedBytes += deleteSegmentFile(file, deletedPaths);
            }
        }
        files = listSegmentFiles();
        if (retentionBytes > 0) {
            long totalBytes = totalBytes(files);
            if (totalBytes > retentionBytes) {
                files.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getAbsolutePath));
                for (File file : files) {
                    if (totalBytes <= retentionBytes) {
                        break;
                    }
                    long bytes = deleteSegmentFile(file, deletedPaths);
                    if (bytes > 0) {
                        totalBytes -= bytes;
                        deletedBytes += bytes;
                    }
                }
            }
        }
        return new RetentionResult(deletedPaths.size(), deletedBytes, new ArrayList<>(deletedPaths));
    }

    public synchronized DeleteResult deleteAllSegments() {
        return deleteSegmentFiles(listSegmentFiles());
    }

    public synchronized DeleteResult deleteSegmentsBeforeDate(String keepDateExclusive) {
        if (keepDateExclusive == null || keepDateExclusive.trim().length() == 0) {
            throw new IllegalArgumentException("keepDateExclusive is required");
        }
        List<File> files = new ArrayList<>();
        collectSegmentFilesBeforeDate(keepDateExclusive, files);
        return deleteSegmentFiles(files);
    }

    public int countSegmentFiles() {
        return listSegmentFiles().size();
    }

    public long totalPayloadBytes() {
        return totalBytes(listSegmentFiles());
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

    private List<File> listSegmentFiles() {
        List<File> files = new ArrayList<>();
        collectSegmentFiles(payloadDir, files);
        return files;
    }

    private void collectSegmentFilesBeforeDate(String keepDateExclusive, List<File> files) {
        if (payloadDir == null || !payloadDir.exists()) {
            return;
        }
        File[] children = payloadDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory() || !DATE_DIR_PATTERN.matcher(child.getName()).matches()) {
                continue;
            }
            if (child.getName().compareTo(keepDateExclusive) < 0) {
                collectSegmentFiles(child, files);
            }
        }
    }

    private void collectSegmentFiles(File directory, List<File> files) {
        if (directory == null || !directory.exists()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectSegmentFiles(child, files);
            } else if (child.getName().endsWith(".seg")) {
                files.add(child);
            }
        }
    }

    private long totalBytes(List<File> files) {
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        return total;
    }

    private DeleteResult deleteSegmentFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return DeleteResult.empty();
        }
        Set<String> deletedPaths = new LinkedHashSet<>();
        long deletedBytes = 0;
        for (File file : files) {
            deletedBytes += deleteSegmentFile(file, deletedPaths);
        }
        return new DeleteResult(deletedPaths.size(), deletedBytes, new ArrayList<>(deletedPaths));
    }

    private long deleteSegmentFile(File file, Set<String> deletedPaths) {
        if (file == null || !file.exists()) {
            return 0;
        }
        long bytes = file.length();
        String relativePath = relativePath(file);
        if (!file.delete()) {
            throw new RuntimeException("delete payload segment failed:" + file.getAbsolutePath());
        }
        deletedPaths.add(relativePath);
        cleanupEmptyParents(file.getParentFile());
        return bytes;
    }

    private void cleanupEmptyParents(File directory) {
        File base = payloadDir.getAbsoluteFile();
        File current = directory;
        while (current != null && !base.equals(current.getAbsoluteFile())) {
            String[] children = current.list();
            if (children != null && children.length == 0) {
                if (!current.delete()) {
                    return;
                }
                current = current.getParentFile();
            } else {
                return;
            }
        }
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

    public static class PayloadReadHandle {

        private final File file;

        private final long payloadOffset;

        private final int length;

        private final boolean complete;

        public PayloadReadHandle(File file, long payloadOffset, int length, boolean complete) {
            this.file = file;
            this.payloadOffset = payloadOffset;
            this.length = length;
            this.complete = complete;
        }

        public File getFile() {
            return file;
        }

        public long getPayloadOffset() {
            return payloadOffset;
        }

        public int getLength() {
            return length;
        }

        public boolean isComplete() {
            return complete;
        }
    }

    public static class RetentionResult {

        private final int deletedFiles;

        private final long deletedBytes;

        private final List<String> deletedRelativePaths;

        public RetentionResult(int deletedFiles, long deletedBytes, List<String> deletedRelativePaths) {
            this.deletedFiles = deletedFiles;
            this.deletedBytes = deletedBytes;
            this.deletedRelativePaths = deletedRelativePaths;
        }

        public int getDeletedFiles() {
            return deletedFiles;
        }

        public long getDeletedBytes() {
            return deletedBytes;
        }

        public List<String> getDeletedRelativePaths() {
            return deletedRelativePaths;
        }
    }

    public static class DeleteResult {

        private final int deletedFiles;

        private final long deletedBytes;

        private final List<String> deletedRelativePaths;

        public DeleteResult(int deletedFiles, long deletedBytes, List<String> deletedRelativePaths) {
            this.deletedFiles = deletedFiles;
            this.deletedBytes = deletedBytes;
            this.deletedRelativePaths = deletedRelativePaths;
        }

        public static DeleteResult empty() {
            return new DeleteResult(0, 0, Collections.<String>emptyList());
        }

        public int getDeletedFiles() {
            return deletedFiles;
        }

        public long getDeletedBytes() {
            return deletedBytes;
        }

        public List<String> getDeletedRelativePaths() {
            return deletedRelativePaths;
        }
    }
}
