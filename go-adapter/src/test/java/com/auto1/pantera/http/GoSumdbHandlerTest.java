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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.core.Is;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for {@link GoSumdbHandler} — the checksum-database (sumdb) proxy
 * (S6, WS4-go.4).
 *
 * @since 2.3.0
 */
final class GoSumdbHandlerTest {

    private ScriptedSlice upstream;
    private GoSumdbHandler handler;

    @BeforeEach
    void setUp() {
        this.upstream = new ScriptedSlice();
        final Storage storage = new InMemoryStorage();
        final Cache cache = new FromStorageCache(storage);
        this.handler = new GoSumdbHandler(this.upstream, cache, "go-proxy-test");
    }

    @Test
    void matchesOnlySumdbPaths() {
        assertThat(
            this.handler.matches("/sumdb/sum.golang.org/supported"),
            new Is<>(new IsEqual<>(true))
        );
        assertThat(
            this.handler.matches("/sumdb/sum.golang.org/lookup/example.com/foo@v1.0.0"),
            new Is<>(new IsEqual<>(true))
        );
        assertThat(
            this.handler.matches("/example.com/foo/@v/list"),
            new Is<>(new IsEqual<>(false))
        );
        assertThat(this.handler.matches("/sumdb/"), new Is<>(new IsEqual<>(false)));
        assertThat(this.handler.matches(null), new Is<>(new IsEqual<>(false)));
    }

    @Test
    void lookupIsCachedImmutablyAfterFirstFetch() throws Exception {
        final String path = "/sumdb/sum.golang.org/lookup/example.com/foo@v1.0.0";
        this.upstream.put(path, "example.com/foo v1.0.0\nh1:abc123=\n");

        final Response first = this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        assertThat(first.status().success(), new Is<>(new IsEqual<>(true)));
        assertThat(
            new String(bodyOf(first), StandardCharsets.UTF_8),
            new IsEqual<>("example.com/foo v1.0.0\nh1:abc123=\n")
        );
        assertThat(
            "first request must hit upstream exactly once",
            this.upstream.hits(path), new IsEqual<>(1)
        );

        final Response second = this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        assertThat(
            new String(bodyOf(second), StandardCharsets.UTF_8),
            new IsEqual<>("example.com/foo v1.0.0\nh1:abc123=\n")
        );
        assertThat(
            "second request must be served from the immutable cache — zero extra upstream calls",
            this.upstream.hits(path), new IsEqual<>(1)
        );
    }

    @Test
    void tileIsCachedImmutablyAfterFirstFetch() throws Exception {
        final String path = "/sumdb/sum.golang.org/tile/8/0/001";
        this.upstream.put(path, "tile-bytes");

        this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        assertThat(
            "tile responses are content-addressed/immutable — one upstream call total",
            this.upstream.hits(path), new IsEqual<>(1)
        );
    }

    @Test
    void supportedIsProbedLiveEveryRequestNeverCached() throws Exception {
        final String path = "/sumdb/sum.golang.org/supported";
        this.upstream.put(path, "");

        this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        final Response second = this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        assertThat(second.status().success(), new Is<>(new IsEqual<>(true)));
        assertThat(
            "supported must be probed live on every request, never cached",
            this.upstream.hits(path), new IsEqual<>(2)
        );
    }

    @Test
    void supportedProbeFailureReturnsNotFound() throws Exception {
        final String path = "/sumdb/unsupported.example.com/supported";
        // No scripted body -> upstream 404s.
        final Response resp = this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        assertThat(resp.status().code(), new IsEqual<>(404));
    }

    @Test
    void lookupMissWithUpstreamDownForwardsBadGatewayAndDoesNotCache() throws Exception {
        final String path = "/sumdb/sum.golang.org/lookup/example.com/bar@v1.0.0";
        this.upstream.fail(true);
        final Response resp = this.handler.handle(new RequestLine(RqMethod.GET, path)).get();
        assertThat(resp.status().code(), new IsEqual<>(502));
    }

    // ===== Helpers =====

    private static byte[] bodyOf(final Response resp) throws Exception {
        return resp.body().asBytesFuture().get();
    }

    /** Minimal scripted {@link Slice}: serves canned bodies for exact paths. */
    private static final class ScriptedSlice implements Slice {
        private final Map<String, byte[]> script = new HashMap<>();
        private final Map<String, AtomicInteger> hits = new HashMap<>();
        private volatile boolean failing;

        void put(final String path, final String body) {
            this.script.put(path, body.getBytes(StandardCharsets.UTF_8));
        }

        void fail(final boolean value) {
            this.failing = value;
        }

        int hits(final String path) {
            final AtomicInteger counter = this.hits.get(path);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            this.hits.computeIfAbsent(path, k -> new AtomicInteger(0)).incrementAndGet();
            if (this.failing) {
                return CompletableFuture.failedFuture(new RuntimeException("upstream down"));
            }
            final byte[] content = this.script.get(path);
            if (content == null) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(content).build()
            );
        }
    }
}
