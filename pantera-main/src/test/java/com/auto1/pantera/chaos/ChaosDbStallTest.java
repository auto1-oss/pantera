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
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos test: simulate 500ms stall on every DB (index) call.
 *
 * <p>Verifies that when the artifact index is pathologically slow,
 * {@link GroupResolver} classifies the outcome as
 * {@code IndexOutcome.Timeout} or {@code IndexOutcome.DBFailure}
 * and translates it to a 500 with {@code X-Pantera-Fault: index-unavailable}.
 *
 * <p>Uses in-memory/mock infrastructure only; no Docker required.
 *
 * @since 2.2.0
 */
@Tag("Chaos")
final class ChaosDbStallTest {

    private static final String GROUP = "chaos-db-group";
    private static final String REPO_TYPE = "maven-group";
    private static final String HOSTED = "libs-release";
    private static final String PROXY = "central-proxy";
    private static final String JAR_PATH =
        "/com/example/artifact/1.0/artifact-1.0.jar";

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newScheduledThreadPool(2);

    /**
     * A 500ms DB stall that eventually completes exceptionally with a timeout
     * must produce a 500 with {@code X-Pantera-Fault: index-unavailable}.
     */
    @Test
    void dbStall_returnsIndexUnavailable() {
        final ArtifactIndex stallingIndex = stallingTimeoutIndex(Duration.ofMillis(500));

        final GroupResolver resolver = buildResolver(
            stallingIndex,
            List.of(HOSTED, PROXY),
            Set.of(PROXY)
        );

        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).orTimeout(5, TimeUnit.SECONDS).join();

        assertEquals(500, resp.status().code(),
            "DB stall must result in 500");
        assertTrue(resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT)
                    && h.getValue().equals("index-unavailable")),
            "Response must have X-Pantera-Fault: index-unavailable");
    }

    /**
     * A DB stall that completes exceptionally with a generic error
     * (not a timeout) must also produce index-unavailable.
     */
    @Test
    void dbStall_genericError_returnsIndexUnavailable() {
        final ArtifactIndex stallingIndex = stallingErrorIndex(Duration.ofMillis(500));

        final GroupResolver resolver = buildResolver(
            stallingIndex,
            List.of(HOSTED, PROXY),
            Set.of(PROXY)
        );

        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).orTimeout(5, TimeUnit.SECONDS).join();

        assertEquals(500, resp.status().code(),
            "DB stall (generic error) must result in 500");
        assertTrue(resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT)
                    && h.getValue().equals("index-unavailable")),
            "Response must have X-Pantera-Fault: index-unavailable");
    }

    /**
     * Multiple concurrent requests during a DB stall must all get
     * deterministic error responses (no deadlock, no hang).
     */
    @Test
    void dbStall_concurrentRequests_allResolve() throws Exception {
        final ArtifactIndex stallingIndex = stallingTimeoutIndex(Duration.ofMillis(300));
        final GroupResolver resolver = buildResolver(
            stallingIndex,
            List.of(HOSTED, PROXY),
            Set.of(PROXY)
        );

        final int count = 20;
        @SuppressWarnings("unchecked")
        final CompletableFuture<Response>[] futures = new CompletableFuture[count];
        for (int i = 0; i < count; i++) {
            futures[i] = resolver.response(
                new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
            ).orTimeout(10, TimeUnit.SECONDS);
        }

        CompletableFuture.allOf(futures).join();
        for (int i = 0; i < count; i++) {
            final Response resp = futures[i].join();
            assertEquals(500, resp.status().code(),
                "Request " + i + " must return 500 during DB stall");
        }
    }

    // ---- Helpers ----

    /**
     * Index that stalls for the given duration then fails with a timeout.
     */
    private static ArtifactIndex stallingTimeoutIndex(final Duration stall) {
        return new NopIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                final CompletableFuture<Optional<List<String>>> future = new CompletableFuture<>();
                SCHEDULER.schedule(
                    () -> future.completeExceptionally(
                        new RuntimeException("statement timeout",
                            new TimeoutException(stall.toMillis() + "ms"))),
                    stall.toMillis(), TimeUnit.MILLISECONDS
                );
                return future;
            }
        };
    }

    /**
     * Index that stalls for the given duration then fails with a generic DB error.
     */
    private static ArtifactIndex stallingErrorIndex(final Duration stall) {
        return new NopIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                final CompletableFuture<Optional<List<String>>> future = new CompletableFuture<>();
                SCHEDULER.schedule(
                    () -> future.completeExceptionally(
                        new RuntimeException("connection pool exhausted")),
                    stall.toMillis(), TimeUnit.MILLISECONDS
                );
                return future;
            }
        };
    }

    private GroupResolver buildResolver(
        final ArtifactIndex idx,
        final List<String> memberNames,
        final Set<String> proxyMemberNames
    ) {
        final Slice okSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());

        final List<MemberSlice> members = memberNames.stream()
            .map(name -> new MemberSlice(name, okSlice, proxyMemberNames.contains(name)))
            .toList();
        return new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.of(idx),
            REPO_TYPE,
            proxyMemberNames,
            buildNegativeCache(),
            java.util.concurrent.ForkJoinPool.commonPool()
        );
    }

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
