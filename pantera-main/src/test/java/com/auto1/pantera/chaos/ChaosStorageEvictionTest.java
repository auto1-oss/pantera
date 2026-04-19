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
package com.auto1.pantera.chaos;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.group.GroupResolver;
import com.auto1.pantera.group.MemberSlice;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos test: simulate storage eviction between index lookup and read.
 *
 * <p>Reproduces the TOCTOU race described in WI-04 / A11: the artifact
 * index says the artifact exists in a hosted member, but by the time the
 * storage read happens, the artifact has been evicted (returns 404).
 *
 * <p>Verifies that {@link GroupResolver} falls through to proxy fanout
 * and serves the artifact from an upstream proxy member, rather than
 * returning a 500 or stale 404 to the client.
 *
 * <p>Uses in-memory/mock infrastructure only; no Docker required.
 *
 * @since 2.2.0
 */
@Tag("Chaos")
final class ChaosStorageEvictionTest {

    private static final String GROUP = "chaos-eviction-group";
    private static final String REPO_TYPE = "maven-group";
    private static final String HOSTED = "libs-release";
    private static final String PROXY = "central-proxy";
    private static final String JAR_PATH =
        "/com/example/artifact/1.0/artifact-1.0.jar";

    /**
     * Index says artifact is in HOSTED, but HOSTED returns 404 (evicted).
     * GroupResolver must fall through to proxy fanout and serve from PROXY.
     */
    @Test
    void eviction_indexHit_hostedEvicted_proxyServes() {
        final ArtifactIndex idx = nopIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCalls = new AtomicInteger(0);
        final AtomicInteger proxyCalls = new AtomicInteger(0);

        // Hosted: always 404 (simulates eviction after index lookup)
        final Slice evictedHosted = (line, headers, body) -> {
            hostedCalls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        };

        // Proxy: returns 200 (upstream still has the artifact)
        final Slice okProxy = (line, headers, body) -> {
            proxyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };

        final List<MemberSlice> members = List.of(
            new MemberSlice(HOSTED, evictedHosted, false),
            new MemberSlice(PROXY, okProxy, true)
        );

        final GroupResolver resolver = new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.of(idx),
            REPO_TYPE,
            Set.of(PROXY),
            buildNegativeCache(),
            java.util.concurrent.ForkJoinPool.commonPool()
        );

        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).orTimeout(10, TimeUnit.SECONDS).join();

        assertEquals(200, resp.status().code(),
            "TOCTOU eviction must fall through to proxy and return 200");
        assertTrue(hostedCalls.get() >= 1,
            "Hosted member must be queried first (index hit)");
        assertTrue(proxyCalls.get() >= 1,
            "Proxy must be queried after hosted 404 (TOCTOU fallthrough)");
    }

    /**
     * Repeated TOCTOU eviction: run the scenario 50 times to confirm
     * deterministic behavior under race conditions.
     */
    @Test
    void eviction_repeated_alwaysFallsThrough() {
        final ArtifactIndex idx = nopIndex(Optional.of(List.of(HOSTED)));
        final Slice evictedHosted = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        final Slice okProxy = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());

        final List<MemberSlice> members = List.of(
            new MemberSlice(HOSTED, evictedHosted, false),
            new MemberSlice(PROXY, okProxy, true)
        );

        final GroupResolver resolver = new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.of(idx),
            REPO_TYPE,
            Set.of(PROXY),
            buildNegativeCache(),
            java.util.concurrent.ForkJoinPool.commonPool()
        );

        int successCount = 0;
        for (int i = 0; i < 50; i++) {
            final Response resp = resolver.response(
                new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
            ).orTimeout(10, TimeUnit.SECONDS).join();
            if (resp.status().code() == 200) {
                successCount++;
            }
        }

        assertEquals(50, successCount,
            "All 50 TOCTOU-eviction iterations must succeed via proxy fallthrough");
    }

    /**
     * Intermittent eviction: hosted member alternates between 200 and 404.
     * When hosted returns 404, proxy must fill in. When hosted returns 200,
     * proxy must NOT be queried.
     */
    @Test
    void eviction_intermittent_proxyOnlyOnEviction() {
        final ArtifactIndex idx = nopIndex(Optional.of(List.of(HOSTED)));
        final AtomicBoolean evicted = new AtomicBoolean(false);
        final AtomicInteger proxyCalls = new AtomicInteger(0);

        final Slice intermittentHosted = (line, headers, body) -> {
            if (evicted.get()) {
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };

        final Slice trackingProxy = (line, headers, body) -> {
            proxyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };

        final List<MemberSlice> members = List.of(
            new MemberSlice(HOSTED, intermittentHosted, false),
            new MemberSlice(PROXY, trackingProxy, true)
        );

        final GroupResolver resolver = new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.of(idx),
            REPO_TYPE,
            Set.of(PROXY),
            buildNegativeCache(),
            java.util.concurrent.ForkJoinPool.commonPool()
        );

        // Round 1: hosted is available -- proxy should NOT be called
        evicted.set(false);
        proxyCalls.set(0);
        final Response r1 = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).orTimeout(10, TimeUnit.SECONDS).join();
        assertEquals(200, r1.status().code());
        assertEquals(0, proxyCalls.get(),
            "Proxy must NOT be called when hosted serves successfully");

        // Round 2: hosted is evicted -- proxy MUST be called
        evicted.set(true);
        proxyCalls.set(0);
        final Response r2 = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).orTimeout(10, TimeUnit.SECONDS).join();
        assertEquals(200, r2.status().code(),
            "Eviction must fall through to proxy");
        assertTrue(proxyCalls.get() >= 1,
            "Proxy must be called when hosted returns 404 (eviction)");
    }

    // ---- Helpers ----

    private static NegativeCache buildNegativeCache() {
        final NegativeCacheConfig config = new NegativeCacheConfig(
            Duration.ofMinutes(5),
            10_000,
            false,
            NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L1_TTL,
            NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L2_TTL
        );
        return new NegativeCache("group-negative", GROUP, config);
    }

    private static ArtifactIndex nopIndex(final Optional<List<String>> result) {
        return new ArtifactIndex() {
            @Override
            public CompletableFuture<Void> index(final ArtifactDocument doc) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> remove(final String rn, final String ap) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<SearchResult> search(
                final String q, final int max, final int off
            ) {
                return CompletableFuture.completedFuture(SearchResult.EMPTY);
            }

            @Override
            public CompletableFuture<List<String>> locate(final String path) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                return CompletableFuture.completedFuture(result);
            }

            @Override
            public void close() {
            }
        };
    }
}
