package com.model;

import com.util.YmlParser;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class MappingCompatibilityTest {

    private final YmlParser ymlParser = new YmlParser();

    @Test
    public void shouldLoadLegacyRawTcpMapping() {
        String yaml = "mappings:\n"
                + "  - name: \"legacy\"\n"
                + "    enable: true\n"
                + "    listenPort: 9300\n"
                + "    forwardHost: \"example.com\"\n"
                + "    forwardPort: 80\n";

        GlobalConfig config = ymlParser.parseFile(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                GlobalConfig.class);

        Mapping mapping = config.getMappings().get(0);
        mapping.applyDefaults();

        Assert.assertEquals("legacy", mapping.getName());
        Assert.assertEquals(9300, mapping.getListenPort());
        Assert.assertEquals("example.com", mapping.getForwardHost());
        Assert.assertEquals(80, mapping.getForwardPort());
        Assert.assertEquals(Integer.valueOf(9300), mapping.getListen().getPort());
        Assert.assertEquals("tcp", mapping.getListen().getApplicationProtocol());
        Assert.assertEquals("tcp", mapping.getForward().getApplicationProtocol());
        Assert.assertFalse(Boolean.TRUE.equals(mapping.getListen().getTls().getEnabled()));
        Assert.assertTrue(mapping.isRawTcpPath());
        Assert.assertEquals("tcp", mapping.getListenMode());
        Assert.assertEquals("tcp", mapping.getForwardMode());
    }

    @Test
    public void shouldLoadNestedHttpTlsMapping() {
        String yaml = "mappings:\n"
                + "  - name: \"demo-http-to-https\"\n"
                + "    enable: false\n"
                + "    listen:\n"
                + "      port: 9300\n"
                + "      applicationProtocol: \"http\"\n"
                + "      tls:\n"
                + "        enabled: false\n"
                + "    forward:\n"
                + "      host: \"api.example.com\"\n"
                + "      port: 443\n"
                + "      applicationProtocol: \"http\"\n"
                + "      tls:\n"
                + "        enabled: true\n"
                + "        sniHost: \"api.example.com\"\n"
                + "    http:\n"
                + "      rewriteHost: true\n"
                + "      addXForwardedHeaders: true\n"
                + "      maxObjectSizeBytes: 2048\n";

        GlobalConfig config = ymlParser.parseFile(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                GlobalConfig.class);

        Mapping mapping = config.getMappings().get(0);
        mapping.applyDefaults();

        Assert.assertEquals(9300, mapping.getListenPort());
        Assert.assertEquals("api.example.com", mapping.getForwardHost());
        Assert.assertEquals(443, mapping.getForwardPort());
        Assert.assertEquals("http", mapping.getListen().getApplicationProtocol());
        Assert.assertEquals("http", mapping.getForward().getApplicationProtocol());
        Assert.assertTrue(Boolean.TRUE.equals(mapping.getForward().getTls().getEnabled()));
        Assert.assertEquals("api.example.com", mapping.getForward().getTls().getSniHost());
        Assert.assertTrue(Boolean.TRUE.equals(mapping.getHttp().getRewriteHost()));
        Assert.assertTrue(Boolean.TRUE.equals(mapping.getHttp().getAddXForwardedHeaders()));
        Assert.assertEquals(Integer.valueOf(2048), mapping.getHttp().getMaxObjectSizeBytes());
        Assert.assertFalse(mapping.isRawTcpPath());
        Assert.assertEquals("http", mapping.getListenMode());
        Assert.assertEquals("https", mapping.getForwardMode());
    }
}
