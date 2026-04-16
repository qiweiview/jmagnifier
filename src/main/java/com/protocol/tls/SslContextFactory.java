package com.protocol.tls;

import com.model.EndpointConfig;
import com.model.Mapping;
import com.model.TlsConfig;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import java.io.File;

public class SslContextFactory {

    public SslContext createServerContext(Mapping mapping) {
        EndpointConfig listen = mapping.getListen();
        TlsConfig tls = listen == null ? null : listen.getTls();
        if (tls == null || !Boolean.TRUE.equals(tls.getEnabled())) {
            return null;
        }
        if (isBlank(tls.getCertificateFile()) || isBlank(tls.getPrivateKeyFile())) {
            throw new IllegalArgumentException("listen TLS requires certificateFile and privateKeyFile");
        }
        try {
            SslContextBuilder builder = SslContextBuilder.forServer(
                    new File(tls.getCertificateFile()),
                    new File(tls.getPrivateKeyFile()),
                    tls.getPrivateKeyPassword());
            builder.clientAuth(ClientAuth.NONE);
            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("create server ssl context failed", e);
        }
    }

    public SslContext createClientContext(Mapping mapping) {
        EndpointConfig forward = mapping.getForward();
        TlsConfig tls = forward == null ? null : forward.getTls();
        if (tls == null || !Boolean.TRUE.equals(tls.getEnabled())) {
            return null;
        }
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();
            if (Boolean.TRUE.equals(tls.getInsecureSkipVerify())) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            } else if (!isBlank(tls.getTrustCertCollectionFile())) {
                builder.trustManager(new File(tls.getTrustCertCollectionFile()));
            }
            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("create client ssl context failed", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }
}
