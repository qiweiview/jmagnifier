package com.protocol;

import com.capture.CaptureOptions;
import com.capture.PacketCaptureService;
import com.core.DataReceiver;
import com.model.EndpointConfig;
import com.model.GlobalConfig;
import com.model.HttpProxyConfig;
import com.model.Mapping;
import com.model.TlsConfig;
import com.store.ConnectionRepository;
import com.store.DatabaseInitializer;
import com.store.PageResult;
import com.store.PacketRepository;
import com.store.SqliteDatabase;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.Assert;
import org.junit.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class HttpProtocolIntegrationTest {

    @Test
    public void shouldProxyHttpToHttpAndCaptureHttpMetadata() throws Exception {
        TestHttpUpstream upstream = TestHttpUpstream.start(false, "plain-ok");
        PacketCaptureFixture captureFixture = PacketCaptureFixture.start();
        ProxyFixture proxyFixture = null;
        try {
            Mapping mapping = httpMapping(false, false, upstream.getPort(), null, null);
            proxyFixture = ProxyFixture.start(mapping, captureFixture.connectionRepository, captureFixture.packetCaptureService);

            ClientResponse response = sendRequest(false, proxyFixture.getBoundPort(), "/hello", "legacy.local");

            Assert.assertEquals(200, response.statusCode);
            Assert.assertEquals("plain-ok", response.body);
            Assert.assertTrue(upstream.awaitRequest());
            Assert.assertEquals("GET", upstream.getLastRequest().method);
            Assert.assertEquals("/hello", upstream.getLastRequest().uri);
            Assert.assertEquals("127.0.0.1:" + upstream.getPort(), upstream.getLastRequest().hostHeader);
            Assert.assertEquals("http", upstream.getLastRequest().forwardedProto);

            captureFixture.packetCaptureService.shutdown();
            ConnectionRepository.ConnectionQuery connectionQuery = new ConnectionRepository.ConnectionQuery();
            connectionQuery.mappingId = 1L;
            PageResult<ConnectionRepository.ConnectionRecord> connectionPage = captureFixture.connectionRepository.query(connectionQuery);
            Assert.assertEquals(1, connectionPage.getItems().size());
            long connectionId = connectionPage.getItems().get(0).id;
            PacketRepository.PacketRecord requestPacket = captureFixture.packetRepository.findRecentByConnectionId(connectionId, 10).stream()
                    .filter(packet -> "REQUEST".equals(packet.direction))
                    .findFirst()
                    .orElse(null);
            PacketRepository.PacketRecord responsePacket = captureFixture.packetRepository.findRecentByConnectionId(connectionId, 10).stream()
                    .filter(packet -> "RESPONSE".equals(packet.direction))
                    .findFirst()
                    .orElse(null);
            Assert.assertNotNull(requestPacket);
            Assert.assertNotNull(responsePacket);
            Assert.assertEquals("HTTP", requestPacket.protocolFamily);
            Assert.assertEquals("http1", requestPacket.applicationProtocol);
            Assert.assertEquals("GET", requestPacket.httpMethod);
            Assert.assertEquals("/hello", requestPacket.httpUri);
            Assert.assertEquals(Integer.valueOf(200), responsePacket.httpStatus);
        } finally {
            if (proxyFixture != null) {
                proxyFixture.close();
            }
            captureFixture.close();
            upstream.close();
        }
    }

    @Test
    public void shouldProxyHttpToHttps() throws Exception {
        TestHttpUpstream upstream = TestHttpUpstream.start(true, "tls-upstream");
        DataReceiver receiver = null;
        try {
            Mapping mapping = httpMapping(false, true, upstream.getPort(), null, null);
            receiver = new DataReceiver(mapping);
            receiver.start();

            ClientResponse response = sendRequest(false, receiver.getBoundPort(), "/secure", "client.example");

            Assert.assertEquals(200, response.statusCode);
            Assert.assertEquals("tls-upstream", response.body);
            Assert.assertTrue(upstream.awaitRequest());
            Assert.assertEquals("127.0.0.1:" + upstream.getPort(), upstream.getLastRequest().hostHeader);
        } finally {
            if (receiver != null) {
                receiver.release();
            }
            upstream.close();
        }
    }

    @Test
    public void shouldProxyHttpsToHttp() throws Exception {
        SelfSignedCertificate listenCert = new SelfSignedCertificate("localhost");
        TestHttpUpstream upstream = TestHttpUpstream.start(false, "https-local");
        DataReceiver receiver = null;
        try {
            Mapping mapping = httpMapping(true, false, upstream.getPort(), listenCert, null);
            receiver = new DataReceiver(mapping);
            receiver.start();

            ClientResponse response = sendRequest(true, receiver.getBoundPort(), "/inbound-secure", "client.example");

            Assert.assertEquals(200, response.statusCode);
            Assert.assertEquals("https-local", response.body);
            Assert.assertTrue(upstream.awaitRequest());
            Assert.assertEquals("https", upstream.getLastRequest().forwardedProto);
        } finally {
            if (receiver != null) {
                receiver.release();
            }
            upstream.close();
            listenCert.delete();
        }
    }

    @Test
    public void shouldProxyHttpsToHttps() throws Exception {
        SelfSignedCertificate listenCert = new SelfSignedCertificate("localhost");
        TestHttpUpstream upstream = TestHttpUpstream.start(true, "double-tls");
        DataReceiver receiver = null;
        try {
            Mapping mapping = httpMapping(true, true, upstream.getPort(), listenCert, "upstream.example.test");
            receiver = new DataReceiver(mapping);
            receiver.start();

            ClientResponse response = sendRequest(true, receiver.getBoundPort(), "/double", "client.example");

            Assert.assertEquals(200, response.statusCode);
            Assert.assertEquals("double-tls", response.body);
            Assert.assertTrue(upstream.awaitRequest());
            Assert.assertEquals("127.0.0.1:" + upstream.getPort(), upstream.getLastRequest().hostHeader);
        } finally {
            if (receiver != null) {
                receiver.release();
            }
            upstream.close();
            listenCert.delete();
        }
    }

    private static Mapping httpMapping(boolean listenTls, boolean forwardTls, int upstreamPort,
                                       SelfSignedCertificate listenCert, String sniHost) {
        Mapping mapping = Mapping.createDefaultMapping();
        mapping.setName("http-mapping");
        mapping.setEnable(true);

        EndpointConfig listen = new EndpointConfig();
        listen.setPort(0);
        listen.setApplicationProtocol("http");
        TlsConfig listenTlsConfig = new TlsConfig();
        listenTlsConfig.setEnabled(listenTls);
        if (listenTls && listenCert != null) {
            listenTlsConfig.setCertificateFile(listenCert.certificate().getAbsolutePath());
            listenTlsConfig.setPrivateKeyFile(listenCert.privateKey().getAbsolutePath());
        }
        listen.setTls(listenTlsConfig);
        mapping.setListen(listen);

        EndpointConfig forward = new EndpointConfig();
        forward.setHost("127.0.0.1");
        forward.setPort(upstreamPort);
        forward.setApplicationProtocol("http");
        TlsConfig forwardTlsConfig = new TlsConfig();
        forwardTlsConfig.setEnabled(forwardTls);
        forwardTlsConfig.setInsecureSkipVerify(forwardTls);
        forwardTlsConfig.setSniHost(sniHost);
        forward.setTls(forwardTlsConfig);
        mapping.setForward(forward);

        HttpProxyConfig http = new HttpProxyConfig();
        http.setRewriteHost(true);
        http.setAddXForwardedHeaders(true);
        http.setMaxObjectSizeBytes(1024 * 1024);
        mapping.setHttp(http);
        mapping.applyDefaults();
        return mapping;
    }

    private static ClientResponse sendRequest(boolean https, int port, String path, String hostHeader) throws Exception {
        URL url = new URL((https ? "https" : "http") + "://127.0.0.1:" + port + path);
        HttpURLConnection connection = https
                ? (HttpsURLConnection) url.openConnection()
                : (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Host", hostHeader);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        if (connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(trustAllSslContext().getSocketFactory());
            ((HttpsURLConnection) connection).setHostnameVerifier(trustAllHostnameVerifier());
        }
        int statusCode = connection.getResponseCode();
        InputStream inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = inputStream == null ? "" : readFully(inputStream);
        connection.disconnect();
        return new ClientResponse(statusCode, body);
    }

    private static SSLContext trustAllSslContext() throws Exception {
        TrustManager[] trustManagers = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new SecureRandom());
        return sslContext;
    }

    private static HostnameVerifier trustAllHostnameVerifier() {
        return (hostname, session) -> true;
    }

    private static String readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static class ClientResponse {

        private final int statusCode;

        private final String body;

        private ClientResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private static class PacketCaptureFixture {

        private final Path sqliteFile;

        private final PacketRepository packetRepository;

        private final ConnectionRepository connectionRepository;

        private final PacketCaptureService packetCaptureService;

        private PacketCaptureFixture(Path sqliteFile, PacketRepository packetRepository, ConnectionRepository connectionRepository,
                                     PacketCaptureService packetCaptureService) {
            this.sqliteFile = sqliteFile;
            this.packetRepository = packetRepository;
            this.connectionRepository = connectionRepository;
            this.packetCaptureService = packetCaptureService;
        }

        private static PacketCaptureFixture start() throws Exception {
            Path sqliteFile = Files.createTempFile("jmagnifier-http-packets", ".db");
            SqliteDatabase sqliteDatabase = new SqliteDatabase(sqliteFile.toString());
            new DatabaseInitializer(sqliteDatabase).initialize();
            PacketRepository packetRepository = new PacketRepository(sqliteDatabase);
            ConnectionRepository connectionRepository = new ConnectionRepository(sqliteDatabase);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.getCapture().setEnabled(true);
            globalConfig.getCapture().setMaxCaptureBytes(1024 * 1024);
            globalConfig.getCapture().setQueueCapacity(128);
            globalConfig.getCapture().setBatchSize(16);
            globalConfig.getCapture().setFlushIntervalMillis(50);
            PacketCaptureService packetCaptureService = new PacketCaptureService(new CaptureOptions(globalConfig.getCapture()), packetRepository);
            packetCaptureService.start();
            return new PacketCaptureFixture(sqliteFile, packetRepository, connectionRepository, packetCaptureService);
        }

        private void close() throws Exception {
            packetCaptureService.shutdown();
            Files.deleteIfExists(sqliteFile);
        }
    }

    private static class TestHttpUpstream {

        private final EventLoopGroup bossGroup;

        private final EventLoopGroup workerGroup;

        private final Channel serverChannel;

        private final CountDownLatch requestLatch;

        private final AtomicReference<ReceivedRequest> lastRequest;

        private final SelfSignedCertificate certificate;

        private TestHttpUpstream(EventLoopGroup bossGroup, EventLoopGroup workerGroup, Channel serverChannel,
                                 CountDownLatch requestLatch, AtomicReference<ReceivedRequest> lastRequest,
                                 SelfSignedCertificate certificate) {
            this.bossGroup = bossGroup;
            this.workerGroup = workerGroup;
            this.serverChannel = serverChannel;
            this.requestLatch = requestLatch;
            this.lastRequest = lastRequest;
            this.certificate = certificate;
        }

        private static TestHttpUpstream start(boolean tls, final String body) throws Exception {
            final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            final EventLoopGroup workerGroup = new NioEventLoopGroup(1);
            final CountDownLatch requestLatch = new CountDownLatch(1);
            final AtomicReference<ReceivedRequest> lastRequest = new AtomicReference<>();
            final SelfSignedCertificate certificate = tls ? new SelfSignedCertificate("localhost") : null;
            final SslContext sslContext = tls
                    ? SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build()
                    : null;
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            if (sslContext != null) {
                                ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                            }
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, FullHttpRequest msg) {
                                    lastRequest.set(new ReceivedRequest(
                                            msg.method().name(),
                                            msg.uri(),
                                            msg.headers().get(HttpHeaderNames.HOST),
                                            msg.headers().get("X-Forwarded-Proto")));
                                    FullHttpResponse response = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1,
                                            HttpResponseStatus.OK,
                                            Unpooled.copiedBuffer(body.getBytes(StandardCharsets.UTF_8)));
                                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                                    HttpUtil.setKeepAlive(response, true);
                                    ctx.writeAndFlush(response);
                                    requestLatch.countDown();
                                }
                            });
                        }
                    });
            Channel channel = bootstrap.bind(0).sync().channel();
            return new TestHttpUpstream(bossGroup, workerGroup, channel, requestLatch, lastRequest, certificate);
        }

        private int getPort() {
            return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        private boolean awaitRequest() throws Exception {
            return requestLatch.await(5, TimeUnit.SECONDS);
        }

        private ReceivedRequest getLastRequest() {
            return lastRequest.get();
        }

        private void close() throws Exception {
            serverChannel.close().syncUninterruptibly();
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            if (certificate != null) {
                certificate.delete();
            }
        }
    }

    private static class ReceivedRequest {

        private final String method;

        private final String uri;

        private final String hostHeader;

        private final String forwardedProto;

        private ReceivedRequest(String method, String uri, String hostHeader, String forwardedProto) {
            this.method = method;
            this.uri = uri;
            this.hostHeader = hostHeader;
            this.forwardedProto = forwardedProto;
        }
    }

    private static class ProxyFixture {

        private final EventLoopGroup bossGroup;

        private final EventLoopGroup workerGroup;

        private final EventLoopGroup clientGroup;

        private final DataReceiver dataReceiver;

        private ProxyFixture(EventLoopGroup bossGroup, EventLoopGroup workerGroup, EventLoopGroup clientGroup, DataReceiver dataReceiver) {
            this.bossGroup = bossGroup;
            this.workerGroup = workerGroup;
            this.clientGroup = clientGroup;
            this.dataReceiver = dataReceiver;
        }

        private static ProxyFixture start(Mapping mapping, ConnectionRepository connectionRepository,
                                          PacketCaptureService packetCaptureService) {
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup(1);
            EventLoopGroup clientGroup = new NioEventLoopGroup(1);
            DataReceiver dataReceiver = new DataReceiver(1L, mapping, bossGroup, workerGroup, clientGroup,
                    connectionRepository, packetCaptureService, new DefaultProtocolPipelineFactory());
            dataReceiver.start();
            return new ProxyFixture(bossGroup, workerGroup, clientGroup, dataReceiver);
        }

        private int getBoundPort() {
            return dataReceiver.getBoundPort();
        }

        private void close() {
            dataReceiver.stop();
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            clientGroup.shutdownGracefully().syncUninterruptibly();
        }
    }
}
