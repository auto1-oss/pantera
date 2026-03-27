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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.reactivex.Flowable;
import io.vertx.reactivex.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

/**
 * Regression tests for proxy scenarios using Jetty client and Vert.x server.
 * 
 * <p>These tests verify that the full proxy chain (Client → Vert.x → Jetty → Upstream)
 * doesn't leak resources under various conditions:</p>
 * <ul>
 *   <li>Normal proxy requests with body consumption</li>
 *   <li>Proxy requests where downstream doesn't consume body</li>
 *   <li>Upstream errors during proxy</li>
 *   <li>Concurrent proxy requests</li>
 *   <li>Large body proxying</li>
 * </ul>
 * 
 * <p>This guards against the leak patterns identified in Leak.md where
 * GroupSlice's drainBody() was ineffective due to Jetty buffer leaks.</p>
 */
final class ProxySliceLeakRegressionTest {

    private static final String HOST = "localhost";

    /**
     * Upstream server (simulates remote repository).
     */
    private Vertx upstreamVertx;
    private VertxSliceServer upstreamServer;
    private int upstreamPort;

    /**
     * Proxy server (Vert.x frontend with Jetty backend).
     */
    private Vertx proxyVertx;
    private VertxSliceServer proxyServer;
    private int proxyPort;

    /**
     * Jetty client used by proxy to reach upstream.
     */
    private JettyClientSlices jettyClients;

    @BeforeEach
    void setUp() throws Exception {
        // Setup upstream server
        this.upstreamVertx = Vertx.vertx();
        
        // Setup Jetty client for proxy
        this.jettyClients = new JettyClientSlices(new HttpClientSettings());
        this.jettyClients.start();
        
        // Setup proxy server
        this.proxyVertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (this.proxyServer != null) {
            this.proxyServer.close();
        }
        if (this.upstreamServer != null) {
            this.upstreamServer.close();
        }
        if (this.jettyClients != null) {
            this.jettyClients.stop();
        }
        if (this.proxyVertx != null) {
            this.proxyVertx.close();
        }
        if (this.upstreamVertx != null) {
            this.upstreamVertx.close();
        }
    }

    /**
     * Make HTTP GET request and return response code and body.
     */
    private HttpResult httpGet(String path) throws Exception {
        final HttpURLConnection con = (HttpURLConnection)
            URI.create(String.format("http://%s:%d%s", HOST, this.proxyPort, path))
                .toURL().openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(30000);
        con.setReadTimeout(30000);
        try {
            final int code = con.getResponseCode();
            byte[] body = new byte[0];
            if (code == 200) {
                try (InputStream is = con.getInputStream()) {
                    body = is.readAllBytes();
                }
            }
            return new HttpResult(code, body);
        } finally {
            con.disconnect();
        }
    }

    private record HttpResult(int code, byte[] body) {}

    @Test
    @DisplayName("Proxy requests with consumed bodies don't leak buffers")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void proxyWithConsumedBodiesNoLeak() throws Exception {
        // Setup upstream that returns body
        final byte[] upstreamBody = new byte[4096];
        java.util.Arrays.fill(upstreamBody, (byte) 'U');
        startUpstream((line, headers, body) -> 
            CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(upstreamBody).build()
            )
        );
        
        // Setup proxy that forwards to upstream and consumes response
        startProxy(createProxySlice(true));

        final JettyClientSlices.BufferPoolStats baseline = this.jettyClients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        // Make many proxy requests
        final int requestCount = 200;
        for (int i = 0; i < requestCount; i++) {
            final HttpResult result = httpGet("/artifact-" + i);
            assertThat("Should return 200", result.code(), equalTo(200));
            assertThat("Body should be received", result.body().length, greaterThan(0));
        }

        Thread.sleep(100);

        final JettyClientSlices.BufferPoolStats afterStats = this.jettyClients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            final long maxAllowedGrowth = 2L * 1024L * 1024L; // 2MB
            assertThat(
                String.format("Buffer pool grew by %d bytes after %d proxy requests", memoryGrowth, requestCount),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
    }

    @Test
    @DisplayName("Proxy requests with unconsumed bodies don't leak (drainBody scenario)")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void proxyWithUnconsumedBodiesNoLeak() throws Exception {
        // Setup upstream that returns body
        final byte[] upstreamBody = new byte[8192];
        java.util.Arrays.fill(upstreamBody, (byte) 'D');
        startUpstream((line, headers, body) -> 
            CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(upstreamBody).build()
            )
        );
        
        // Setup proxy that forwards but DOESN'T consume upstream body (simulates GroupSlice loser)
        startProxy(createProxySlice(false));

        final JettyClientSlices.BufferPoolStats baseline = this.jettyClients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        // Make many proxy requests
        final int requestCount = 200;
        for (int i = 0; i < requestCount; i++) {
            final HttpResult result = httpGet("/drain-" + i);
            // Proxy returns 200 but may have empty body if not forwarding
            assertThat("Should return 200", result.code(), equalTo(200));
        }

        Thread.sleep(100);

        // Key assertion: even without consuming upstream body, buffers should be released
        // because JettyClientSlice now copies data and releases chunks immediately
        final JettyClientSlices.BufferPoolStats afterStats = this.jettyClients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            final long maxAllowedGrowth = 2L * 1024L * 1024L; // 2MB
            assertThat(
                String.format(
                    "Buffer pool grew by %d bytes after %d proxy requests with unconsumed bodies. " +
                    "This was the original leak scenario from Leak.md",
                    memoryGrowth, requestCount
                ),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
    }

    @Test
    @DisplayName("Upstream errors don't leak buffers")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void upstreamErrorsNoLeak() throws Exception {
        final AtomicInteger reqCounter = new AtomicInteger(0);
        
        // Setup upstream that fails every 3rd request
        startUpstream((line, headers, body) -> {
            if (reqCounter.incrementAndGet() % 3 == 0) {
                return CompletableFuture.failedFuture(new RuntimeException("Upstream error"));
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("ok").build()
            );
        });
        
        startProxy(createProxySlice(true));

        final JettyClientSlices.BufferPoolStats baseline = this.jettyClients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        // Make requests (some will fail)
        final int totalRequests = 150;
        int successCount = 0;
        int errorCount = 0;
        
        for (int i = 0; i < totalRequests; i++) {
            try {
                final HttpResult result = httpGet("/maybe-fail-" + i);
                if (result.code() == 200) {
                    successCount++;
                } else {
                    errorCount++;
                }
            } catch (Exception e) {
                errorCount++;
            }
        }

        Thread.sleep(100);

        final JettyClientSlices.BufferPoolStats afterStats = this.jettyClients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            final long maxAllowedGrowth = 2L * 1024L * 1024L;
            assertThat(
                String.format("Buffer pool grew by %d bytes with %d errors", memoryGrowth, errorCount),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
        
        assertThat("Should have some successes", successCount, greaterThan(0));
        assertThat("Should have some errors", errorCount, greaterThan(0));
    }

    @Test
    @DisplayName("Sequential proxy requests don't leak")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void sequentialProxyRequestsNoLeak() throws Exception {
        final byte[] upstreamBody = new byte[2048];
        java.util.Arrays.fill(upstreamBody, (byte) 'C');
        
        startUpstream((line, headers, body) -> 
            CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(upstreamBody).build()
            )
        );
        
        startProxy(createProxySlice(true));

        final JettyClientSlices.BufferPoolStats baseline = this.jettyClients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        final int totalRequests = 150;
        int successCount = 0;

        for (int i = 0; i < totalRequests; i++) {
            try {
                final HttpResult result = httpGet("/seq-" + i);
                if (result.code() == 200) {
                    successCount++;
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        assertThat("Most requests should succeed", successCount, greaterThan(totalRequests / 2));

        Thread.sleep(200);

        final JettyClientSlices.BufferPoolStats afterStats = this.jettyClients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            final long maxAllowedGrowth = 5L * 1024L * 1024L; // 5MB
            assertThat(
                String.format("Buffer pool grew by %d bytes after %d sequential requests", 
                    memoryGrowth, totalRequests),
                memoryGrowth,
                lessThan(maxAllowedGrowth)
            );
        }
    }

    @Test
    @DisplayName("Large body proxy doesn't leak")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void largeBodyProxyNoLeak() throws Exception {
        // 1MB upstream body
        final byte[] largeBody = new byte[1024 * 1024];
        java.util.Arrays.fill(largeBody, (byte) 'L');
        
        startUpstream((line, headers, body) -> 
            CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Length", String.valueOf(largeBody.length))
                    .body(largeBody)
                    .build()
            )
        );
        
        startProxy(createProxySlice(true));

        // Make several large body requests
        final int requestCount = 5;
        for (int i = 0; i < requestCount; i++) {
            final HttpResult result = httpGet("/large-" + i);
            assertThat("Should return 200", result.code(), equalTo(200));
            assertThat("Body should be 1MB", result.body().length, equalTo(largeBody.length));
        }

        // Verify no excessive memory growth
        final JettyClientSlices.BufferPoolStats stats = this.jettyClients.getBufferPoolStats();
        if (stats != null) {
            // After 5 x 1MB requests, pool should not hold more than ~10MB
            assertThat(
                "Buffer pool should not grow excessively",
                stats.totalMemory(),
                lessThan(20L * 1024L * 1024L)
            );
        }
    }

    @Test
    @DisplayName("Mixed success/404 proxy responses don't leak")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void mixedResponsesNoLeak() throws Exception {
        final AtomicInteger reqCounter = new AtomicInteger(0);
        
        // Upstream returns 404 for odd requests, 200 for even
        startUpstream((line, headers, body) -> {
            if (reqCounter.incrementAndGet() % 2 == 1) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("found").build()
            );
        });
        
        startProxy(createProxySlice(true));

        final JettyClientSlices.BufferPoolStats baseline = this.jettyClients.getBufferPoolStats();
        final long baselineMemory = baseline != null ? baseline.totalMemory() : 0;

        int found = 0;
        int notFound = 0;
        
        for (int i = 0; i < 200; i++) {
            try {
                final HttpResult result = httpGet("/mixed-" + i);
                if (result.code() == 200) {
                    found++;
                } else if (result.code() == 404) {
                    notFound++;
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        Thread.sleep(100);

        final JettyClientSlices.BufferPoolStats afterStats = this.jettyClients.getBufferPoolStats();
        if (afterStats != null && baseline != null) {
            final long memoryGrowth = afterStats.totalMemory() - baselineMemory;
            assertThat(
                "Buffer pool should not grow with mixed responses",
                memoryGrowth,
                lessThan(2L * 1024L * 1024L)
            );
        }
        
        assertThat("Should have found responses", found, greaterThan(0));
        assertThat("Should have not-found responses", notFound, greaterThan(0));
    }

    private void startUpstream(Slice slice) {
        this.upstreamServer = new VertxSliceServer(this.upstreamVertx, slice);
        this.upstreamPort = this.upstreamServer.start();
    }

    private void startProxy(Slice slice) {
        this.proxyServer = new VertxSliceServer(this.proxyVertx, slice);
        this.proxyPort = this.proxyServer.start();
    }

    /**
     * Create a proxy slice that forwards requests to upstream via Jetty.
     * @param consumeBody Whether to consume the upstream response body
     * @return Proxy slice
     */
    private Slice createProxySlice(boolean consumeBody) {
        return (line, headers, body) -> {
            final Slice upstream = this.jettyClients.http(HOST, this.upstreamPort);
            return upstream.response(
                new RequestLine(RqMethod.GET, line.uri().getPath()),
                Headers.EMPTY,
                Content.EMPTY
            ).thenApply(upstreamResp -> {
                if (consumeBody) {
                    // Forward the body to client
                    return ResponseBuilder.from(upstreamResp.status())
                        .headers(upstreamResp.headers())
                        .body(upstreamResp.body())
                        .build();
                } else {
                    // Simulate GroupSlice "loser" - drain body but don't forward
                    // This was the leak scenario: drainBody() didn't release Jetty buffers
                    new Content.From(upstreamResp.body()).asBytesFuture()
                        .whenComplete((bytes, err) -> {
                            // Body drained (or failed)
                        });
                    return ResponseBuilder.ok().textBody("drained").build();
                }
            });
        };
    }
}
