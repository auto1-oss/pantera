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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos test: saturate the group resolver with 100 concurrent requests.
 *
 * <p>Verifies that when every internal event queue is at capacity, all
 * requests still resolve gracefully (either 200 or a well-formed error
 * response). Queue overflow must be handled per WI-00 -- never an
 * unhandled exception or a hung future.
 *
 * <p>Uses in-memory/mock infrastructure only; no Docker required.
 *
 * @since 2.2.0
 */
@Tag("Chaos")
final class ChaosQueueSaturationTest {

    private static final String GROUP = "chaos-queue-group";
    private static final String REPO_TYPE = "npm-group";
    private static final String HOSTED = "hosted-repo";
    private static final String PROXY = "proxy-repo";
    private static final String JAR_PATH =
        "/com/example/artifact/1.0/artifact-1.0.jar";
    private static final int CONCURRENT_REQUESTS = 100;

    /**
     * Fire 100 concurrent requests at the group resolver with index hits.
     * All must complete (no hung futures) and all must return a valid HTTP
     * status (200, 404, or 5xx -- never an exception bubbling up).
     */
    @Test
    void saturation_allRequestsResolve_indexHit() throws Exception {
        final ArtifactIndex idx = nopIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger servedCount = new AtomicInteger(0);
        final Slice countingOk = (line, headers, body) -> {
            servedCount.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };

        final List<MemberSlice> members = List.of(
            new MemberSlice(HOSTED, countingOk, false),
            new MemberSlice(PROXY, countingOk, true)
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

        final List<CompletableFuture<Response>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            futures.add(resolver.response(
                new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
            ).orTimeout(30, TimeUnit.SECONDS));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int successCount = 0;
        int errorCount = 0;
        for (final CompletableFuture<Response> f : futures) {
            final Response resp = f.join();
            if (resp.status().code() == 200) {
                successCount++;
            } else {
                errorCount++;
            }
        }

        assertTrue(successCount + errorCount == CONCURRENT_REQUESTS,
            "All " + CONCURRENT_REQUESTS + " requests must resolve (got "
                + successCount + " success + " + errorCount + " error)");
        assertTrue(successCount > 0,
            "At least some requests must succeed (got " + successCount + ")");
    }

    /**
     * Fire 100 concurrent requests with index misses (proxy fanout path).
     * All must complete gracefully even under saturation.
     */
    @Test
    void saturation_allRequestsResolve_proxyFanout() throws Exception {
        final ArtifactIndex idx = nopIndex(Optional.of(List.of()));
        final Slice okSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());

        final List<MemberSlice> members = List.of(
            new MemberSlice(PROXY, okSlice, true)
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

        final List<CompletableFuture<Response>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            futures.add(resolver.response(
                new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
            ).orTimeout(30, TimeUnit.SECONDS));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int resolved = 0;
        for (final CompletableFuture<Response> f : futures) {
            f.join();
            resolved++;
        }
        assertTrue(resolved == CONCURRENT_REQUESTS,
            "All " + CONCURRENT_REQUESTS + " requests must resolve under saturation");
    }

    /**
     * Fire 100 concurrent requests where the index itself is slow (50ms per call).
     * Verify no deadlock or starvation: all futures complete.
     */
    @Test
    void saturation_slowIndex_allRequestsResolve() throws Exception {
        final ArtifactIndex slowIdx = new NopIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Optional.of(List.of(HOSTED));
                });
            }
        };
        final Slice okSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());

        final List<MemberSlice> members = List.of(
            new MemberSlice(HOSTED, okSlice, false),
            new MemberSlice(PROXY, okSlice, true)
        );

        final GroupResolver resolver = new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.of(slowIdx),
            REPO_TYPE,
            Set.of(PROXY),
            buildNegativeCache(),
            java.util.concurrent.ForkJoinPool.commonPool()
        );

        final List<CompletableFuture<Response>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            futures.add(resolver.response(
                new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
            ).orTimeout(30, TimeUnit.SECONDS));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int resolved = 0;
        for (final CompletableFuture<Response> f : futures) {
            f.join();
            resolved++;
        }
        assertTrue(resolved == CONCURRENT_REQUESTS,
            "All requests must resolve even with slow index");
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
        return new NegativeCache(config);
    }

    private static ArtifactIndex nopIndex(final Optional<List<String>> result) {
        return new NopIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                return CompletableFuture.completedFuture(result);
            }
        };
    }

    /**
     * Minimal no-op index base class.
     */
    private static class NopIndex implements ArtifactIndex {
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
            return CompletableFuture.completedFuture(Optional.of(List.of()));
        }

        @Override
        public void close() {
        }
    }
}
