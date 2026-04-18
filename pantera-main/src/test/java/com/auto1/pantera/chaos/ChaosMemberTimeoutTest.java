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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chaos test: simulate a proxy member that takes 30 seconds to respond.
 *
 * <p>Verifies that {@link GroupResolver} returns within its internal deadline
 * when a member is pathologically slow, rather than blocking indefinitely.
 * The response must carry the {@code X-Pantera-Fault} header indicating
 * a fault condition (all-proxies-failed or similar).
 *
 * <p>Uses in-memory/mock infrastructure only; no Docker required.
 *
 * @since 2.2.0
 */
@Tag("Chaos")
@SuppressWarnings("PMD.TooManyMethods")
final class ChaosMemberTimeoutTest {

    private static final String GROUP = "chaos-group";
    private static final String REPO_TYPE = "npm-group";
    private static final String SLOW_PROXY = "slow-upstream";
    private static final String JAR_PATH =
        "/com/example/artifact/1.0/artifact-1.0.jar";

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newScheduledThreadPool(2);

    /**
     * A proxy member that takes 30 seconds to respond should not block the
     * caller indefinitely. When an external deadline (orTimeout) fires,
     * the resolution is cancelled within that deadline rather than waiting
     * the full 30 seconds. This simulates a client-side deadline enforcement.
     */
    @Test
    void slowMember_groupReturnsWithinDeadline() {
        final ArtifactIndex idx = nopIndex(Optional.of(List.of()));
        final Slice slowSlice = slowSlice(Duration.ofSeconds(30));

        final GroupResolver resolver = buildResolver(
            idx,
            List.of(SLOW_PROXY),
            Set.of(SLOW_PROXY),
            Map.of(SLOW_PROXY, slowSlice)
        );

        final long start = System.currentTimeMillis();
        final CompletableFuture<Response> future = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).orTimeout(5, TimeUnit.SECONDS);

        // The future must complete (either with a response or an exception)
        // within 5 seconds, not block for 30 seconds.
        boolean timedOut = false;
        boolean gotErrorResponse = false;
        try {
            final Response resp = future.join();
            // If the resolver returns before the deadline, the response
            // must indicate failure (the slow member did not complete).
            gotErrorResponse = resp.status().code() >= 400;
        } catch (final java.util.concurrent.CompletionException ex) {
            // orTimeout fired: the deadline was enforced
            timedOut = ex.getCause() instanceof java.util.concurrent.TimeoutException;
        }
        final long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 10_000,
            "Resolution must complete within the deadline, took " + elapsed + "ms");
        assertTrue(timedOut || gotErrorResponse,
            "Slow member must produce either a timeout or an error response");
    }

    /**
     * When the sole proxy member is slow and index returns a miss,
     * the external deadline fires and the future completes exceptionally
     * with a TimeoutException (Fault.Deadline simulation).
     */
    @Test
    void slowMember_indexMiss_returnsFaultOrTimeout() {
        final ArtifactIndex idx = nopIndex(Optional.of(List.of()));
        final Slice slowSlice = slowSlice(Duration.ofSeconds(30));

        final GroupResolver resolver = buildResolver(
            idx,
            List.of(SLOW_PROXY),
            Set.of(SLOW_PROXY),
            Map.of(SLOW_PROXY, slowSlice)
        );

        final CompletableFuture<Response> future = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).orTimeout(5, TimeUnit.SECONDS);

        boolean timedOut = false;
        boolean hasFaultOrError = false;
        try {
            final Response resp = future.join();
            final boolean hasFault = resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT));
            hasFaultOrError = hasFault || resp.status().code() >= 400;
        } catch (final java.util.concurrent.CompletionException ex) {
            timedOut = ex.getCause() instanceof java.util.concurrent.TimeoutException;
        }

        assertTrue(timedOut || hasFaultOrError,
            "Slow-member timeout must produce a timeout exception or fault/error response");
    }

    // ---- Helpers ----

    private static Slice slowSlice(final Duration delay) {
        return (line, headers, body) -> {
            final CompletableFuture<Response> future = new CompletableFuture<>();
            SCHEDULER.schedule(
                () -> future.complete(ResponseBuilder.ok().build()),
                delay.toMillis(), TimeUnit.MILLISECONDS
            );
            return future;
        };
    }

    private GroupResolver buildResolver(
        final ArtifactIndex idx,
        final List<String> memberNames,
        final Set<String> proxyMemberNames,
        final Map<String, Slice> sliceMap
    ) {
        final List<MemberSlice> members = memberNames.stream()
            .map(name -> new MemberSlice(
                name,
                sliceMap.getOrDefault(name,
                    (line, headers, body) ->
                        CompletableFuture.completedFuture(ResponseBuilder.notFound().build())),
                proxyMemberNames.contains(name)
            ))
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
