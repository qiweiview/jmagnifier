package com.admin;

import com.store.PacketRepository;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

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
}
