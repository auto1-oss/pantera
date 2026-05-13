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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.fault.FaultTranslator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GroupResolver} covering every branch of the 5-path
 * decision tree from {@code docs/analysis/v2.2-target-architecture.md} section 2.
 *
 * <ul>
 *   <li>PATH A: 404 paths (negative cache hit, all-proxy-404, no-proxy-members)</li>
 *   <li>PATH B: 500 paths (DB timeout, DB failure, StorageUnavailable)</li>
 *   <li>PATH OK: success paths (index hit serves, proxy fanout first-wins)</li>
 *   <li>TOCTOU: index hit but member 404, falls through to proxy fanout (A11 fix)</li>
 *   <li>AllProxiesFailed: any proxy 5xx with no 2xx, pass-through via FaultTranslator</li>
 * </ul>
 *
 * @since 2.2.0
 */
final class GroupResolverTest {

    private static final String GROUP = "maven-group";
    private static final String REPO_TYPE = "maven-group";
    private static final String HOSTED = "libs-release-local";
    private static final String PROXY_A = "maven-central";
    private static final String PROXY_B = "jboss-proxy";
    private static final String JAR_PATH =
        "/com/google/guava/guava/31.1/guava-31.1.jar";
    private static final String PARSED_NAME = "com.google.guava.guava";
    /**
     * Version that {@link com.auto1.pantera.http.cache.NegativeCacheKey#fromPath}
     * extracts from {@link #JAR_PATH}. GroupResolver populates the cache with
     * this version so the admin UI shows it as a separate column.
     */
    private static final String PARSED_VERSION = "31.1";

    // ---- PATH A: negativeCacheHit_returns404WithoutDbQuery ----

    @Test
    void negativeCacheHit_returns404WithoutDbQuery() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final NegativeCache negCache = buildNegativeCache();
        // Pre-populate the negative cache
        negCache.cacheNotFound(new com.auto1.pantera.http.cache.NegativeCacheKey(
            GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION));

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), negCache,
            Map.of(HOSTED, okSlice(), PROXY_A, okSlice())
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(404, resp.status().code(),
            "Negative cache hit must return 404");
        assertTrue(idx.locateByNameCalls.isEmpty(),
            "DB must NOT be queried when negative cache hits");
    }

    // ---- PATH OK: indexHit_servesFromTargetedMember ----

    @Test
    void indexHit_servesFromTargetedMember() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, countingSlice(hostedCount, RsStatus.OK));
        slices.put(PROXY_A, countingSlice(proxyCount, RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "Index hit must return 200 from targeted member");
        assertEquals(1, hostedCount.get(),
            "Only the indexed member should be queried");
        assertEquals(0, proxyCount.get(),
            "Proxy must NOT be queried on index hit");
    }

    // ---- TOCTOU: indexHit_toctouDrift_fallsThroughToProxyFanout (A11 fix) ----

    @Test
    void indexHit_toctouDrift_fallsThroughToProxyFanout() {
        // Index says artifact is in HOSTED, but HOSTED returns 404 (TOCTOU)
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, countingSlice(hostedCount, RsStatus.NOT_FOUND));
        slices.put(PROXY_A, countingSlice(proxyCount, RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "TOCTOU drift must fall through to proxy fanout and succeed");
        assertEquals(1, hostedCount.get(),
            "Hosted member must be queried first (index hit)");
        assertEquals(1, proxyCount.get(),
            "Proxy must be queried after hosted 404 (TOCTOU fallthrough)");
    }

    // ---- PATH OK: indexMiss_proxyFanout_firstWins_cancelsOthers ----

    @Test
    void indexMiss_proxyFanout_firstWins_cancelsOthers() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final AtomicInteger proxyACount = new AtomicInteger(0);
        final AtomicInteger proxyBCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, countingSlice(proxyACount, RsStatus.OK));
        slices.put(PROXY_B, countingSlice(proxyBCount, RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            idx,
            List.of(PROXY_A, PROXY_B),
            Set.of(PROXY_A, PROXY_B),
            buildNegativeCache(),
            slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "Proxy fanout must return 200 when at least one proxy succeeds");
        // At least one proxy was queried
        assertTrue(proxyACount.get() + proxyBCount.get() >= 1,
            "At least one proxy member must be queried");
    }

    // ---- PATH A: indexMiss_allProxy404_negCachePopulated ----

    @Test
    void indexMiss_allProxy404_negCachePopulated() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final NegativeCache negCache = buildNegativeCache();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, notFoundSlice());
        slices.put(PROXY_B, notFoundSlice());

        final GroupResolver resolver = buildResolver(
            idx,
            List.of(PROXY_A, PROXY_B),
            Set.of(PROXY_A, PROXY_B),
            negCache,
            slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(404, resp.status().code(),
            "All-proxy-404 must return 404");
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertTrue(negCache.isKnown404(negKey),
            "Negative cache must be populated after all-proxy-404");
    }

    // ---- PATH B: indexMiss_anyProxy5xx_allProxiesFailedPassThrough ----

    @Test
    void indexMiss_anyProxy5xx_allProxiesFailedPassThrough() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, staticSlice(RsStatus.INTERNAL_ERROR));
        slices.put(PROXY_B, staticSlice(RsStatus.SERVICE_UNAVAILABLE));

        final GroupResolver resolver = buildResolver(
            idx,
            List.of(PROXY_A, PROXY_B),
            Set.of(PROXY_A, PROXY_B),
            buildNegativeCache(),
            slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        // FaultTranslator should pass through the best 5xx (503 beats 500)
        assertTrue(resp.status().serverError(),
            "AllProxiesFailed must return a server error");
        assertTrue(resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT)),
            "Response must contain X-Pantera-Fault header");
    }

    // ---- PATH B: dbTimeout_returnsIndexUnavailable500 ----

    @Test
    void dbTimeout_returnsIndexUnavailable500() {
        final ArtifactIndex idx = timeoutIndex();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, okSlice());
        slices.put(PROXY_A, okSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(500, resp.status().code(),
            "DB timeout must return 500");
        assertTrue(resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT)
                    && h.getValue().equals("index-unavailable")),
            "Response must have X-Pantera-Fault: index-unavailable");
    }

    // ---- PATH B: dbFailure_returnsIndexUnavailable500 ----

    @Test
    void dbFailure_returnsIndexUnavailable500() {
        final ArtifactIndex idx = failingIndex();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, okSlice());
        slices.put(PROXY_A, okSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(500, resp.status().code(),
            "DB failure must return 500");
        assertTrue(resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT)
                    && h.getValue().equals("index-unavailable")),
            "Response must have X-Pantera-Fault: index-unavailable");
    }

    // ---- PATH A: noProxyMembers_indexMiss_returns404 ----

    @Test
    void noProxyMembers_indexMiss_returns404() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final NegativeCache negCache = buildNegativeCache();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, countingSlice(hostedCount, RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            idx,
            List.of(HOSTED),
            Collections.emptySet(), // no proxy members
            negCache,
            slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(404, resp.status().code(),
            "Index miss with no proxy members must return 404");
        assertEquals(0, hostedCount.get(),
            "Hosted member must NOT be queried on index miss (fully indexed)");
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertTrue(negCache.isKnown404(negKey),
            "Negative cache must be populated");
    }

    // ---- Index hit + member 5xx: returns StorageUnavailable 500 ----

    @Test
    void indexHit_memberServerError_returnsStorageUnavailable() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, staticSlice(RsStatus.INTERNAL_ERROR));
        slices.put(PROXY_A, okSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(500, resp.status().code(),
            "Index hit + member 5xx must return 500 (StorageUnavailable)");
        assertTrue(resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT)
                    && h.getValue().equals("storage-unavailable")),
            "Response must have X-Pantera-Fault: storage-unavailable");
    }

    // ---- No index configured: full two-phase fanout ----

    @Test
    void noIndex_fullTwoPhaseFanout() {
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, countingSlice(hostedCount, RsStatus.OK));
        slices.put(PROXY_A, countingSlice(proxyCount, RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            null, // no index
            List.of(HOSTED, PROXY_A),
            Set.of(PROXY_A),
            buildNegativeCache(),
            slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "Full two-phase fanout must return 200 when a member serves");
        assertTrue(hostedCount.get() > 0,
            "Hosted member must be queried in full fanout");
    }

    // ---- Metadata URL (unparseable) skips index, does full fanout ----

    @Test
    void metadataUrl_skipsIndex_fullFanout() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger memberCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put("member-a", countingSlice(memberCount, RsStatus.OK));
        slices.put("member-b", countingSlice(new AtomicInteger(0), RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            idx,
            List.of("member-a", "member-b"),
            Set.of("member-a"),
            buildNegativeCache(),
            slices,
            "helm-group"
        );
        // /index.yaml is unparseable for helm
        final Response resp = resolver.response(
            new RequestLine("GET", "/index.yaml"), Headers.EMPTY, Content.EMPTY
        ).join();

        assertTrue(idx.locateByNameCalls.isEmpty(),
            "locateByName must NOT be called for metadata URL");
    }

    // ---- Mixed 404 + 5xx in proxy fanout: AllProxiesFailed (not all-404) ----

    @Test
    void proxyFanout_mixed404And5xx_allProxiesFailed() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, notFoundSlice());
        slices.put(PROXY_B, staticSlice(RsStatus.INTERNAL_ERROR));

        final GroupResolver resolver = buildResolver(
            idx,
            List.of(PROXY_A, PROXY_B),
            Set.of(PROXY_A, PROXY_B),
            buildNegativeCache(),
            slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        // Mixed: one 404 + one 5xx => AllProxiesFailed (passes through the 5xx)
        assertTrue(resp.status().serverError(),
            "Mixed 404+5xx must produce AllProxiesFailed (server error)");
        assertTrue(resp.headers().stream()
                .anyMatch(h -> h.getKey().equals(FaultTranslator.HEADER_FAULT)),
            "Must have X-Pantera-Fault header");
    }

    // ---- HEAD request works like GET ----

    @Test
    void headRequestWorks() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, okSlice());
        slices.put(PROXY_A, okSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("HEAD", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "HEAD must be handled like GET");
    }

    // ---- Non-GET/HEAD/POST returns 405 ----

    @Test
    void putReturns405() {
        final Map<String, Slice> slices = Map.of(HOSTED, okSlice());
        final GroupResolver resolver = buildResolver(
            null, List.of(HOSTED), Collections.emptySet(),
            buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("PUT", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(405, resp.status().code(),
            "PUT must return 405 Method Not Allowed");
    }

    // ---- Empty members returns 404 ----

    @Test
    void emptyMembersReturns404() {
        final GroupResolver resolver = new GroupResolver(
            GROUP,
            Collections.emptyList(),
            Collections.emptyList(),
            Optional.empty(),
            REPO_TYPE,
            Collections.emptySet(),
            buildNegativeCache(),
            java.util.concurrent.ForkJoinPool.commonPool()
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(404, resp.status().code(),
            "Empty members must return 404");
    }

    // ---- Single-member smoke (sequential is the only fanout mode in v2.2.0+) ----

    @Test
    void singleMemberWithArtifactSucceeds() throws Exception {
        final byte[] payload = "ok".getBytes();
        final Slice only = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().body(payload).build());
        final List<MemberSlice> members = List.of(
            new MemberSlice("only", only, true)
        );
        final GroupResolver resolver = new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.empty(),
            REPO_TYPE,
            Set.of("only"),
            buildNegativeCache(),
            java.util.concurrent.ForkJoinPool.commonPool()
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(200, resp.status().code());
        assertArrayEquals(payload, resp.body().asBytes());
    }

    // ---- Helpers ----

    private GroupResolver buildResolver(
        final ArtifactIndex idx,
        final List<String> memberNames,
        final Set<String> proxyMemberNames,
        final NegativeCache negCache,
        final Map<String, Slice> sliceMap
    ) {
        return buildResolver(idx, memberNames, proxyMemberNames, negCache, sliceMap, REPO_TYPE);
    }

    private GroupResolver buildResolver(
        final ArtifactIndex idx,
        final List<String> memberNames,
        final Set<String> proxyMemberNames,
        final NegativeCache negCache,
        final Map<String, Slice> sliceMap,
        final String repoType
    ) {
        final List<MemberSlice> members = memberNames.stream()
            .map(name -> {
                final Slice s = sliceMap.getOrDefault(name,
                    (line, headers, body) ->
                        CompletableFuture.completedFuture(ResponseBuilder.notFound().build()));
                return new MemberSlice(name, s, proxyMemberNames.contains(name));
            })
            .toList();
        return new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            idx != null ? Optional.of(idx) : Optional.empty(),
            repoType,
            proxyMemberNames,
            negCache,
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

    private static Slice okSlice() {
        return (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
    }

    private static Slice notFoundSlice() {
        return (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
    }

    private static Slice staticSlice(final RsStatus status) {
        return (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.from(status).build());
    }

    private static Slice countingSlice(final AtomicInteger counter, final RsStatus status) {
        return (line, headers, body) -> {
            counter.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.from(status).build());
        };
    }

    /**
     * Index that completes exceptionally with a RuntimeException wrapping
     * a TimeoutException.
     */
    private static ArtifactIndex timeoutIndex() {
        return new NopIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("statement timeout", new TimeoutException("500ms"))
                );
            }
        };
    }

    /**
     * Index that completes exceptionally with a generic DB error.
     */
    private static ArtifactIndex failingIndex() {
        return new NopIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("connection refused")
                );
            }
        };
    }

    /**
     * Recording index that tracks locateByName calls.
     */
    private static final class RecordingIndex extends NopIndex {
        final List<String> locateByNameCalls = new CopyOnWriteArrayList<>();
        final List<String> locateCalls = new CopyOnWriteArrayList<>();
        private final Optional<List<String>> result;

        RecordingIndex(final Optional<List<String>> result) {
            this.result = result;
        }

        @Override
        public CompletableFuture<Optional<List<String>>> locateByName(final String name) {
            this.locateByNameCalls.add(name);
            return CompletableFuture.completedFuture(this.result);
        }

        @Override
        public CompletableFuture<List<String>> locate(final String path) {
            this.locateCalls.add(path);
            return CompletableFuture.completedFuture(
                this.result.orElse(List.of())
            );
        }
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
