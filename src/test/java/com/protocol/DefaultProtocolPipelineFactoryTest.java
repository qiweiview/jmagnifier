package com.protocol;

import com.model.EndpointConfig;
import com.model.Mapping;
import com.model.TlsConfig;
import com.protocol.raw.RawTcpBridge;
import org.junit.Assert;
import org.junit.Test;

public class DefaultProtocolPipelineFactoryTest {

    private final DefaultProtocolPipelineFactory factory = new DefaultProtocolPipelineFactory();

    @Test
    public void shouldCreateRawTcpBridgeForLegacyMapping() {
        Mapping mapping = Mapping.createDefaultMapping();
        mapping.setListenPort(9000);
        mapping.setForwardHost("127.0.0.1");
        mapping.setForwardPort(9001);
        mapping.applyDefaults();

        ProtocolBridge bridge = factory.createBridge(mapping, null, null);

        Assert.assertTrue(bridge instanceof RawTcpBridge);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldRejectHttpTlsBridgeBeforeImplementation() {
        Mapping mapping = Mapping.createDefaultMapping();
        EndpointConfig listen = new EndpointConfig();
        listen.setPort(9443);
        listen.setApplicationProtocol("http");
        TlsConfig listenTls = new TlsConfig();
        listenTls.setEnabled(true);
        listen.setTls(listenTls);
        mapping.setListen(listen);

        EndpointConfig forward = new EndpointConfig();
        forward.setHost("upstream.example.com");
        forward.setPort(443);
        forward.setApplicationProtocol("http");
        TlsConfig forwardTls = new TlsConfig();
        forwardTls.setEnabled(true);
        forward.setTls(forwardTls);
        mapping.setForward(forward);
        mapping.applyDefaults();

        factory.createBridge(mapping, null, null);
    }
}
