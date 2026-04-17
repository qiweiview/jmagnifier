package com.store;

import com.capture.PacketDirection;
import com.capture.PacketEvent;
import com.capture.PayloadStoreType;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class PacketRepositoryPurgeTest {

    @Test
    public void shouldPurgePacketsBeforeKeepDateAndDeleteOldSegments() throws Exception {
        File dir = Files.createTempDirectory("packet-repository-purge-date-test").toFile();
        try {
            SqliteDatabase sqliteDatabase = new SqliteDatabase(new File(dir, "jmagnifier.db").getAbsolutePath());
            new DatabaseInitializer(sqliteDatabase).initialize();
            PayloadFileStore payloadFileStore = new PayloadFileStore(new File(dir, "payload").getAbsolutePath(), 1024 * 1024);
            PacketRepository repository = new PacketRepository(sqliteDatabase, payloadFileStore);

            PacketEvent older = packetEvent(1L, 10L, 1L, "2026-04-15T08:00:00Z", "older-payload");
            PacketEvent kept = packetEvent(1L, 10L, 2L, "2026-04-17T08:00:00Z", "kept-payload");
            repository.insertBatch(Arrays.asList(older, kept));

            PacketRepository.PurgeResult result = repository.purgeBeforeDate("2026-04-17");

            Assert.assertEquals(1L, result.getDeletedPackets());
            Assert.assertEquals(1, result.getDeletedPayloadFiles());
            Assert.assertTrue(result.getDeletedPayloadBytes() > 0);
            Assert.assertFalse(payloadFileStore.exists(older.getPayloadFilePath()));
            Assert.assertTrue(payloadFileStore.exists(kept.getPayloadFilePath()));

            PacketRepository.PacketQuery query = new PacketRepository.PacketQuery();
            Assert.assertEquals(1L, repository.query(query).getTotal());
            PacketRepository.PacketRecord remaining = repository.query(query).getItems().get(0);
            Assert.assertEquals("2026-04-17T08:00:00Z", remaining.receivedAt);
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }

    @Test
    public void shouldPurgeAllPacketsAndDeleteAllSegments() throws Exception {
        File dir = Files.createTempDirectory("packet-repository-purge-all-test").toFile();
        try {
            SqliteDatabase sqliteDatabase = new SqliteDatabase(new File(dir, "jmagnifier.db").getAbsolutePath());
            new DatabaseInitializer(sqliteDatabase).initialize();
            PayloadFileStore payloadFileStore = new PayloadFileStore(new File(dir, "payload").getAbsolutePath(), 1024 * 1024);
            PacketRepository repository = new PacketRepository(sqliteDatabase, payloadFileStore);

            PacketEvent first = packetEvent(2L, 20L, 1L, "2026-04-16T08:00:00Z", "first-payload");
            PacketEvent second = packetEvent(2L, 20L, 2L, "2026-04-17T08:00:00Z", "second-payload");
            repository.insertBatch(Arrays.asList(first, second));

            PacketRepository.PurgeResult result = repository.purgeAll();

            Assert.assertEquals(2L, result.getDeletedPackets());
            Assert.assertEquals(2, result.getDeletedPayloadFiles());
            Assert.assertFalse(payloadFileStore.exists(first.getPayloadFilePath()));
            Assert.assertFalse(payloadFileStore.exists(second.getPayloadFilePath()));
            Assert.assertEquals(0L, repository.query(new PacketRepository.PacketQuery()).getTotal());
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }

    private PacketEvent packetEvent(long mappingId, long connectionId, long sequenceNo, String receivedAt, String payloadText) {
        byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
        PacketEvent event = new PacketEvent();
        event.setMappingId(mappingId);
        event.setConnectionId(connectionId);
        event.setDirection(PacketDirection.REQUEST);
        event.setSequenceNo(sequenceNo);
        event.setClientIp("127.0.0.1");
        event.setClientPort(9000);
        event.setListenIp("0.0.0.0");
        event.setListenPort(9300);
        event.setTargetHost("example.com");
        event.setTargetPort(80);
        event.setRemoteIp("93.184.216.34");
        event.setRemotePort(80);
        event.setPayload(payload);
        event.setPayloadPreview(payload);
        event.setPayloadSize(payload.length);
        event.setCapturedSize(payload.length);
        event.setPayloadPreviewSize(payload.length);
        event.setPayloadComplete(true);
        event.setTruncated(false);
        event.setProtocolFamily("TCP");
        event.setApplicationProtocol("tcp");
        event.setPayloadStoreType(PayloadStoreType.FILE.name());
        event.setReceivedAt(receivedAt);
        return event;
    }
}
