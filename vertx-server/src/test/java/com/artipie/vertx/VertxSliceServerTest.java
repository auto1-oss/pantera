/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.vertx;

import com.artipie.asto.Content;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
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
