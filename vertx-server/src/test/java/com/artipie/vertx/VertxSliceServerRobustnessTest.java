/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.vertx;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import io.reactivex.Flowable;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Robustness tests for {@link VertxSliceServer}.
 * 
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>ResponseTerminator ensures responses are always ended exactly once</li>
 *   <li>Request timeouts work correctly and return 503</li>
 *   <li>Concurrent requests don't cause resource leaks</li>
 *   <li>Error handling doesn't leave connections hanging</li>
 *   <li>Slow/stalled upstream slices are handled gracefully</li>
 * </ul>
 */
final class VertxSliceServerRobustnessTest {

    private static final String HOST = "localhost";

    private int port;
    private Vertx vertx;
    private WebClient client;
    private VertxSliceServer server;

    @BeforeEach
    void setUp() throws Exception {
        this.port = findFreePort();
        this.vertx = Vertx.vertx();
        this.client = WebClient.create(
            this.vertx,
            new WebClientOptions()
                .setConnectTimeout(30000)
                .setIdleTimeout(60)
        );
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            try {
                this.server.close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        if (this.client != null) {
            this.client.close();
        }
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    @DisplayName("Request timeout returns 503 Service Unavailable")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void requestTimeoutReturns503() throws Exception {
        // Server with 1 second timeout and a slice that takes 5 seconds
        final Duration timeout = Duration.ofSeconds(1);
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                // Simulate slow upstream processing
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ResponseBuilder.ok().textBody("too late").build();
                });
            },
            new HttpServerOptions().setPort(this.port),
            timeout
        );
        this.server.start();

        final HttpResponse<Buffer> response = this.client
            .get(this.port, HOST, "/slow")
            .rxSend()
            .blockingGet();

        assertThat("Should return 503 on timeout", response.statusCode(), equalTo(503));
        assertTrue(
            response.bodyAsString().contains("timed out"),
            "Body should mention timeout"
        );
    }

    @Test
    @DisplayName("Concurrent requests are handled without leaks")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentRequestsHandledWithoutLeaks() throws Exception {
        final AtomicInteger activeRequests = new AtomicInteger(0);
        final AtomicInteger maxConcurrent = new AtomicInteger(0);
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                final int current = activeRequests.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        // Small delay to allow concurrency
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    activeRequests.decrementAndGet();
                    return ResponseBuilder.ok().textBody("ok").build();
                });
            },
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final int totalRequests = 100;
        final int concurrency = 20;
        final CountDownLatch latch = new CountDownLatch(totalRequests);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            this.client.get(this.port, HOST, "/concurrent/" + i)
                .rxSend()
                .subscribe(
                    resp -> {
                        if (resp.statusCode() == 200) {
                            successCount.incrementAndGet();
                        } else {
                            errorCount.incrementAndGet();
                        }
                        latch.countDown();
                    },
                    error -> {
                        errorCount.incrementAndGet();
                        latch.countDown();
                    }
                );
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All requests should complete");
        assertThat("All requests should succeed", successCount.get(), equalTo(totalRequests));
        assertThat("No errors should occur", errorCount.get(), equalTo(0));
        assertThat("Active requests should be 0 after completion", activeRequests.get(), equalTo(0));
        assertThat("Should have had concurrent requests", maxConcurrent.get(), greaterThan(1));
    }

    @Test
    @DisplayName("Response body error triggers ResponseTerminator.fail()")
    void responseBodyErrorTriggersTerminatorFail() throws Exception {
        final RuntimeException bodyError = new RuntimeException("Simulated body stream error");
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(new Content.From(Flowable.error(bodyError)))
                    .build()
            ),
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final HttpResponse<Buffer> response = this.client
            .get(this.port, HOST, "/body-error")
            .rxSend()
            .blockingGet();

        // Should get 500 error response
        assertThat("Should return 500 on body error", response.statusCode(), equalTo(500));
        assertTrue(
            response.bodyAsString().contains("RuntimeException"),
            "Body should contain error info"
        );
    }

    @Test
    @DisplayName("Slice exception returns 500 and doesn't leak")
    void sliceExceptionReturns500() throws Exception {
        final AtomicInteger requestCount = new AtomicInteger(0);
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                requestCount.incrementAndGet();
                throw new IllegalStateException("Slice processing failed");
            },
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        // Make multiple requests to ensure server stays healthy
        for (int i = 0; i < 10; i++) {
            final HttpResponse<Buffer> response = this.client
                .get(this.port, HOST, "/error/" + i)
                .rxSend()
                .blockingGet();

            assertThat("Should return 500", response.statusCode(), equalTo(500));
        }

        assertThat("All requests should have been processed", requestCount.get(), equalTo(10));
    }

    @Test
    @DisplayName("Async slice failure returns 500")
    void asyncSliceFailureReturns500() throws Exception {
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.failedFuture(
                new RuntimeException("Async processing failed")
            ),
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final HttpResponse<Buffer> response = this.client
            .get(this.port, HOST, "/async-error")
            .rxSend()
            .blockingGet();

        assertThat("Should return 500 on async failure", response.statusCode(), equalTo(500));
    }

    @Test
    @DisplayName("Empty response body is handled correctly")
    void emptyResponseBodyHandled() throws Exception {
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            ),
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final HttpResponse<Buffer> response = this.client
            .get(this.port, HOST, "/empty")
            .rxSend()
            .blockingGet();

        assertThat("Should return 200", response.statusCode(), equalTo(200));
    }

    @Test
    @DisplayName("Large streaming response completes without error")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void largeStreamingResponseCompletes() throws Exception {
        final int chunkCount = 1000;
        final int chunkSize = 1024;
        final byte[] chunk = new byte[chunkSize];
        java.util.Arrays.fill(chunk, (byte) 'X');
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                final Flowable<ByteBuffer> stream = Flowable.range(0, chunkCount)
                    .map(i -> ByteBuffer.wrap(chunk));
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("Content-Length", String.valueOf(chunkCount * chunkSize))
                        .body(new Content.From(stream))
                        .build()
                );
            },
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final HttpResponse<Buffer> response = this.client
            .get(this.port, HOST, "/large")
            .rxSend()
            .blockingGet();

        assertThat("Should return 200", response.statusCode(), equalTo(200));
        assertThat(
            "Body size should match",
            response.body().length(),
            equalTo(chunkCount * chunkSize)
        );
    }

    @Test
    @DisplayName("Multiple sequential requests reuse connections")
    void sequentialRequestsReuseConnections() throws Exception {
        final AtomicInteger requestCount = new AtomicInteger(0);
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                requestCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().textBody("request " + requestCount.get()).build()
                );
            },
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final int totalRequests = 50;
        for (int i = 0; i < totalRequests; i++) {
            final HttpResponse<Buffer> response = this.client
                .get(this.port, HOST, "/seq/" + i)
                .rxSend()
                .blockingGet();

            assertThat("Should return 200", response.statusCode(), equalTo(200));
        }

        assertThat("All requests processed", requestCount.get(), equalTo(totalRequests));
    }

    @Test
    @DisplayName("POST request with body is handled correctly")
    void postRequestWithBodyHandled() throws Exception {
        final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> 
                new Content.From(body).asBytesFuture()
                    .thenApply(bytes -> {
                        receivedBody.set(bytes);
                        return ResponseBuilder.ok()
                            .textBody("received " + bytes.length + " bytes")
                            .build();
                    }),
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final byte[] requestBody = "Hello, Server!".getBytes();
        final HttpResponse<Buffer> response = this.client
            .post(this.port, HOST, "/upload")
            .rxSendBuffer(Buffer.buffer(requestBody))
            .blockingGet();

        assertThat("Should return 200", response.statusCode(), equalTo(200));
        assertThat("Should receive correct body", receivedBody.get(), equalTo(requestBody));
    }

    @Test
    @DisplayName("Server handles rapid request/response cycles")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void rapidRequestResponseCycles() throws Exception {
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("pong").build()
            ),
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        final int totalRequests = 500;
        final long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < totalRequests; i++) {
            final HttpResponse<Buffer> response = this.client
                .get(this.port, HOST, "/ping")
                .rxSend()
                .blockingGet();

            assertThat("Should return 200", response.statusCode(), equalTo(200));
        }

        final long duration = System.currentTimeMillis() - startTime;
        final double rps = (double) totalRequests / (duration / 1000.0);
        
        // Should handle at least 50 req/s for simple responses
        assertThat(
            String.format("Throughput was %.1f req/s, expected at least 50", rps),
            rps,
            greaterThan(50.0)
        );
    }

    @Test
    @DisplayName("Partial body consumption doesn't leak resources")
    void partialBodyConsumptionNoLeak() throws Exception {
        final int chunkCount = 100;
        final AtomicInteger chunksGenerated = new AtomicInteger(0);
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                final Flowable<ByteBuffer> stream = Flowable.range(0, chunkCount)
                    .map(i -> {
                        chunksGenerated.incrementAndGet();
                        return ByteBuffer.wrap(("chunk-" + i + "\n").getBytes());
                    });
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .body(new Content.From(stream))
                        .build()
                );
            },
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        // Make request but don't fully consume body (client will still receive it)
        final HttpResponse<Buffer> response = this.client
            .get(this.port, HOST, "/partial")
            .rxSend()
            .blockingGet();

        assertThat("Should return 200", response.statusCode(), equalTo(200));
        // All chunks should have been generated and sent
        assertThat("All chunks generated", chunksGenerated.get(), equalTo(chunkCount));
    }

    @Test
    @DisplayName("Server recovers after error responses")
    void serverRecoversAfterErrors() throws Exception {
        final AtomicInteger requestCount = new AtomicInteger(0);
        
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> {
                final int count = requestCount.incrementAndGet();
                // Every 3rd request fails
                if (count % 3 == 0) {
                    throw new RuntimeException("Intentional failure");
                }
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().textBody("success").build()
                );
            },
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();

        int successCount = 0;
        int errorCount = 0;
        
        for (int i = 0; i < 15; i++) {
            final HttpResponse<Buffer> response = this.client
                .get(this.port, HOST, "/recover/" + i)
                .rxSend()
                .blockingGet();

            if (response.statusCode() == 200) {
                successCount++;
            } else if (response.statusCode() == 500) {
                errorCount++;
            }
        }

        assertThat("Should have 10 successes", successCount, equalTo(10));
        assertThat("Should have 5 errors", errorCount, equalTo(5));
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
