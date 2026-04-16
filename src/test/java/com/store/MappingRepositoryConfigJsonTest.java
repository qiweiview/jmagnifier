package com.store;

import com.model.EndpointConfig;
import com.model.HttpProxyConfig;
import com.model.Mapping;
import com.model.TlsConfig;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MappingRepositoryConfigJsonTest {

    @Test
    public void shouldPersistAndRestoreExtendedMappingConfig() throws Exception {
        Path tempFile = Files.createTempFile("jmagnifier-mapping", ".db");
        SqliteDatabase sqliteDatabase = new SqliteDatabase(tempFile.toString());
        new DatabaseInitializer(sqliteDatabase).initialize();
        MappingRepository repository = new MappingRepository(sqliteDatabase);

        Mapping mapping = Mapping.createDefaultMapping();
        mapping.setName("http-to-https");
        mapping.setEnable(false);

        EndpointConfig listen = new EndpointConfig();
        listen.setPort(9300);
        listen.setApplicationProtocol("http");
        listen.setTls(new TlsConfig());
        mapping.setListen(listen);

        EndpointConfig forward = new EndpointConfig();
        forward.setHost("api.example.com");
        forward.setPort(443);
        forward.setApplicationProtocol("http");
        TlsConfig tlsConfig = new TlsConfig();
        tlsConfig.setEnabled(true);
        tlsConfig.setSniHost("api.example.com");
        forward.setTls(tlsConfig);
        mapping.setForward(forward);

        HttpProxyConfig httpProxyConfig = new HttpProxyConfig();
        httpProxyConfig.setRewriteHost(false);
        httpProxyConfig.setAddXForwardedHeaders(true);
        httpProxyConfig.setMaxObjectSizeBytes(4096);
        mapping.setHttp(httpProxyConfig);
        mapping.applyDefaults();

        long id = repository.insert(mapping);
        mapping.getHttp().setMaxObjectSizeBytes(8192);
        repository.update(id, mapping);

        List<MappingEntity> entities = repository.findAllActive();
        Assert.assertEquals(1, entities.size());
        Assert.assertNotNull(entities.get(0).getConfigJson());

        Mapping restored = entities.get(0).toMapping();
        restored.applyDefaults();

        Assert.assertEquals("http-to-https", restored.getName());
        Assert.assertEquals("http", restored.getListen().getApplicationProtocol());
        Assert.assertEquals("http", restored.getForward().getApplicationProtocol());
        Assert.assertEquals("api.example.com", restored.getForward().getHost());
        Assert.assertTrue(Boolean.TRUE.equals(restored.getForward().getTls().getEnabled()));
        Assert.assertEquals("api.example.com", restored.getForward().getTls().getSniHost());
        Assert.assertEquals(Integer.valueOf(8192), restored.getHttp().getMaxObjectSizeBytes());
    }
}
