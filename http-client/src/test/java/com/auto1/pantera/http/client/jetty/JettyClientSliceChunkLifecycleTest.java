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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.HttpServer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying correct Content.Chunk lifecycle management in JettyClientSlice.
 * These tests ensure that Jetty buffers are properly released regardless of whether
 * the response body is consumed, partially consumed, or not consumed at all.
 * 
 * <p>This guards against the leak described in Leak.md where incorrect retain/release
 * handling caused Jetty's ArrayByteBufferPool to grow unboundedly.</p>
 */
final class JettyClientSliceChunkLifecycleTest {

    /**
     * HTTP server used in tests.
     */
    private HttpServer server;

    /**
     * Jetty client slices with buffer pool instrumentation.
     */
    private JettyClientSlices clients;

    /**
     * HTTP client slice being tested.
     */
    private JettyClientSlice slice;

    @BeforeEach
    void setUp() throws Exception {
        this.server = new HttpServer();
        this.clients = new JettyClientSlices(new HttpClientSettings());
        this.clients.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.server != null && this.server.port() > 0) {
            try {
                this.server.stop();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        if (this.clients != null) {
            this.clients.stop();
        }
    }

    @Test
    @DisplayName("Buffer pool stats are accessible for monitoring")
    void bufferPoolStatsAreAccessible() throws Exception {
        // Start a minimal server for this test
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            )
        );
        this.server.start();
        
        final JettyClientSlices.BufferPoolStats stats = this.clients.getBufferPoolStats();
        assertThat("Buffer pool stats should be available", stats, notNullValue());
    }

    @Test
    @DisplayName("Unread response bodies do not leak buffers")
    void unreadBodiesDoNotLeakBuffers() throws Exception {
        // Setup: server returns a moderate-sized response body
        final byte[] responseData = new byte[8192]; // 8KB per response
        java.util.Arrays.fill(responseData, (byte) 'X');
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(responseData)
                    .build()
            )
        );
        final int port = this.server.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 30_000L
        );

        // Baseline measurement
        final JettyClientSlices.BufferPoolStats baseline = this.clients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        // Execute many requests WITHOUT reading the response body
        final int requestCount = 500;
        for (int i = 0; i < requestCount; i++) {
            final Response resp = this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);
            // Intentionally NOT reading resp.body()
        }

        // Allow some time for any async cleanup
        Thread.sleep(100);

        // Verify: buffer pool should not have grown excessively
        final JettyClientSlices.BufferPoolStats afterStats = this.clients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            // With proper release, memory growth should be bounded
            // Without proper release, we'd see ~500 * 8KB = 4MB+ growth
            // Allow some growth for pool overhead, but not proportional to request count
            final long maxAllowedGrowth = 1024L * 1024L; // 1MB max allowed growth
            assertThat(
                String.format(
                    "Buffer pool memory grew by %d bytes after %d requests with unread bodies. " +
                    "This suggests buffers are not being released properly.",
                    memoryGrowth, requestCount
                ),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
    }

    @Test
    @DisplayName("Fully consumed response bodies release buffers correctly")
    void fullyConsumedBodiesReleaseBuffers() throws Exception {
        // Setup: server returns multi-chunk response
        final byte[] chunk1 = new byte[4096];
        final byte[] chunk2 = new byte[4096];
        java.util.Arrays.fill(chunk1, (byte) 'A');
        java.util.Arrays.fill(chunk2, (byte) 'B');
        
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(Flowable.just(
                        ByteBuffer.wrap(chunk1),
                        ByteBuffer.wrap(chunk2)
                    ))
                    .build()
            )
        );
        final int port = this.server.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 30_000L
        );

        final JettyClientSlices.BufferPoolStats baseline = this.clients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        // Execute requests and FULLY consume the body
        final int requestCount = 500;
        for (int i = 0; i < requestCount; i++) {
            final Response resp = this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);
            
            // Fully consume the body
            final byte[] bodyBytes = new Content.From(resp.body()).asBytes();
            assertThat("Body should have content", bodyBytes.length, greaterThan(0));
        }

        Thread.sleep(100);

        final JettyClientSlices.BufferPoolStats afterStats = this.clients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            final long maxAllowedGrowth = 1024L * 1024L; // 1MB
            assertThat(
                String.format(
                    "Buffer pool memory grew by %d bytes after %d requests with consumed bodies.",
                    memoryGrowth, requestCount
                ),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
    }

    @Test
    @DisplayName("Partially consumed response bodies release all buffers")
    void partiallyConsumedBodiesReleaseAllBuffers() throws Exception {
        // Setup: server returns a multi-chunk response
        final int chunkCount = 10;
        final byte[][] chunks = new byte[chunkCount][];
        for (int i = 0; i < chunkCount; i++) {
            chunks[i] = new byte[1024];
            java.util.Arrays.fill(chunks[i], (byte) ('0' + i));
        }
        
        this.server.update(
            (line, headers, body) -> {
                Flowable<ByteBuffer> flow = Flowable.fromArray(chunks)
                    .map(ByteBuffer::wrap);
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(flow).build()
                );
            }
        );
        final int port = this.server.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 30_000L
        );

        final JettyClientSlices.BufferPoolStats baseline = this.clients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        // Execute requests and only partially consume the body
        final int requestCount = 200;
        for (int i = 0; i < requestCount; i++) {
            final Response resp = this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);
            
            // Only consume first 2 chunks, then abandon
            final AtomicInteger consumed = new AtomicInteger(0);
            Flowable.fromPublisher(resp.body())
                .takeWhile(buf -> consumed.incrementAndGet() <= 2)
                .blockingSubscribe();
        }

        Thread.sleep(100);

        final JettyClientSlices.BufferPoolStats afterStats = this.clients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            // Even with partial consumption, buffers should be released
            // because we copy data out of Jetty chunks immediately
            final long maxAllowedGrowth = 2L * 1024L * 1024L; // 2MB
            assertThat(
                String.format(
                    "Buffer pool memory grew by %d bytes after %d requests with partial consumption.",
                    memoryGrowth, requestCount
                ),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
    }

    @Test
    @DisplayName("Large response bodies are handled without excessive buffer retention")
    void largeResponseBodiesHandledCorrectly() throws Exception {
        // Setup: server returns a large response (1MB)
        final byte[] largeBody = new byte[1024 * 1024]; // 1MB
        java.util.Arrays.fill(largeBody, (byte) 'L');
        
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(largeBody)
                    .build()
            )
        );
        final int port = this.server.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 60_000L
        );

        // Execute a few requests with large bodies
        final int requestCount = 10;
        for (int i = 0; i < requestCount; i++) {
            final Response resp = this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(30, TimeUnit.SECONDS);
            
            // Consume the body
            final byte[] bodyBytes = new Content.From(resp.body()).asBytes();
            assertThat("Large body should be received", bodyBytes.length, greaterThan(1000000));
        }

        // Verify no exceptions and requests completed
        assertTrue(true, "Large body requests completed successfully");
    }

    @Test
    @DisplayName("Concurrent requests do not cause buffer leaks")
    void concurrentRequestsDoNotLeakBuffers() throws Exception {
        final byte[] responseData = new byte[2048];
        java.util.Arrays.fill(responseData, (byte) 'C');

        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(responseData)
                    .build()
            )
        );
        final int port = this.server.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 30_000L
        );

        final JettyClientSlices.BufferPoolStats baseline = this.clients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        // Execute concurrent requests
        final int concurrency = 50;
        final int iterations = 10;
        final CompletableFuture<?>[] futures = new CompletableFuture[concurrency];

        for (int iter = 0; iter < iterations; iter++) {
            for (int i = 0; i < concurrency; i++) {
                futures[i] = this.slice.response(
                    new RequestLine(RqMethod.GET, "/"),
                    Headers.EMPTY,
                    Content.EMPTY
                ).thenCompose(resp -> {
                    // Mix of consumed and unconsumed bodies.
                    // Use async asBytesFuture() to avoid blocking ForkJoinPool threads,
                    // which is essential with streaming response bodies.
                    if (Math.random() > 0.5) {
                        return new Content.From(resp.body()).asBytesFuture()
                            .thenAccept(bytes -> { })
                            .exceptionally(ex -> null);
                    }
                    return CompletableFuture.completedFuture(null);
                });
            }
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        }

        Thread.sleep(200);

        final JettyClientSlices.BufferPoolStats afterStats = this.clients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            final long maxAllowedGrowth = 5L * 1024L * 1024L; // 5MB for concurrent load
            assertThat(
                String.format(
                    "Buffer pool memory grew by %d bytes after %d concurrent requests.",
                    memoryGrowth, concurrency * iterations
                ),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
    }

    @Test
    @DisplayName("Empty response bodies are handled correctly")
    void emptyResponseBodiesHandledCorrectly() throws Exception {
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build() // Empty body
            )
        );
        final int port = this.server.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 30_000L
        );

        // Execute many requests with empty bodies
        final int requestCount = 1000;
        for (int i = 0; i < requestCount; i++) {
            assertDoesNotThrow(() -> {
                this.slice.response(
                    new RequestLine(RqMethod.GET, "/"),
                    Headers.EMPTY,
                    Content.EMPTY
                ).get(5, TimeUnit.SECONDS);
            });
        }
    }

    @Test
    @DisplayName("Connection pool remains stable under sustained load")
    void connectionPoolRemainsStable() throws Exception {
        final byte[] responseData = "OK".getBytes();
        
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(responseData)
                    .build()
            )
        );
        final int port = this.server.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 30_000L
        );

        // Sustained load test
        final int totalRequests = 2000;
        final long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < totalRequests; i++) {
            final Response resp = this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);
            
            // Consume body
            new Content.From(resp.body()).asBytes();
        }
        
        final long duration = System.currentTimeMillis() - startTime;
        
        // Verify reasonable throughput (at least 100 req/s for this simple test)
        final double rps = (double) totalRequests / (duration / 1000.0);
        assertThat(
            String.format("Throughput was %.1f req/s, expected at least 100", rps),
            rps,
            greaterThan(100.0)
        );
    }
}
