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
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
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

    // ---- FIX 2: a rate-limit-laundered 404 must not poison the negative cache ----

    @Test
    void markedNonAuthoritative404IsNotNegativeCached() {
        // A proxy member launders an upstream throttle (403/429/410) into a 404
        // for the multi-remote race, flagging it with NegativeCache.SKIP_HEADER.
        // The group must serve the 404 but NOT cache it — the artifact may exist
        // and the upstream was merely throttling; caching would create a
        // long-lived false 404. JAR_PATH parses to a NON-empty version, so only
        // the marker (not Fix 1) prevents caching here.
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // index miss
        final NegativeCache negCache = buildNegativeCache();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, unverifiedNotFoundSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(PROXY_A), Set.of(PROXY_A), negCache, slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(404, resp.status().code(),
            "a marked non-authoritative absence is still returned as 404");
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(
                GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertFalse(negCache.isKnown404(negKey),
            "a rate-limit-laundered 404 must NOT be negative-cached");
    }

    @Test
    void plainAll404IsStillNegativeCached() {
        // Counterpart guard: an UNmarked all-members 404 is a genuine absence
        // and must still be cached (thundering-herd protection preserved).
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // index miss
        final NegativeCache negCache = buildNegativeCache();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, notFoundSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(PROXY_A), Set.of(PROXY_A), negCache, slices
        );
        resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(
                GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertTrue(negCache.isKnown404(negKey),
            "a genuine all-members 404 (unmarked) must still be negative-cached");
    }

    // ---- WS8 Bug B5: the walk terminal must not discard a member's own honest 404 body ----

    @Test
    void allMembers404_terminalPreservesFirstMemberHonestBody() throws Exception {
        // Regression for the live-server bug: GroupResolver's sequential walk
        // used to drainBody() every member 404 and, once every member was
        // exhausted, manufacture a bare ResponseBuilder.notFound().build() --
        // discarding a proxy member's own honest "version not found" JSON
        // (e.g. what CachedNpmProxySlice builds for npm's /<pkg>/<version>
        // shape). This drives GroupResolver's real member-walk boundary --
        // the same Slice interface production member adapters implement --
        // with a stub whose 404 body/shape matches CachedNpmProxySlice's
        // actual output exactly, so it fails on the pre-fix walk exactly as a
        // live npm_group /<pkg>/<bad-version> lookup did.
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // index miss
        final NegativeCache negCache = buildNegativeCache();
        final String honestBody =
            "{\"error\":\"version not found: 999.999.999\",\"package\":\"pnpm\"}";
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, honestNotFoundSlice(honestBody));

        final GroupResolver resolver = buildResolver(
            idx, List.of(PROXY_A), Set.of(PROXY_A), negCache, slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "the walk terminal must still answer 404",
            resp.status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the walk terminal must forward the member's own honest body, not a bare empty one",
            resp.body().asBytesFuture().get(),
            new IsEqual<>(honestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    @Test
    void allMembers404_unverifiedMarkerAlsoPreservesHonestBody() throws Exception {
        // The anyUnverified terminal (Fix 2's SKIP_HEADER re-attachment) had
        // the exact same bare-body bug as the plain terminal above -- cover
        // it separately since it is a distinct branch in tryNextSequentialMember.
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // index miss
        final NegativeCache negCache = buildNegativeCache();
        final String honestBody =
            "{\"error\":\"version not found: 999.999.999\",\"package\":\"pnpm\"}";
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, honestUnverifiedNotFoundSlice(honestBody));

        final GroupResolver resolver = buildResolver(
            idx, List.of(PROXY_A), Set.of(PROXY_A), negCache, slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.NOT_FOUND));
        MatcherAssert.assertThat(
            "the non-authoritative-404 terminal must also forward the member's honest body",
            resp.body().asBytesFuture().get(),
            new IsEqual<>(honestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(
                GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        MatcherAssert.assertThat(
            "a laundered 404 must still not be negative-cached, even with a preserved body",
            negCache.isKnown404(negKey),
            new IsEqual<>(false)
        );
    }

    // ---- FIX 5: malformed version-range path rejected before any walk ----

    @Test
    void versionRangePath_rejected404_withoutTouchingMembersOrIndex() {
        // A broken Gradle dependency can leak a Maven version RANGE into the
        // artifact coordinate (e.g. `graphql-utils-[,7.2079-test-1).jar`). The
        // range metacharacters `[ ] ( )` are never part of a valid artifact
        // path; forwarding them makes upstreams 502 on the unescaped brackets
        // and the walk would recordFailure() against a HEALTHY member
        // (fabricated breaker evidence). The resolver must 404 before the index
        // lookup or any member is queried.
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, countingSlice(hostedCount, RsStatus.OK));
        slices.put(PROXY_A, countingSlice(proxyCount, RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED, PROXY_A), Set.of(PROXY_A), buildNegativeCache(), slices
        );
        final String rangePath =
            "/test-automation/graphql-utils/[,7.2079-test-1)/graphql-utils-[,7.2079-test-1).jar";
        final Response resp = resolver.response(
            new RequestLine("GET", rangePath), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(404, resp.status().code(),
            "Malformed version-range path must be rejected with 404");
        assertEquals(0, hostedCount.get(),
            "No member may be queried for a malformed range path");
        assertEquals(0, proxyCount.get(),
            "No proxy may be queried for a malformed range path");
        assertTrue(idx.locateByNameCalls.isEmpty(),
            "The index must not be queried for a malformed range path");
    }

    @Test
    void bracketCharactersAreServedNormallyForNonMavenGroupTypes() {
        // The version-range guard is Maven/Gradle-specific: `[ ] ( )` are only
        // ever a malformed version range in THAT ecosystem. GroupResolver is
        // also the shared response() for file/php/npm/gem/go/pypi/docker-group,
        // where a bracket is a perfectly legitimate file name (e.g. an upload
        // named "backup[v2].zip"). The guard must not fire outside
        // maven-group/gradle-group.
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicInteger hostedCount = new AtomicInteger(0);
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, countingSlice(hostedCount, RsStatus.OK));

        final GroupResolver resolver = buildResolver(
            idx, List.of(HOSTED), Set.of(), buildNegativeCache(), slices, "file-group"
        );
        final String bracketPath = "/reports/backup[v2].zip";
        final Response resp = resolver.response(
            new RequestLine("GET", bracketPath), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "A legitimate bracket-containing file name must be served, not rejected as a version range");
        assertEquals(1, hostedCount.get(),
            "The member must be queried normally for a file-group request");
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

    // ---- WS8 Bug 2: a HEAD probe must never poison the negative cache ----

    @Test
    void headProbe_noProxyMembers_doesNotNegativeCache_andFollowingGetStillQueries() {
        // Counterpart to noProxyMembers_indexMiss_returns404 above, driven by
        // HEAD instead of GET. A HEAD probe (health check, scanner, routine
        // existence check) hitting the same no-proxy-members dead end must
        // NOT write a negative-cache entry -- only a GET's 404 is trusted.
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final NegativeCache negCache = buildNegativeCache();
        final GroupResolver resolver = buildResolver(
            idx,
            List.of(HOSTED),
            Collections.emptySet(), // no proxy members
            negCache,
            Map.of(HOSTED, okSlice())
        );

        final Response headResp = resolver.response(
            new RequestLine("HEAD", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(404, headResp.status().code(),
            "HEAD with no proxy members must still return 404");
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertFalse(negCache.isKnown404(negKey),
            "A HEAD probe 404 must NOT negative-cache");

        // A following GET must re-query rather than short-circuit off a
        // negative-cache entry the HEAD probe should never have written --
        // negativeCacheHit_returns404WithoutDbQuery above proves a poisoned
        // cache skips the index query entirely, so this is the direct
        // counter-proof that the HEAD did not poison it.
        final int callsBeforeGet = idx.locateByNameCalls.size();
        final Response getResp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(404, getResp.status().code(),
            "GET with no proxy members still returns 404 -- there is nothing to serve it");
        assertTrue(idx.locateByNameCalls.size() > callsBeforeGet,
            "The GET must re-query the index, proving it was not short-circuited "
                + "by a HEAD-poisoned negative cache");
    }

    @Test
    void headProbe_allMembers404_doesNotNegativeCache_andFollowingGetSucceeds() {
        // Counterpart to indexMiss_allProxy404_negCachePopulated below, driven
        // by HEAD. All proxy members answer 404 to the HEAD probe; the group
        // must still answer 404 but must NOT negative-cache it. A following
        // GET for the same coordinate, now that the artifact has actually been
        // published, must succeed -- proving the HEAD never shadowed the GET.
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final NegativeCache negCache = buildNegativeCache();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, notFoundThenOkSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(PROXY_A), Set.of(PROXY_A), negCache, slices
        );

        final Response headResp = resolver.response(
            new RequestLine("HEAD", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(404, headResp.status().code(),
            "HEAD against a not-yet-published artifact must return 404");
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertFalse(negCache.isKnown404(negKey),
            "A HEAD-driven all-proxies-404 must NOT negative-cache");

        final Response getResp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertEquals(200, getResp.status().code(),
            "A following GET for the now-published artifact must succeed, "
                + "not be shadowed by a HEAD-poisoned negative cache");
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

    // ---- Breaker-cascade fixes: marker skip, 503 terminal, cache-only probe ----

    @Test
    void circuitMarker502_skipsWithoutConviction_nextMemberServes() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, circuitOpenSlice("17"));
        slices.put(PROXY_B, okSlice());

        final GroupResolver resolver = buildResolver(
            idx, List.of(PROXY_A, PROXY_B), Set.of(PROXY_A, PROXY_B),
            buildNegativeCache(), slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "A marker-502 member must be skipped and the next member must serve");
    }

    @Test
    void allMembersCircuitMarker_returns503RetryAfter_notNegativeCached() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // miss
        final NegativeCache negCache = buildNegativeCache();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY_A, circuitOpenSlice("17"));
        slices.put(PROXY_B, circuitOpenSlice("9"));

        final GroupResolver resolver = buildResolver(
            idx, List.of(PROXY_A, PROXY_B), Set.of(PROXY_A, PROXY_B), negCache, slices
        );
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(503, resp.status().code(),
            "All-members-circuit-open must be 503 (temporarily unavailable), never 404");
        assertEquals(List.of("17"),
            resp.headers().values("Retry-After"),
            "Retry-After must carry the largest hint seen across skipped members");
        assertFalse(
            resp.headers().values(
                com.auto1.pantera.http.UpstreamCircuitOpenException.HEADER
            ).isEmpty(),
            "The 503 terminal must carry the circuit-open marker");
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(
                GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertFalse(negCache.isKnown404(negKey),
            "Circuit-skip terminal must NOT poison the negative cache");
    }

    @Test
    void blockedMember_cacheOnlyProbe_servesWarmCache() {
        final java.util.concurrent.atomic.AtomicBoolean sawCacheOnly =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final Slice blockedSlice = (line, headers, body) -> {
            final boolean cacheOnly = !headers.values(
                com.auto1.pantera.http.cache.BaseCachedProxySlice.CACHE_ONLY_HEADER
            ).isEmpty();
            sawCacheOnly.set(cacheOnly);
            if (cacheOnly) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body("warm".getBytes()).build());
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build());
        };
        final GroupResolver resolver = resolverWithBlockedMember(blockedSlice, null);
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "A circuit-open member must still serve from its warm cache");
        assertTrue(sawCacheOnly.get(),
            "The probe of a circuit-open member must carry the cache-only header");
    }

    @Test
    void blockedMember_cacheOnlyMiss_terminal503WithBlockRemainder() {
        final NegativeCache negCache = buildNegativeCache();
        final GroupResolver resolver = resolverWithBlockedMember(notFoundSlice(), negCache);
        final Response resp = resolver.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(503, resp.status().code(),
            "Blocked member with a cold cache must produce 503, not 404");
        final List<String> retry = resp.headers().values("Retry-After");
        assertFalse(retry.isEmpty(), "503 must carry Retry-After");
        assertTrue(Long.parseLong(retry.get(0)) >= 5L,
            "Retry-After must be at least the 5s floor");
        final com.auto1.pantera.http.cache.NegativeCacheKey negKey =
            new com.auto1.pantera.http.cache.NegativeCacheKey(
                GROUP, REPO_TYPE, PARSED_NAME, PARSED_VERSION);
        assertFalse(negCache.isKnown404(negKey),
            "Cache-only miss on a blocked member must NOT poison the negative cache");
    }

    /**
     * Build a resolver whose single proxy member has its group-layer
     * circuit BLOCKED (one failure against a min-calls=1 registry).
     */
    private static GroupResolver resolverWithBlockedMember(
        final Slice slice, final NegativeCache negCache
    ) {
        final AutoBlockRegistry registry = new AutoBlockRegistry(
            new AutoBlockSettings(
                0.5, 1, 30, Duration.ofSeconds(60), Duration.ofMinutes(5))
        );
        registry.recordFailure(PROXY_A);
        final List<MemberSlice> members = List.of(
            new MemberSlice(PROXY_A, slice, registry, true)
        );
        return new GroupResolver(
            GROUP,
            members,
            Collections.emptyList(),
            Optional.of(new RecordingIndex(Optional.of(List.of()))),
            REPO_TYPE,
            Set.of(PROXY_A),
            negCache == null ? buildNegativeCache() : negCache,
            java.util.concurrent.ForkJoinPool.commonPool()
        );
    }

    private static Slice circuitOpenSlice(final String retryAfter) {
        return (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                    .header(com.auto1.pantera.http.UpstreamCircuitOpenException.HEADER, "true")
                    .header("Retry-After", retryAfter)
                    .build()
            );
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

    /**
     * A member that returns a 404 with a non-empty JSON body, matching the
     * shape {@code CachedNpmProxySlice} builds for a proxy/group version
     * lookup (WS8 Bug B5). Used to prove {@link GroupResolver}'s walk
     * terminal forwards a real member's honest body instead of discarding it.
     *
     * @param body Honest 404 JSON body the member returns
     */
    private static Slice honestNotFoundSlice(final String body) {
        return (line, headers, requestBody) ->
            CompletableFuture.completedFuture(ResponseBuilder.notFound().jsonBody(body).build());
    }

    /**
     * Same as {@link #honestNotFoundSlice(String)}, but also marks the 404
     * non-authoritative (WS8 Fix 2), exercising the {@code anyUnverified}
     * terminal branch instead of the plain one.
     *
     * @param body Honest 404 JSON body the member returns
     */
    private static Slice honestUnverifiedNotFoundSlice(final String body) {
        return (line, headers, requestBody) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.notFound()
                    .jsonBody(body)
                    .header(com.auto1.pantera.http.cache.NegativeCache.SKIP_HEADER, "true")
                    .build());
    }

    /**
     * A member that returns a 404 marked non-authoritative (as the npm proxy
     * does when it launders an upstream rate-limit / non-404 4xx into a 404 for
     * the multi-remote race). The group must NOT negative-cache such a 404.
     */
    private static Slice unverifiedNotFoundSlice() {
        return (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.notFound()
                    .header(com.auto1.pantera.http.cache.NegativeCache.SKIP_HEADER, "true")
                    .build());
    }

    /**
     * A member that answers NOT_FOUND on its first invocation and OK on
     * every one after -- simulates an artifact that genuinely did not
     * exist when a HEAD probe checked, then was published before a
     * following GET arrived (WS8 Bug 2 regression coverage).
     */
    private static Slice notFoundThenOkSlice() {
        final AtomicInteger calls = new AtomicInteger(0);
        return (line, headers, body) -> CompletableFuture.completedFuture(
            calls.getAndIncrement() == 0
                ? ResponseBuilder.notFound().build()
                : ResponseBuilder.ok().build()
        );
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
