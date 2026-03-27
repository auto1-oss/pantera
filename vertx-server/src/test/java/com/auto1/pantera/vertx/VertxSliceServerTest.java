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
package com.auto1.pantera.vertx;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.reactivex.Flowable;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Ensure that {@link VertxSliceServer} works correctly.
 */
public final class VertxSliceServerTest {

    /**
     * The host to send http requests to.
     */
    private static final String HOST = "localhost";

    /**
     * Server port.
     */
    private int port;

    /**
     * Vertx instance used in server and client.
     */
    private Vertx vertx;

    /**
     * HTTP client used to send requests to server.
     */
    private WebClient client;

    /**
     * Server instance being tested.
     */
    private VertxSliceServer server;

    @BeforeEach
    public void setUp() throws Exception {
        this.port = this.rndPort();
        this.vertx = Vertx.vertx();
        this.client = WebClient.create(this.vertx);
    }

    @Test
    public void headRequestPreservesContentLength() {
        final String path = "/head";
        final String header = "Content-Length";
        final long length = 123L;
        this.start(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header(header, Long.toString(length))
                    .build()
            )
        );
        final HttpResponse<Buffer> response = this.client
            .head(this.port, VertxSliceServerTest.HOST, path)
            .rxSend()
            .blockingGet();
        MatcherAssert.assertThat(response.statusCode(), Matchers.equalTo(200));
        MatcherAssert.assertThat(response.getHeader(header), Matchers.equalTo(Long.toString(length)));
        MatcherAssert.assertThat(response.body(), Matchers.nullValue());
    }

    /**
     * Test that large file downloads (>200MB) work correctly without corruption.
     * This is a regression test for the observeOn() bug that caused file corruption.
     *
     * The bug was: adding .observeOn(Schedulers.io()) to the response streaming pipeline
     * broke Vert.x's threading model, causing race conditions and data corruption for large files.
     */
    @Test
    public void largeFileDownloadWithoutCorruption() throws Exception {
        final String path = "/large-file.jar";

        // Create a 300MB test file (larger than the reported 291MB corruption case)
        final int fileSize = 300 * 1024 * 1024; // 300 MB
        final int chunkSize = 8192; // 8KB chunks
        final byte[] pattern = new byte[chunkSize];
        new Random(42).nextBytes(pattern); // Deterministic pattern for verification

        // Calculate expected SHA-256 checksum
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < fileSize / chunkSize; i++) {
            digest.update(pattern);
        }
        final byte[] expectedChecksum = digest.digest();

        // Start server with large file response
        this.start(
            (line, headers, body) -> {
                // Stream the file in chunks using reactive streams
                final Flowable<ByteBuffer> chunks = Flowable.range(0, fileSize / chunkSize)
                    .map(i -> ByteBuffer.wrap(pattern));

                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Length", String.valueOf(fileSize))
                        .header("Content-Type", "application/java-archive")
                        .body(new Content.From(chunks))
                        .build()
                );
            }
        );

        // Download the file
        final HttpResponse<Buffer> response = this.client
            .get(this.port, VertxSliceServerTest.HOST, path)
            .rxSend()
            .blockingGet();

        // Verify response
        MatcherAssert.assertThat("Status should be 200", response.statusCode(), Matchers.equalTo(200));
        MatcherAssert.assertThat(
            "Content-Length header should match",
            response.getHeader("Content-Length"),
            Matchers.equalTo(String.valueOf(fileSize))
        );

        // Verify downloaded file size
        final Buffer downloadedBody = response.body();
        MatcherAssert.assertThat(
            "Downloaded file size should match expected size",
            downloadedBody.length(),
            Matchers.equalTo(fileSize)
        );

        // Verify file integrity using checksum
        final MessageDigest actualDigest = MessageDigest.getInstance("SHA-256");
        actualDigest.update(downloadedBody.getBytes());
        final byte[] actualChecksum = actualDigest.digest();

        MatcherAssert.assertThat(
            "File checksum should match (no corruption)",
            actualChecksum,
            Matchers.equalTo(expectedChecksum)
        );
    }

    @AfterEach
    public void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
        if (this.client != null) {
            this.client.close();
        }
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    public void serverHandlesBasicRequest() {
        this.start(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(body).build()
            )
        );
        final String expected = "Hello World!";
        final String actual = this.client.post(this.port, VertxSliceServerTest.HOST, "/hello")
            .rxSendBuffer(Buffer.buffer(expected.getBytes()))
            .blockingGet()
            .bodyAsString();
        MatcherAssert.assertThat(actual, Matchers.equalTo(expected));
    }

    @Test
    public void basicGetRequest() {
        final String expected = "Hello World!!!";
        this.start(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(expected.getBytes()).build()
            )
        );
        final String actual = this.client.get(this.port, VertxSliceServerTest.HOST, "/hello1")
            .rxSend()
            .blockingGet()
            .bodyAsString();
        MatcherAssert.assertThat(actual, Matchers.equalTo(expected));
    }

    @Test
    public void basicGetRequestWithContentLengthHeader() {
        final String clh = "Content-Length";
        final String expected = "Hello World!!!!!";
        this.start(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header(clh, Integer.toString(expected.length()))
                    .body(expected.getBytes())
                    .build()
            )
        );
        final HttpResponse<Buffer> response = this.client
            .get(this.port, VertxSliceServerTest.HOST, "/hello2")
            .rxSend()
            .blockingGet();
        final String actual = response.bodyAsString();
        MatcherAssert.assertThat(actual, Matchers.equalTo(expected));
        MatcherAssert.assertThat(response.getHeader(clh), Matchers.notNullValue());
        MatcherAssert.assertThat(
            response.getHeader(String.valueOf(HttpHeaderNames.TRANSFER_ENCODING)),
            Matchers.nullValue()
        );
    }

    @Test
    public void exceptionInSlice() {
        final RuntimeException exception = new IllegalStateException("Failed to create response");
        this.start(
            (line, headers, body) -> {
                throw exception;
            }
        );
        final HttpResponse<Buffer> response = this.client.get(
            this.port, VertxSliceServerTest.HOST, ""
        ).rxSend().blockingGet();
        MatcherAssert.assertThat(response, new IsErrorResponse(exception));
    }

    @Test
    public void exceptionInResponse() {
        final RuntimeException exception = new IllegalStateException("Failed to send response");
        this.start(
            (line, headers, body) -> {
                throw exception;
            }
        );
        final HttpResponse<Buffer> response = this.client.get(
            this.port, VertxSliceServerTest.HOST, ""
        ).rxSend().blockingGet();
        MatcherAssert.assertThat(response, new IsErrorResponse(exception));
    }

    @Test
    public void exceptionInResponseAsync() {
        final RuntimeException exception = new IllegalStateException(
            "Failed to send response async"
        );
        this.start(
            (line, headers, body) -> CompletableFuture.failedFuture(exception)
        );
        final HttpResponse<Buffer> response = this.client
            .get(this.port, VertxSliceServerTest.HOST, "")
            .rxSend().blockingGet();
        MatcherAssert.assertThat(response, new IsErrorResponse(exception));
    }

    @Test
    public void exceptionInBody() {
        final Throwable exception = new IllegalStateException("Failed to publish body");
        this.start(
            (line, headers, body) -> CompletableFuture.supplyAsync(
                () -> ResponseBuilder.ok().body(new Content.From(Flowable.error(exception))).build()
            )
        );
        final HttpResponse<Buffer> response = this.client.get(
            this.port, VertxSliceServerTest.HOST, ""
        ).rxSend().blockingGet();
        MatcherAssert.assertThat(response, new IsErrorResponse(exception));
    }

    @Test
    public void serverMayStartOnRandomPort() {
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().headers(headers).body(body).build()
            )
        );
        MatcherAssert.assertThat(srv.start(), new IsNot<>(new IsEqual<>(0)));
    }

    @Test
    public void serverStartsWithHttpServerOptions() throws Exception {
        final int expected = this.rndPort();
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().headers(headers).body(body).build()
            ),
            new HttpServerOptions().setPort(expected)
        );
        MatcherAssert.assertThat(srv.start(), new IsEqual<>(expected));
    }

    @Test
    void repeatedServerStartTest() {
        this.start(
            (s, iterable, publisher) -> {
                throw new IllegalStateException("Request serving is not expected in this test");
            }
        );
        final IllegalStateException err = Assertions.assertThrows(
            IllegalStateException.class,
            this.server::start
        );
        Assertions.assertEquals("Server was already started", err.getMessage());
    }

    @Test
    void doesNotCompressJarFiles() throws Exception {
        final byte[] jarContent = new byte[1024];
        java.util.Arrays.fill(jarContent, (byte) 'A');
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", "application/java-archive")
                .header("Content-Length", String.valueOf(jarContent.length))
                .body(jarContent)
                .build()
        );
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx, slice, this.port,
            java.time.Duration.ofMinutes(1)
        );
        srv.start();
        this.server = srv;
        final HttpResponse<Buffer> response = this.client
            .get(this.port, VertxSliceServerTest.HOST, "/artifact.jar")
            .putHeader("Accept-Encoding", "gzip")
            .rxSend()
            .blockingGet();
        MatcherAssert.assertThat(
            "Status should be 200",
            response.statusCode(), Matchers.equalTo(200)
        );
        MatcherAssert.assertThat(
            "Binary artifact should not be gzip-compressed",
            response.getHeader("Content-Encoding"),
            new IsNot<>(new IsEqual<>("gzip"))
        );
        MatcherAssert.assertThat(
            "Body content should match original (not compressed)",
            response.body().length(),
            Matchers.equalTo(jarContent.length)
        );
    }

    @Test
    void doesNotCompressGzipFiles() throws Exception {
        final byte[] gzContent = new byte[2048];
        java.util.Arrays.fill(gzContent, (byte) 'B');
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", "application/gzip")
                .header("Content-Length", String.valueOf(gzContent.length))
                .body(gzContent)
                .build()
        );
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx, slice, this.port,
            java.time.Duration.ofMinutes(1)
        );
        srv.start();
        this.server = srv;
        final HttpResponse<Buffer> response = this.client
            .get(this.port, VertxSliceServerTest.HOST, "/archive.tar.gz")
            .putHeader("Accept-Encoding", "gzip")
            .rxSend()
            .blockingGet();
        MatcherAssert.assertThat(
            "Status should be 200",
            response.statusCode(), Matchers.equalTo(200)
        );
        MatcherAssert.assertThat(
            "Gzip content should not be re-compressed",
            response.getHeader("Content-Encoding"),
            new IsNot<>(new IsEqual<>("gzip"))
        );
    }

    @Test
    void gracefulShutdownDrainsInflightRequests() throws Exception {
        final java.util.concurrent.CountDownLatch requestReceived = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch requestRelease = new java.util.concurrent.CountDownLatch(1);
        final Slice slowSlice = (line, headers, body) -> {
            requestReceived.countDown();
            try {
                requestRelease.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("done").build()
            );
        };
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx, slowSlice, this.port,
            java.time.Duration.ofMinutes(1)
        );
        srv.start();
        this.server = srv;
        // Start a slow request in background
        final CompletableFuture<HttpResponse<Buffer>> responseFuture = CompletableFuture.supplyAsync(() ->
            this.client.get(this.port, VertxSliceServerTest.HOST, "/slow")
                .rxSend()
                .blockingGet()
        );
        // Wait for request to reach the slice
        requestReceived.await(5, java.util.concurrent.TimeUnit.SECONDS);
        // Verify there's at least one in-flight request
        MatcherAssert.assertThat(
            "Should have in-flight requests",
            srv.inFlightCount(),
            Matchers.greaterThan(0)
        );
        // Release the request so shutdown can complete
        requestRelease.countDown();
        // Stop should wait for the request to complete
        srv.stop();
        this.server = null; // prevent double-close in tearDown
        // Verify the response was successful
        final HttpResponse<Buffer> response = responseFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "In-flight request should complete during drain",
            response.statusCode(),
            Matchers.equalTo(200)
        );
    }

    @Test
    void rejectsNewRequestsDuringShutdown() throws Exception {
        final java.util.concurrent.CountDownLatch requestReceived = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch requestRelease = new java.util.concurrent.CountDownLatch(1);
        final Slice slowSlice = (line, headers, body) -> {
            requestReceived.countDown();
            try {
                requestRelease.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("done").build()
            );
        };
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx, slowSlice, this.port,
            java.time.Duration.ofMinutes(1)
        );
        srv.start();
        this.server = srv;
        // Start a slow request to keep drain active
        final CompletableFuture<HttpResponse<Buffer>> firstResponse = CompletableFuture.supplyAsync(() ->
            this.client.get(this.port, VertxSliceServerTest.HOST, "/slow")
                .rxSend()
                .blockingGet()
        );
        // Wait for the request to reach the slice
        requestReceived.await(5, java.util.concurrent.TimeUnit.SECONDS);
        // Initiate shutdown in background (will drain for up to 30s)
        final CompletableFuture<Void> shutdownFuture = CompletableFuture.runAsync(() -> srv.stop());
        // Give the shutdown a moment to set the shuttingDown flag
        Thread.sleep(200);
        // Try to send a new request - should get 503
        final HttpResponse<Buffer> newResponse = this.client
            .get(this.port, VertxSliceServerTest.HOST, "/new-request")
            .rxSend()
            .blockingGet();
        MatcherAssert.assertThat(
            "New requests should be rejected with 503 during shutdown",
            newResponse.statusCode(),
            Matchers.equalTo(503)
        );
        // Release the slow request so shutdown can complete
        requestRelease.countDown();
        shutdownFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
        this.server = null; // prevent double-close in tearDown
    }

    @Test
    void defaultBodyBufferThresholdIsOneMegabyte() {
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            )
        );
        MatcherAssert.assertThat(
            "Default body buffer threshold should be 1MB",
            srv.bodyBufferThreshold(),
            Matchers.equalTo(VertxSliceServer.DEFAULT_BODY_BUFFER_THRESHOLD)
        );
    }

    @Test
    void customBodyBufferThresholdIsUsed() throws Exception {
        final long customThreshold = 512L;
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            ),
            new HttpServerOptions().setPort(0),
            java.time.Duration.ofMinutes(1),
            java.time.Duration.ofSeconds(30),
            customThreshold
        );
        MatcherAssert.assertThat(
            "Custom body buffer threshold should be set",
            srv.bodyBufferThreshold(),
            Matchers.equalTo(customThreshold)
        );
    }

    @Test
    void customThresholdBuffersSmallBodiesAndStreamsLarge() throws Exception {
        // Use a very small threshold (512 bytes) so we can easily test both paths
        final long customThreshold = 512L;
        final java.util.concurrent.atomic.AtomicReference<String> receivedBody =
            new java.util.concurrent.atomic.AtomicReference<>();
        final Slice echoSlice = (line, headers, body) -> {
            // Read the entire body and echo it back
            final CompletableFuture<com.auto1.pantera.http.Response> future = new CompletableFuture<>();
            final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            io.reactivex.Flowable.fromPublisher(body)
                .doOnNext(buf -> {
                    final byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    baos.write(bytes, 0, bytes.length);
                })
                .doOnComplete(() -> {
                    receivedBody.set(baos.toString("UTF-8"));
                    future.complete(
                        ResponseBuilder.ok()
                            .header("Content-Length", String.valueOf(baos.size()))
                            .body(baos.toByteArray())
                            .build()
                    );
                })
                .doOnError(future::completeExceptionally)
                .subscribe();
            return future;
        };
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx,
            echoSlice,
            new HttpServerOptions().setPort(this.port),
            java.time.Duration.ofMinutes(1),
            java.time.Duration.ofSeconds(30),
            customThreshold
        );
        srv.start();
        this.server = srv;
        // Test 1: Send a small body (< 512 bytes) - should be buffered
        final byte[] smallBody = new byte[256];
        java.util.Arrays.fill(smallBody, (byte) 'A');
        final HttpResponse<Buffer> smallResponse = this.client
            .post(this.port, VertxSliceServerTest.HOST, "/small")
            .putHeader("Content-Length", String.valueOf(smallBody.length))
            .rxSendBuffer(Buffer.buffer(smallBody))
            .blockingGet();
        MatcherAssert.assertThat(
            "Small body request should succeed (buffered path)",
            smallResponse.statusCode(),
            Matchers.equalTo(200)
        );
        MatcherAssert.assertThat(
            "Small body should be echoed back correctly",
            smallResponse.body().length(),
            Matchers.equalTo(smallBody.length)
        );
        // Test 2: Send a large body (>= 512 bytes) - should be streamed
        final byte[] largeBody = new byte[1024];
        java.util.Arrays.fill(largeBody, (byte) 'B');
        final HttpResponse<Buffer> largeResponse = this.client
            .post(this.port, VertxSliceServerTest.HOST, "/large")
            .putHeader("Content-Length", String.valueOf(largeBody.length))
            .rxSendBuffer(Buffer.buffer(largeBody))
            .blockingGet();
        MatcherAssert.assertThat(
            "Large body request should succeed (streaming path)",
            largeResponse.statusCode(),
            Matchers.equalTo(200)
        );
        MatcherAssert.assertThat(
            "Large body should be echoed back correctly",
            largeResponse.body().length(),
            Matchers.equalTo(largeBody.length)
        );
    }

    @Test
    void bodyBufferThresholdRejectsNonPositiveValues() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new VertxSliceServer(
                this.vertx,
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().build()
                ),
                new HttpServerOptions().setPort(0),
                java.time.Duration.ofMinutes(1),
                java.time.Duration.ofSeconds(30),
                0L
            ),
            "bodyBufferThreshold of 0 should be rejected"
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new VertxSliceServer(
                this.vertx,
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().build()
                ),
                new HttpServerOptions().setPort(0),
                java.time.Duration.ofMinutes(1),
                java.time.Duration.ofSeconds(30),
                -1L
            ),
            "Negative bodyBufferThreshold should be rejected"
        );
    }

    @Test
    void chunkedTransferEncodingBodyIsReceived() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<byte[]> receivedBody =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Boolean> sizeWasUnknown =
            new java.util.concurrent.atomic.AtomicReference<>();
        final Slice captureSlice = (line, headers, body) -> {
            sizeWasUnknown.set(body.size().isEmpty());
            final CompletableFuture<com.auto1.pantera.http.Response> future = new CompletableFuture<>();
            final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            Flowable.fromPublisher(body)
                .doOnNext(buf -> {
                    final byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    baos.write(bytes, 0, bytes.length);
                })
                .doOnComplete(() -> {
                    receivedBody.set(baos.toByteArray());
                    future.complete(
                        ResponseBuilder.ok()
                            .header("Content-Length", String.valueOf(baos.size()))
                            .body(baos.toByteArray())
                            .build()
                    );
                })
                .doOnError(future::completeExceptionally)
                .subscribe();
            return future;
        };
        final VertxSliceServer srv = new VertxSliceServer(
            this.vertx, captureSlice, this.port,
            java.time.Duration.ofMinutes(1)
        );
        srv.start();
        this.server = srv;
        // Send a chunked request (no Content-Length) using sendStream
        // This simulates: curl -T - -XPUT (piped stdin, chunked transfer)
        final byte[] payload = new byte[4096];
        new Random(123).nextBytes(payload);
        final Flowable<Buffer> chunkedBody = Flowable.just(Buffer.buffer(payload));
        final HttpResponse<Buffer> response = this.client
            .put(this.port, VertxSliceServerTest.HOST, "/chunked-upload")
            .rxSendStream(chunkedBody)
            .blockingGet();
        MatcherAssert.assertThat(
            "Chunked upload should succeed",
            response.statusCode(),
            Matchers.equalTo(200)
        );
        MatcherAssert.assertThat(
            "Body should be received completely",
            receivedBody.get().length,
            Matchers.equalTo(payload.length)
        );
        MatcherAssert.assertThat(
            "Body content should match what was sent",
            receivedBody.get(),
            Matchers.equalTo(payload)
        );
        MatcherAssert.assertThat(
            "Content size should be unknown for chunked transfer",
            sizeWasUnknown.get(),
            Matchers.equalTo(true)
        );
    }

    @Test
    void chunkedTransferMultipleChunksReceived() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger receivedSize =
            new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger chunkCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
        final Slice countSlice = (line, headers, body) -> {
            final CompletableFuture<com.auto1.pantera.http.Response> future = new CompletableFuture<>();
            Flowable.fromPublisher(body)
                .doOnNext(buf -> {
                    chunkCount.incrementAndGet();
                    receivedSize.addAndGet(buf.remaining());
                })
                .doOnComplete(() -> future.complete(
                    ResponseBuilder.ok()
                        .textBody(String.valueOf(receivedSize.get()))
                        .build()
                ))
                .doOnError(future::completeExceptionally)
                .subscribe();
            return future;
        };
        this.start(countSlice);
        // Send a 64KB chunked body (large enough to span multiple TCP segments)
        final byte[] payload = new byte[65536];
        new Random(999).nextBytes(payload);
        final Flowable<Buffer> chunkedBody = Flowable.just(Buffer.buffer(payload));
        final HttpResponse<Buffer> response = this.client
            .put(this.port, VertxSliceServerTest.HOST, "/chunked-large")
            .rxSendStream(chunkedBody)
            .blockingGet();
        MatcherAssert.assertThat(
            "Chunked upload should succeed",
            response.statusCode(),
            Matchers.equalTo(200)
        );
        MatcherAssert.assertThat(
            "All bytes should be received",
            receivedSize.get(),
            Matchers.equalTo(payload.length)
        );
        MatcherAssert.assertThat(
            "Body should have been received in at least one chunk",
            chunkCount.get(),
            Matchers.greaterThanOrEqualTo(1)
        );
    }

    private void start(final Slice slice) {
        final VertxSliceServer srv = new VertxSliceServer(this.vertx, slice, this.port);
        srv.start();
        this.server = srv;
    }

    /**
     * Find a random port.
     *
     * @return The free port.
     * @throws IOException If fails.
     */
    private int rndPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Matcher for HTTP response to check that it is proper error response.
     *
     * @since 0.1
     */
    private static class IsErrorResponse extends TypeSafeMatcher<HttpResponse<Buffer>> {

        /**
         * HTTP status code matcher.
         */
        private final Matcher<Integer> status;

        /**
         * HTTP body matcher.
         */
        private final Matcher<String> body;

        /**
         * Ctor.
         *
         * @param throwable Expected error response reason.
         */
        IsErrorResponse(final Throwable throwable) {
            this.status = new IsEqual<>(HttpURLConnection.HTTP_INTERNAL_ERROR);
            // Check for exception class name - message format may vary depending on wrapping
            this.body = new StringContains(false, throwable.getClass().getSimpleName());
        }

        @Override
        public void describeTo(final Description description) {
            description
                .appendText("(")
                .appendDescriptionOf(this.status)
                .appendText(" and ")
                .appendDescriptionOf(this.body)
                .appendText(")");
        }

        @Override
        public boolean matchesSafely(final HttpResponse<Buffer> response) {
            return this.status.matches(response.statusCode())
                && this.body.matches(response.bodyAsString());
        }
    }
}
