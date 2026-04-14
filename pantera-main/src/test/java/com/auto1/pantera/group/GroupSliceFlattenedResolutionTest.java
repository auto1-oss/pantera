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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the 5-path resolution flow defined by the GroupSlice v2 spec:
 *
 * <ol>
 *   <li>PARSE artifact name. Unparseable → full two-phase fanout.</li>
 *   <li>QUERY INDEX.  DB error (Optional.empty)  → full two-phase fanout (safety net).
 *                     Empty list (confirmed miss) → proxy-only fanout.
 *                     Hit                         → targeted local read.</li>
 *   <li>TARGETED LOCAL READ (hit): NO circuit breaker. NO fallback fanout on 5xx.</li>
 *   <li>PROXY FANOUT (miss): query proxy leaves only; hosted NOT queried.</li>
 *   <li>FULL TWO-PHASE FANOUT: hosted first, then proxy.</li>
 * </ol>
 *
 * <p>Key behaviour locked in:
 * <ul>
 *   <li>Index hit + member 5xx → 500 (local error, no fallback bytes elsewhere)</li>
 *   <li>No 503 from group resolution — 503 used to leak from circuit-open skips</li>
 * </ul>
 *
 * @since 2.1.3
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
final class GroupSliceFlattenedResolutionTest {

    private static final String MAVEN_GROUP = "maven-group";
    private static final String HOSTED = "libs-release-local";
    private static final String PROXY = "maven-central";
    private static final String JAR_PATH =
        "/com/google/guava/guava/31.1/guava-31.1.jar";
    private static final String PARSED_NAME = "com.google.guava.guava";

    // ---- Path 3: Index hit → targeted local read returns 200 ----

    @Test
    void indexHitTargetedServes() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, staticSlice(hostedCount, RsStatus.OK));
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.OK));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(HOSTED, PROXY),
            Set.of(PROXY),
            slices
        );
        final Response resp = slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, resp.status(),
            "Targeted local read must return 200 when member serves");
        assertEquals(1, hostedCount.get(),
            "Only the indexed member should be queried");
        assertEquals(0, proxyCount.get(),
            "Proxy must NOT be queried on index hit");
        assertTrue(idx.locateByNameCalls.contains(PARSED_NAME));
    }

    // ---- Path 3: Index hit + member 5xx → 500, NO fallback fanout ----

    @Test
    void indexHitMemberFailsReturns500NoFanout() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, staticSlice(hostedCount, RsStatus.INTERNAL_ERROR));
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.OK));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(HOSTED, PROXY),
            Set.of(PROXY),
            slices
        );
        final Response resp = slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(RsStatus.INTERNAL_ERROR, resp.status(),
            "Index hit + member 5xx must return 500 (local error) — NOT 502/503");
        assertEquals(1, hostedCount.get(),
            "Indexed member must be queried exactly once");
        assertEquals(0, proxyCount.get(),
            "Proxy members MUST NOT be queried on targeted-read failure "
                + "(no fallback fanout — bytes are local, nobody else has them)");
    }

    // ---- Path 3: Index hit + member 404 → 404 returned as-is (stale index) ----

    @Test
    void indexHitMember404ReturnsAsIs() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, staticSlice(hostedCount, RsStatus.NOT_FOUND));
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.OK));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(HOSTED, PROXY),
            Set.of(PROXY),
            slices
        );
        final Response resp = slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(RsStatus.NOT_FOUND, resp.status(),
            "Index hit + member 404 must return 404 as-is (stale index scenario)");
        assertEquals(1, hostedCount.get(),
            "Indexed member must be queried exactly once");
        assertEquals(0, proxyCount.get(),
            "Proxy must NOT be queried after targeted-read 404");
    }

    // ---- Path 4: Index confirmed miss → only proxy members queried ----

    @Test
    void indexMissProxyFanoutServes() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of()));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, staticSlice(hostedCount, RsStatus.OK));
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.OK));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(HOSTED, PROXY),
            Set.of(PROXY),
            slices
        );
        final Response resp = slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, resp.status(),
            "Proxy-only fanout must serve 200 when proxy has it");
        assertEquals(0, hostedCount.get(),
            "Hosted members MUST NOT be queried on confirmed index miss "
                + "(hosted is fully indexed — absence = absence)");
        assertEquals(1, proxyCount.get(),
            "Proxy member must be queried on index miss");
    }

    // ---- Path 5: DB error (Optional.empty) → full two-phase fanout ----

    @Test
    void dbErrorFullFanout() {
        final RecordingIndex idx = new RecordingIndex(Optional.empty());
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, staticSlice(hostedCount, RsStatus.NOT_FOUND));
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.OK));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(HOSTED, PROXY),
            Set.of(PROXY),
            slices
        );
        final Response resp = slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, resp.status(),
            "Full fanout safety net should still succeed via proxy on DB error");
        assertEquals(1, hostedCount.get(),
            "Hosted must be queried in full fanout safety net (DB error)");
        assertEquals(1, proxyCount.get(),
            "Proxy must be queried after hosted returns 404 in full fanout");
    }

    // ---- Path 1: Unparseable URL → full two-phase fanout ----

    @Test
    void unparseableNameFullFanout() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, staticSlice(hostedCount, RsStatus.NOT_FOUND));
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.OK));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(HOSTED, PROXY),
            Set.of(PROXY),
            slices
        );
        // "/" is unparseable for maven-group (no artifact name)
        final Response resp = slice.response(
            new RequestLine("GET", "/"), Headers.EMPTY, Content.EMPTY
        ).join();
        // Unparseable bypasses the index entirely.
        assertTrue(idx.locateByNameCalls.isEmpty(),
            "locateByName MUST NOT be called for unparseable URL");
        assertEquals(RsStatus.OK, resp.status(),
            "Unparseable URL → full fanout; proxy serves 200");
        assertEquals(1, hostedCount.get(),
            "Hosted must be queried in full fanout (phase 1)");
        assertEquals(1, proxyCount.get(),
            "Proxy must be queried in full fanout (phase 2)");
    }

    // ---- No 503 from group resolution: all-404 returns 404, never 503 ----

    @Test
    void indexMissAll404ReturnsPlain404() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of()));
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.NOT_FOUND));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(PROXY),
            Set.of(PROXY),
            slices
        );
        final Response resp = slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(RsStatus.NOT_FOUND, resp.status(),
            "Index miss + all proxy 404 → 404 (never 503)");
        assertNotEquals(RsStatus.SERVICE_UNAVAILABLE, resp.status(),
            "Group resolution must NEVER return 503");
    }

    // ---- Index miss + all proxy 5xx → 502 (we ARE proxying) ----

    @Test
    void indexMissAll5xxReturns502() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of()));
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.INTERNAL_ERROR));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(PROXY),
            Set.of(PROXY),
            slices
        );
        final Response resp = slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(RsStatus.BAD_GATEWAY, resp.status(),
            "Index miss + proxy 5xx → 502 (we are proxying a bad gateway)");
    }

    // ---- Negative cache: second fanout for confirmed-miss is suppressed ----

    @Test
    void negativeCachePreventsSecondFanout() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // confirmed miss
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY, staticSlice(proxyCount, RsStatus.NOT_FOUND));
        final GroupSlice slice = buildGroup(
            idx,
            List.of(PROXY),
            Set.of(PROXY),
            slices
        );
        // First request: fanout → 404 → cache populated
        slice.response(new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY).join();
        // Second request: should hit negative cache, not fanout again
        slice.response(new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY).join();
        assertEquals(1, proxyCount.get(),
            "Proxy should only be queried once — second request hits negative cache");
    }

    // ---- Helpers ----

    private static GroupSlice buildGroup(
        final ArtifactIndex idx,
        final List<String> members,
        final Set<String> proxyMembers,
        final Map<String, Slice> slices
    ) {
        return new GroupSlice(
            new MapResolver(slices),
            MAVEN_GROUP,
            members,
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(idx),
            proxyMembers,
            MAVEN_GROUP
        );
    }

    private static Slice staticSlice(final AtomicInteger counter, final RsStatus status) {
        return (line, headers, body) -> {
            counter.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(status).build()
            );
        };
    }

    /**
     * ArtifactIndex stub that records {@code locateByName} calls and returns
     * a fixed Optional result.  Empty Optional models a DB error; present
     * Optional with empty list models a confirmed miss.
     */
    private static final class RecordingIndex implements ArtifactIndex {
        final List<String> locateByNameCalls = new CopyOnWriteArrayList<>();
        private final Optional<List<String>> result;

        RecordingIndex(final Optional<List<String>> result) {
            this.result = result;
        }

        @Override
        public CompletableFuture<Void> index(final ArtifactDocument doc) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(
            final String repoName, final String artifactPath
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SearchResult> search(
            final String query, final int maxResults, final int offset
        ) {
            return CompletableFuture.completedFuture(SearchResult.EMPTY);
        }

        @Override
        public CompletableFuture<List<String>> locate(final String artifactPath) {
            throw new AssertionError(
                "locate() must NEVER be called in the flattened resolution flow"
            );
        }

        @Override
        public CompletableFuture<Optional<List<String>>> locateByName(
            final String artifactName
        ) {
            this.locateByNameCalls.add(artifactName);
            return CompletableFuture.completedFuture(this.result);
        }

        @Override
        public void close() {
            // nop
        }
    }

    private static final class MapResolver implements SliceResolver {
        private final Map<String, Slice> slices;

        MapResolver(final Map<String, Slice> slices) {
            this.slices = slices;
        }

        @Override
        public Slice slice(final Key name, final int port, final int depth) {
            final Slice s = this.slices.get(name.string());
            return s != null ? s
                : (line, headers, body) ->
                    CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
        }
    }
}
