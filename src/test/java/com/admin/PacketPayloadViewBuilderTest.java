package com.admin;

import com.store.PacketRepository;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class PacketPayloadViewBuilderTest {

    @Test
    public void shouldBuildHttpJsonPayloadViewWithPrettyBody() {
        PacketRepository.PacketRecord record = new PacketRepository.PacketRecord();
        record.protocolFamily = "HTTP";
        record.applicationProtocol = "http1";
        record.contentType = "application/json; charset=utf-8";
        record.payload = ("POST /api/demo HTTP/1.1\r\n"
                + "Host: example.com\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n"
                + "{\"name\":\"demo\"}").getBytes(StandardCharsets.UTF_8);

        PacketPayloadViewBuilder.PacketPayloadView view = new PacketPayloadViewBuilder(4096).build(record);

        Assert.assertFalse(view.previewTruncated);
        Assert.assertTrue(view.textRaw.contains("\r\n\r\n"));
        Assert.assertEquals("POST /api/demo HTTP/1.1", view.http.startLine);
        Assert.assertEquals("Host: example.com\r\nContent-Type: application/json", view.http.headersText);
        Assert.assertEquals("{\"name\":\"demo\"}", view.http.bodyText);
        Assert.assertTrue(view.http.bodyDetected);
        Assert.assertTrue(view.http.bodyJson);
        Assert.assertFalse(view.http.bodyTruncated);
        Assert.assertNotNull(view.http.bodyJsonPretty);
        Assert.assertTrue(view.http.bodyJsonPretty.contains("\n"));
        Assert.assertTrue(view.http.bodyJsonPretty.contains("\"name\""));
    }

    @Test
    public void shouldDisablePrettyJsonWhenPreviewIsTruncated() {
        PacketRepository.PacketRecord record = new PacketRepository.PacketRecord();
        record.protocolFamily = "HTTP";
        record.applicationProtocol = "http1";
        record.contentType = "application/json";
        record.payload = ("POST /submit HTTP/1.1\r\n"
                + "Host: example.com\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n"
                + "{\"name\":\"demo\",\"message\":\"payload preview should be truncated\"}")
                .getBytes(StandardCharsets.UTF_8);

        PacketPayloadViewBuilder.PacketPayloadView view = new PacketPayloadViewBuilder(96).build(record);

        Assert.assertTrue(view.previewTruncated);
        Assert.assertNotNull(view.http);
        Assert.assertTrue(view.http.bodyDetected);
        Assert.assertTrue(view.http.bodyJson);
        Assert.assertTrue(view.http.bodyTruncated);
        Assert.assertNull(view.http.bodyJsonPretty);
    }

    @Test
    public void shouldDecodeGzipHttpBodyForDisplay() throws Exception {
        PacketRepository.PacketRecord record = new PacketRepository.PacketRecord();
        record.protocolFamily = "HTTP";
        record.applicationProtocol = "http1";
        record.contentType = "application/json;charset=UTF-8";
        byte[] compressed = gzip("{\"name\":\"demo\"}");
        byte[] head = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json;charset=UTF-8\r\n"
                + "Content-Encoding: gzip\r\n"
                + "Content-Length: " + compressed.length + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);
        record.payload = concat(head, compressed);

        PacketPayloadViewBuilder.PacketPayloadView view = new PacketPayloadViewBuilder(4096).build(record);

        Assert.assertNotNull(view.http);
        Assert.assertTrue(view.http.bodyDetected);
        Assert.assertEquals("{\"name\":\"demo\"}", view.http.bodyText);
        Assert.assertTrue(view.http.bodyJson);
        Assert.assertNotNull(view.http.bodyJsonPretty);
        Assert.assertTrue(view.http.bodyJsonPretty.contains("\"name\""));
    }

    @Test
    public void shouldKeepCompressedBodyWhenRecordIsTruncated() throws Exception {
        PacketRepository.PacketRecord record = new PacketRepository.PacketRecord();
        record.protocolFamily = "HTTP";
        record.applicationProtocol = "http1";
        record.contentType = "application/json;charset=UTF-8";
        record.truncated = true;
        byte[] compressed = gzip("{\"name\":\"demo\"}");
        byte[] head = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json;charset=UTF-8\r\n"
                + "Content-Encoding: gzip\r\n"
                + "Content-Length: " + compressed.length + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);
        record.payload = concat(head, compressed);

        PacketPayloadViewBuilder.PacketPayloadView view = new PacketPayloadViewBuilder(4096).build(record);

        Assert.assertNotNull(view.http);
        Assert.assertTrue(view.http.bodyDetected);
        Assert.assertTrue(view.http.bodyTruncated);
        Assert.assertNull(view.http.bodyJsonPretty);
        Assert.assertNotEquals("{\"name\":\"demo\"}", view.http.bodyText);
    }

    private byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream);
        try {
            gzipOutputStream.write(value.getBytes(StandardCharsets.UTF_8));
        } finally {
            gzipOutputStream.close();
        }
        return outputStream.toByteArray();
    }

    private byte[] concat(byte[] left, byte[] right) {
        byte[] joined = new byte[left.length + right.length];
        System.arraycopy(left, 0, joined, 0, left.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return joined;
    }
}
