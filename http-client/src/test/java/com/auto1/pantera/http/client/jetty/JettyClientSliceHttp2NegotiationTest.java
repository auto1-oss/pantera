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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.client.HttpServer;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.metrics.MicrometerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.http.HttpServerOptions;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

/**
 * Verifies that {@link JettyClientSlice} increments
 * {@code pantera_http2_negotiated_total{upstream_host,version}} on every
 * upstream response received.
 *
 * <p>The test server is plain HTTP/1.1 (no TLS, no ALPN) so the expected
 * label is {@code version="http/1.1"}. The full h2 negotiation path is
 * exercised by the live smoke check against Maven Central in
 * {@code docs/superpowers/plans/2026-05-04-pantera-http2-prefetch.md}
 * Task 10 step 5.
 */
final class JettyClientSliceHttp2NegotiationTest {

    private final HttpServer server = new HttpServer();
    private HttpClient client;
    private JettyClientSlice slice;
    private MeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        // MicrometerMetrics is a process-wide singleton; init it with a
        // SimpleMeterRegistry if no other test got there first. Either way,
        // we read the active registry back via getInstance() so the
        // assertion targets whichever one is in effect.
        MicrometerMetrics.initialize(new SimpleMeterRegistry());
        this.registry = MicrometerMetrics.getInstance().getRegistry();
        final int port = this.server.start(new HttpServerOptions().setPort(0));
        this.client = new HttpClient();
        this.client.start();
        this.slice = new JettyClientSlice(this.client, false, "localhost", port, 0L);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.server.stop();
        this.client.stop();
    }

    @Test
    void incrementsCounterOnHttp1Response() {
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(ResponseBuilder.ok().build())
        );
        final double before = counterValue(this.registry, "localhost", "http/1.1");
        this.slice.response(
            new RequestLine(RqMethod.GET, "/some/path"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        final double after = counterValue(this.registry, "localhost", "http/1.1");
        Assertions.assertEquals(
            before + 1.0,
            after,
            0.0,
            "pantera.http2.negotiated counter should increment by exactly 1 per response"
        );
    }

    @Test
    void usesAlpnCanonicalHttp1LabelNotJettyEnumString() {
        this.server.update(
            (line, headers, body) -> CompletableFuture.completedFuture(ResponseBuilder.ok().build())
        );
        this.slice.response(
            new RequestLine(RqMethod.GET, "/p"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        // Counter must exist with the ALPN canonical label "http/1.1",
        // NOT Jetty's HttpVersion.HTTP_1_1.asString() = "HTTP/1.1".
        final Counter alpn = Search.in(this.registry)
            .name("pantera.http2.negotiated")
            .tag("version", "http/1.1")
            .tag("upstream_host", "localhost")
            .counter();
        Assertions.assertNotNull(alpn, "expected counter labelled version=\"http/1.1\"");
        final Counter wrong = Search.in(this.registry)
            .name("pantera.http2.negotiated")
            .tag("version", "HTTP/1.1")
            .counter();
        Assertions.assertNull(wrong, "must not emit Jetty's enum-string \"HTTP/1.1\"");
    }

    /**
     * Read the current value of the counter for the given (host, version)
     * tuple. Returns 0.0 when the counter has not been registered yet.
     */
    private static double counterValue(
        final MeterRegistry registry, final String host, final String version
    ) {
        final Counter counter = Search.in(registry)
            .name("pantera.http2.negotiated")
            .tag("upstream_host", host)
            .tag("version", version)
            .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
