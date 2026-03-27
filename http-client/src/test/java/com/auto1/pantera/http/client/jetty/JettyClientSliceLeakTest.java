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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests checking for connection and buffer leaks in {@link JettyClientSlice}.
 * 
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Connections are properly returned to the pool when response bodies are not read</li>
 *   <li>Jetty Content.Chunk buffers are released regardless of body consumption</li>
 *   <li>The ArrayByteBufferPool does not grow unboundedly</li>
 * </ul>
 */
final class JettyClientSliceLeakTest {

    /**
     * HTTP server used in tests.
     */
    private HttpServer server;

    /**
     * Jetty client slices with instrumentation.
     */
    private JettyClientSlices clients;

    /**
     * HTTP client slice being tested.
     */
    private JettyClientSlice slice;

    @BeforeEach
    void setUp() throws Exception {
        this.server = new HttpServer();
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("data").build()
            )
        );
        final int port = this.server.start();
        this.clients = new JettyClientSlices(new HttpClientSettings());
        this.clients.start();
        this.slice = new JettyClientSlice(
            this.clients.httpClient(), false, "localhost", port, 30_000L
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.server != null) {
            this.server.stop();
        }
        if (this.clients != null) {
            this.clients.stop();
        }
    }

    @Test
    @DisplayName("Connections are reused when response body is not read")
    void shouldNotLeakConnectionsIfBodyNotRead() throws Exception {
        final int total = 1025;
        for (int count = 0; count < total; count += 1) {
            this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(1, TimeUnit.SECONDS);
        }
        // If we get here without timeout/exception, connections are being reused
    }

    @Test
    @DisplayName("Buffer pool does not grow when response bodies are not read")
    void shouldNotLeakBuffersIfBodyNotRead() throws Exception {
        // Get baseline buffer pool stats
        final JettyClientSlices.BufferPoolStats baseline = this.clients.getBufferPoolStats();
        assertThat("Buffer pool stats should be available", baseline, notNullValue());
        final long baselineMemory = baseline.totalMemory();

        // Execute many requests without reading the body
        final int total = 500;
        for (int count = 0; count < total; count += 1) {
            final Response resp = this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(1, TimeUnit.SECONDS);
            // Intentionally NOT reading resp.body()
        }

        // Allow async cleanup
        Thread.sleep(50);

        // Check buffer pool hasn't grown excessively
        final JettyClientSlices.BufferPoolStats afterStats = this.clients.getBufferPoolStats();
        assertThat("Buffer pool stats should still be available", afterStats, notNullValue());
        
        final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
        // With proper chunk release, growth should be minimal (pool overhead only)
        // Without proper release, we'd see ~500 * body_size growth
        final long maxAllowedGrowth = 512L * 1024L; // 512KB max
        assertThat(
            String.format(
                "Buffer pool grew by %d bytes after %d requests with unread bodies. " +
                "Expected < %d bytes. This indicates a buffer leak.",
                memoryGrowth, total, maxAllowedGrowth
            ),
            memoryGrowth,
            lessThan(maxAllowedGrowth)
        );
    }

    @Test
    @DisplayName("Buffer pool does not grow when response bodies are fully consumed")
    void shouldNotLeakBuffersWhenBodyIsConsumed() throws Exception {
        final JettyClientSlices.BufferPoolStats baseline = this.clients.getBufferPoolStats();
        assertThat("Buffer pool stats should be available", baseline, notNullValue());
        final long baselineMemory = baseline.totalMemory();

        final int total = 500;
        for (int count = 0; count < total; count += 1) {
            final Response resp = this.slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(1, TimeUnit.SECONDS);
            // Fully consume the body
            new Content.From(resp.body()).asBytes();
        }

        Thread.sleep(50);

        final JettyClientSlices.BufferPoolStats afterStats = this.clients.getBufferPoolStats();
        final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
        final long maxAllowedGrowth = 512L * 1024L;
        assertThat(
            String.format(
                "Buffer pool grew by %d bytes after %d requests with consumed bodies.",
                memoryGrowth, total
            ),
            memoryGrowth,
            lessThan(maxAllowedGrowth)
        );
    }
}
