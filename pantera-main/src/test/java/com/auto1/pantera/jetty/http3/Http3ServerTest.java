/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.jetty.http3;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Splitting;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.nuget.RandomFreePort;
import io.reactivex.Flowable;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test for {@link Http3Server}.
 * Disabled: Requires native QUIC (quiche) libraries not available on all platforms.
 */
@Disabled("Requires native QUIC libraries - ExceptionInInitializerError: no quiche binding implementation found")
class Http3ServerTest {

    /**
     * Test header name with request method.
     */
    private static final String RQ_METHOD = "rq_method";

    /**
     * Some test small data chunk.
     */
    private static final byte[] SMALL_DATA = "abc123".getBytes();

    /**
     * Test data size.
     */
    private static final int SIZE = 1024 * 1024;

    private Http3Server server;

    private HTTP3Client client;

    private int port;

    private Session.Client session;

    @BeforeEach
    void init() throws Exception {
        this.port = new RandomFreePort().value();
        final SslContextFactory.Server sslserver = new SslContextFactory.Server();
        sslserver.setKeyStoreType("jks");
        sslserver.setKeyStorePath("src/test/resources/ssl/keystore.jks");
        sslserver.setKeyStorePassword("secret");
        this.server = new Http3Server(new TestSlice(), this.port, sslserver);
        this.server.start();
        
        // Create client with ClientQuicConfiguration
        final ClientQuicConfiguration clientQuicConfig = new ClientQuicConfiguration();
        this.client = new HTTP3Client(clientQuicConfig);
        this.client.getHTTP3Configuration().setStreamIdleTimeout(15_000);
        
        final SslContextFactory.Client ssl = new SslContextFactory.Client();
        ssl.setTrustAll(true);
        this.client.start();
        
        // Connect with Transport and Promise
        final CompletableFuture<Session.Client> sessionFuture = new CompletableFuture<>();
        this.client.connect(
            Transport.TCP_IP,
            ssl,
            new InetSocketAddress("localhost", this.port),
            new Session.Client.Listener() { },
            new Promise.Invocable.NonBlocking<>() {
                @Override
                public void succeeded(Session.Client result) {
                    sessionFuture.complete(result);
                }
                @Override
                public void failed(Throwable error) {
                    sessionFuture.completeExceptionally(error);
                }
            }
        );
        this.session = sessionFuture.get();
    }

    @AfterEach
    void stop() throws Exception {
        this.client.stop();
        this.server.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "DELETE"})
    void sendsRequestsAndReceivesResponseWithNoData(final String method) throws ExecutionException,
        InterruptedException, TimeoutException {
        final CountDownLatch count = new CountDownLatch(1);
        this.session.newRequest(
            new HeadersFrame(
                new MetaData.Request(
                    method, HttpURI.from(String.format("http://localhost:%d/no_data", this.port)),
                    HttpVersion.HTTP_3, HttpFields.EMPTY
                ), true
            ),
            new Stream.Client.Listener() {
                @Override
                public void onResponse(final Stream.Client stream, final HeadersFrame frame) {
                    final MetaData meta = frame.getMetaData();
                    final MetaData.Response response = (MetaData.Response) meta;
                    MatcherAssert.assertThat(
                        response.getHttpFields().get(Http3ServerTest.RQ_METHOD),
                        new IsEqual<>(method)
                    );
                    count.countDown();
                }
            },
            new Promise.Invocable.NonBlocking<>() {
                @Override
                public void succeeded(Stream stream) { /* Stream created */ }
                @Override
                public void failed(Throwable error) { count.countDown(); }
            }
        );
        MatcherAssert.assertThat("Response was not received", count.await(5, TimeUnit.SECONDS));
    }

    @Test
    void getWithSmallResponseData() throws ExecutionException,
        InterruptedException, TimeoutException {
        final MetaData.Request request = new MetaData.Request(
            "GET", HttpURI.from(String.format("http://localhost:%d/small_data", this.port)),
            HttpVersion.HTTP_3, HttpFields.from()
        );
        final StreamTestListener listener = new StreamTestListener(Http3ServerTest.SMALL_DATA.length);
        this.session.newRequest(
            new HeadersFrame(request, true),
            listener,
            new Promise.Invocable.NonBlocking<>() {
                @Override
                public void succeeded(Stream stream) { /* Stream created */ }
                @Override
                public void failed(Throwable error) { /* Error */ }
            }
        );
        MatcherAssert.assertThat("Response was not received", listener.awaitResponse(5));
        final boolean dataReceived = listener.awaitData(5);
        MatcherAssert.assertThat(
            "Error: response completion timeout. Currently received bytes: %s".formatted(listener.received()),
            dataReceived
        );
        listener.assertDataMatch(Http3ServerTest.SMALL_DATA);
    }

    @Test
    void getWithChunkedResponseData() throws ExecutionException,
        InterruptedException, TimeoutException {
        final MetaData.Request request = new MetaData.Request(
            "GET", HttpURI.from(String.format("http://localhost:%d/random_chunks", this.port)),
            HttpVersion.HTTP_3, HttpFields.from()
        );
        final StreamTestListener listener = new StreamTestListener(Http3ServerTest.SIZE);
        this.session.newRequest(
            new HeadersFrame(request, true),
            listener,
            new Promise.Invocable.NonBlocking<>() {
                @Override
                public void succeeded(Stream stream) { /* Stream created */ }
                @Override
                public void failed(Throwable error) { /* Error */ }
            }
        );
        MatcherAssert.assertThat("Response was not received", listener.awaitResponse(5));
        final boolean dataReceived = listener.awaitData(60);
        MatcherAssert.assertThat(
            "Error: response completion timeout. Currently received bytes: %s".formatted(listener.received()),
            dataReceived
        );
        MatcherAssert.assertThat(listener.received(), new IsEqual<>(Http3ServerTest.SIZE));
    }

    @Test
    void putWithRequestDataResponse() throws ExecutionException, InterruptedException,
        TimeoutException {
        final int size = 964;
        final MetaData.Request request = new MetaData.Request(
            "PUT", HttpURI.from(String.format("http://localhost:%d/return_back", this.port)),
            HttpVersion.HTTP_3,
            HttpFields.build()
        );
        final StreamTestListener listener = new StreamTestListener(size * 2);
        final byte[] data = new byte[size];
        new Random().nextBytes(data);
        final CompletableFuture<Stream> streamFuture = new CompletableFuture<>();
        this.session.newRequest(
            new HeadersFrame(request, false),
            listener,
            new Promise.Invocable.NonBlocking<Stream>() {
                public void succeeded(Stream result) { streamFuture.complete(result); }
                public void failed(Throwable error) { /* Error */ }
            }
        );
        final Stream.Client stream = (Stream.Client) streamFuture.get(5, TimeUnit.SECONDS);
        stream.data(
            new DataFrame(ByteBuffer.wrap(data), false),
            new Promise.Invocable.NonBlocking<>() {
                @Override
                public void succeeded(Stream result) { /* Continue */ }
                @Override
                public void failed(Throwable error) { /* Error */ }
            }
        );
        stream.data(
            new DataFrame(ByteBuffer.wrap(data), true),
            new Promise.Invocable.NonBlocking<>() {
                @Override
                public void succeeded(Stream result) { /* Done */ }
                @Override
                public void failed(Throwable error) { /* Error */ }
            }
        );
        MatcherAssert.assertThat("Response was not received", listener.awaitResponse(10));
        final boolean dataReceived = listener.awaitData(10);
        MatcherAssert.assertThat(
            "Error: response completion timeout. Currently received bytes: %s".formatted(listener.received()),
            dataReceived
        );
        final ByteBuffer copy = ByteBuffer.allocate(size * 2);
        copy.put(data);
        copy.put(data);
        listener.assertDataMatch(copy.array());
    }

    /**
     * Slice for tests.
     */
    static final class TestSlice implements Slice {

        @Override
        public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
            if (line.toString().contains("no_data")) {
                return ResponseBuilder.ok()
                    .header( Http3ServerTest.RQ_METHOD, line.method().value())
                    .completedFuture();
            }
            if (line.toString().contains("small_data")) {
                return ResponseBuilder.ok()
                    .body(Http3ServerTest.SMALL_DATA)
                    .completedFuture();
            }
            if (line.toString().contains("random_chunks")) {
                final Random random = new Random();
                final byte[] data = new byte[Http3ServerTest.SIZE];
                random.nextBytes(data);
                return ResponseBuilder.ok().body(
                    new Content.From(
                        Flowable.fromArray(ByteBuffer.wrap(data))
                            .flatMap(
                                buffer -> new Splitting(
                                    buffer, (random.nextInt(9) + 1) * 1024
                                ).publisher()
                            )
                            .delay(random.nextInt(5_000), TimeUnit.MILLISECONDS)
                    )
                ).completedFuture();
            }
            if (line.toString().contains("return_back")) {
                return ResponseBuilder.ok().body(body).completedFuture();
            }
            return ResponseBuilder.notFound().completedFuture();
        }
    }

    /**
     * Client-side listener for testing http3 server responses.
     */
    private static final class StreamTestListener implements Stream.Client.Listener {

        final CountDownLatch responseLatch;

        final CountDownLatch dataAvailableLatch;

        final ByteBuffer buffer;

        StreamTestListener(final int length) {
            this.responseLatch = new CountDownLatch(1);
            this.dataAvailableLatch = new CountDownLatch(1);
            this.buffer = ByteBuffer.allocate(length);
        }

        public boolean awaitResponse(final int seconds) throws InterruptedException {
            return this.responseLatch.await(seconds, TimeUnit.SECONDS);
        }

        public boolean awaitData(final int seconds) throws InterruptedException {
            return this.dataAvailableLatch.await(seconds, TimeUnit.SECONDS);
        }

        public int received() {
            return this.buffer.position();
        }

        public void assertDataMatch(final byte[] copy) {
            Assertions.assertArrayEquals(copy, this.buffer.array());
        }

        @Override
        public void onResponse(final Stream.Client stream, final HeadersFrame frame) {
            responseLatch.countDown();
            stream.demand();
        }

        @Override
        public void onDataAvailable(final Stream.Client stream) {
            stream.demand();
            this.dataAvailableLatch.countDown();
        }
    }
}
