package com.store;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

public class PayloadFileStoreTest {

    @Test
    public void shouldWriteAndReadPayloadFrame() throws Exception {
        File dir = Files.createTempDirectory("payload-store-test").toFile();
        try {
            PayloadFileStore store = new PayloadFileStore(dir.getAbsolutePath(), 1024 * 1024);
            byte[] payload = "hello-payload".getBytes(StandardCharsets.UTF_8);

            PayloadFileStore.PayloadWriteResult result = store.write(12L, "2026-04-16T10:00:00Z", payload, true);

            Assert.assertTrue(result.getRelativePath().contains("2026-04-16/mapping-12/packet-000001.seg"));
            Assert.assertArrayEquals(payload, store.read(result.getRelativePath(), result.getOffset(), result.getLength()));
            Assert.assertTrue(store.exists(result.getRelativePath()));
            Assert.assertTrue(result.isComplete());
            Assert.assertNotNull(result.getSha256());
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }

    @Test
    public void shouldRollbackAppendedFrames() throws Exception {
        File dir = Files.createTempDirectory("payload-store-rollback-test").toFile();
        try {
            PayloadFileStore store = new PayloadFileStore(dir.getAbsolutePath(), 1024 * 1024);

            PayloadFileStore.PayloadWriteResult first = store.write(1L, "2026-04-16T10:00:00Z",
                    "first".getBytes(StandardCharsets.UTF_8), true);
            PayloadFileStore.PayloadWriteResult second = store.write(1L, "2026-04-16T10:00:01Z",
                    "second".getBytes(StandardCharsets.UTF_8), true);

            store.rollback(Arrays.asList(second));

            Assert.assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                    store.read(first.getRelativePath(), first.getOffset(), first.getLength()));
            try {
                store.read(second.getRelativePath(), second.getOffset(), second.getLength());
                Assert.fail("expected second frame to be rolled back");
            } catch (RuntimeException expected) {
                Assert.assertTrue(expected.getMessage().contains("payload"));
            }
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }

    @Test
    public void shouldDeleteExpiredSegmentsDuringRetention() throws Exception {
        File dir = Files.createTempDirectory("payload-store-retention-test").toFile();
        try {
            PayloadFileStore store = new PayloadFileStore(dir.getAbsolutePath(), 20);
            PayloadFileStore.PayloadWriteResult expired = store.write(1L, "2026-04-01T10:00:00Z",
                    "expired".getBytes(StandardCharsets.UTF_8), true);
            PayloadFileStore.PayloadWriteResult fresh = store.write(1L, "2026-04-16T10:00:00Z",
                    "fresh".getBytes(StandardCharsets.UTF_8), true);

            File expiredFile = store.resolve(expired.getRelativePath());
            Assert.assertTrue(expiredFile.setLastModified(Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli()));

            PayloadFileStore.RetentionResult result = store.enforceRetention(7, 0);

            Assert.assertEquals(1, result.getDeletedFiles());
            Assert.assertTrue(result.getDeletedRelativePaths().contains(expired.getRelativePath()));
            Assert.assertFalse(store.exists(expired.getRelativePath()));
            Assert.assertTrue(store.exists(fresh.getRelativePath()));
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }

    @Test
    public void shouldDeleteOnlySegmentsBeforeKeepDate() throws Exception {
        File dir = Files.createTempDirectory("payload-store-purge-date-test").toFile();
        try {
            PayloadFileStore store = new PayloadFileStore(dir.getAbsolutePath(), 1024 * 1024);
            PayloadFileStore.PayloadWriteResult older = store.write(1L, "2026-04-15T08:00:00Z",
                    "older".getBytes(StandardCharsets.UTF_8), true);
            PayloadFileStore.PayloadWriteResult kept = store.write(1L, "2026-04-17T08:00:00Z",
                    "kept".getBytes(StandardCharsets.UTF_8), true);

            PayloadFileStore.DeleteResult result = store.deleteSegmentsBeforeDate("2026-04-17");

            Assert.assertEquals(1, result.getDeletedFiles());
            Assert.assertTrue(result.getDeletedRelativePaths().contains(older.getRelativePath()));
            Assert.assertFalse(store.exists(older.getRelativePath()));
            Assert.assertTrue(store.exists(kept.getRelativePath()));
            Assert.assertFalse(new File(dir, "2026-04-15").exists());
            Assert.assertTrue(new File(dir, "2026-04-17").exists());
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }
}
