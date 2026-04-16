package com.store;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
}
