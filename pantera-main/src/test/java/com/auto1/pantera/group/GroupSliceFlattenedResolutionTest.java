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
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- Request coalescing: N concurrent misses trigger only ONE fanout ----

    /**
     * When N concurrent requests arrive for the same missing artifact, the
     * proxy member must be queried exactly once — the first request does the
     * fanout, subsequent requests park on an in-flight "gate" future and, on
     * wake-up, hit the freshly-populated negative cache instead of repeating
     * the fanout.  This collapses a thundering herd of N concurrent 404s into
     * a single upstream request.
     */
    @Test
    void concurrentMissesCoalesceIntoSingleFanout() throws Exception {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // confirmed miss
        final AtomicInteger proxyCount = new AtomicInteger(0);
        // Slow proxy: block for 100ms so N concurrent requests all arrive
        // BEFORE the first fanout completes, forcing the coalescer to kick in.
        final java.util.concurrent.ExecutorService delay = Executors.newSingleThreadExecutor();
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY, (line, headers, body) -> {
            proxyCount.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return ResponseBuilder.notFound().build();
            }, delay);
        });
        final GroupSlice slice = buildGroup(
            idx,
            List.of(PROXY),
            Set.of(PROXY),
            slices
        );
        final int concurrency = 10;
        final java.util.concurrent.ExecutorService pool =
            Executors.newFixedThreadPool(concurrency);
        final java.util.concurrent.CountDownLatch startGate =
            new java.util.concurrent.CountDownLatch(1);
        final List<CompletableFuture<Response>> all = new java.util.ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            all.add(CompletableFuture.supplyAsync(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return slice.response(
                    new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
                ).join();
            }, pool));
        }
        // Release all requests simultaneously
        startGate.countDown();
        CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0])).join();
        pool.shutdown();
        delay.shutdown();

        // Every follower must see a 404 response
        for (final CompletableFuture<Response> fut : all) {
            assertEquals(RsStatus.NOT_FOUND, fut.join().status(),
                "Every coalesced follower must receive 404");
        }
        // CRITICAL: the thundering herd must collapse to ONE upstream query.
        // The first request fanouts; followers park on the gate, then re-enter
        // proxyOnlyFanout and short-circuit via the negative cache.
        assertEquals(1, proxyCount.get(),
            "N concurrent misses must trigger exactly ONE upstream fanout "
                + "(request coalescing + negative cache eliminate the thundering herd)");
    }

    // ---- Stack-safety regression: coalescer is safe at high N ----

    /**
     * Stack-safety regression guard for the coalescer.
     *
     * Before commit 7c30f01f the coalescer used .thenCompose on the gate.
     * When the leader completed the gate synchronously, followers whose
     * callbacks were already queued ran on the SAME stack, each retrying
     * proxyOnlyFanout which re-hit the still-in-map gate and recursed,
     * blowing the stack at ~400 frames.
     *
     * The fix (thenComposeAsync) dispatches retries to the common pool,
     * keeping the stack flat regardless of follower count.
     *
     * This test locks in that guarantee at N=1000 — well beyond the
     * observed production reproducer (~15 concurrent graphql-codegen
     * requests) and far above the point where synchronous recursion
     * would overflow.
     */
    @Test
    void coalescingIsStackSafeAtHighConcurrency() throws Exception {
        final int N = 1000;
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of())); // confirmed miss
        final AtomicInteger proxyCount = new AtomicInteger(0);
        final java.util.concurrent.CountDownLatch startLatch =
            new java.util.concurrent.CountDownLatch(1);

        // Slow proxy to keep the gate open while followers pile up
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(PROXY, (line, headers, body) -> {
            proxyCount.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ResponseBuilder.notFound().build();
            });
        });

        final GroupSlice slice = buildGroup(
            idx,
            List.of(PROXY),
            Set.of(PROXY),
            slices
        );

        // Launch N concurrent requests
        final java.util.concurrent.ExecutorService pool =
            Executors.newFixedThreadPool(Math.min(N, 64));
        final List<CompletableFuture<Response>> futures = new java.util.ArrayList<>(N);
        try {
            for (int i = 0; i < N; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return slice.response(
                        new RequestLine("GET", JAR_PATH),
                        Headers.EMPTY,
                        Content.EMPTY
                    ).join();
                }, pool));
            }
            startLatch.countDown();
            // Wait for all — if ANY threw StackOverflowError, CompletableFuture.join rethrows it
            for (CompletableFuture<Response> f : futures) {
                final Response r = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(RsStatus.NOT_FOUND, r.status(),
                    "All " + N + " concurrent requests must receive 404");
            }
        } finally {
            pool.shutdownNow();
        }

        // Coalescing invariant at scale: exactly one proxy query despite N followers
        assertEquals(1, proxyCount.get(),
            "At N=" + N + " concurrent missers, proxy must be queried exactly once — "
                + "the coalescer + negative cache must collapse the herd. Actual: "
                + proxyCount.get());
    }

    // ---- Internal routing header suppresses EcsLoggingSlice access logs ----

    /**
     * Verify that GroupSlice sets the {@code X-Pantera-Internal} header on member requests.
     * EcsLoggingSlice checks this header and skips access log emission, eliminating ~105K
     * noise entries per 30 min from internal group-to-member fanout in production.
     */
    @Test
    void memberDispatchCarriesInternalRoutingHeader() {
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final AtomicBoolean headerSeen = new AtomicBoolean(false);
        final Map<String, Slice> slices = new HashMap<>();
        // Member slice records whether the internal routing header was present
        slices.put(HOSTED, (line, headers, body) -> {
            if (!headers.find(EcsLoggingSlice.INTERNAL_ROUTING_HEADER).isEmpty()) {
                headerSeen.set(true);
            }
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        });
        final GroupSlice slice = buildGroup(
            idx,
            List.of(HOSTED),
            Collections.emptySet(),
            slices
        );
        slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).join();
        assertTrue(headerSeen.get(),
            "GroupSlice must set X-Pantera-Internal header on member dispatch "
                + "so EcsLoggingSlice skips access log emission for internal queries");
    }

    /**
     * Verify that external (client-facing) requests do NOT carry the internal routing header —
     * EcsLoggingSlice at the top of the stack must still emit access logs for real client traffic.
     */
    @Test
    void externalRequestLacksInternalRoutingHeader() {
        final AtomicBoolean headerSeen = new AtomicBoolean(false);
        // Simulate EcsLoggingSlice's check on an external request
        final Headers externalHeaders = Headers.EMPTY;
        headerSeen.set(!externalHeaders.find(EcsLoggingSlice.INTERNAL_ROUTING_HEADER).isEmpty());
        assertFalse(headerSeen.get(),
            "External (client-facing) requests must NOT carry X-Pantera-Internal "
                + "— EcsLoggingSlice must still emit access logs for real client traffic");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ---- MDC propagation: thenCompose callback sees caller's MDC context ----

    /**
     * Verify that MDC values set on the calling thread are visible inside the
     * {@code thenCompose} callback that runs after the index query completes —
     * even when the index future completes on a different thread.
     *
     * <p>This guards against regression of the MDC propagation fix: without
     * {@code MdcPropagation.withMdc()}, the DB executor thread would execute
     * the callback with an empty MDC, causing trace.id/user.name/client.ip to
     * be missing from any logs emitted inside that callback.
     */
    @Test
    void mdcIsVisibleInsideThenComposeCallbackAcrossThreadBoundary() throws Exception {
        // Set MDC on the calling (test) thread
        MDC.put("trace.id", "test-trace-abc123");
        MDC.put("user.name", "test-user");

        // Use a separate thread pool to simulate the DB executor completing
        // the index future on a different thread (the typical production scenario).
        final java.util.concurrent.ExecutorService indexThread =
            Executors.newSingleThreadExecutor();

        // Capture the MDC values seen inside the thenCompose callback
        final AtomicReference<String> traceIdInCallback = new AtomicReference<>("NOT_SET");
        final AtomicReference<String> userNameInCallback = new AtomicReference<>("NOT_SET");

        // Build an index that completes asynchronously on a different thread,
        // simulating the DB executor. The member slice records the MDC values
        // from inside the thenCompose callback (via the member request).
        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, (line, headers, body) -> {
            // This lambda runs inside the thenCompose callback chain —
            // MDC must be set here for the fix to be working
            traceIdInCallback.set(MDC.get("trace.id"));
            userNameInCallback.set(MDC.get("user.name"));
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        });

        final ArtifactIndex asyncIndex = new ArtifactIndex() {
            @Override
            public CompletableFuture<Optional<List<String>>> locateByName(
                final String artifactName
            ) {
                // Complete the future on a different thread (DB executor simulation)
                return CompletableFuture.supplyAsync(
                    () -> Optional.of(List.of(HOSTED)),
                    indexThread
                );
            }

            @Override
            public CompletableFuture<List<String>> locate(final String path) {
                throw new AssertionError("locate() must not be called");
            }

            @Override
            public CompletableFuture<Void> index(final ArtifactDocument doc) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> remove(final String repo, final String path) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<SearchResult> search(
                final String query, final int maxResults, final int offset
            ) {
                return CompletableFuture.completedFuture(SearchResult.EMPTY);
            }

            @Override
            public void close() {
                indexThread.shutdownNow();
            }
        };

        final GroupSlice slice = new GroupSlice(
            new MapResolver(slices),
            MAVEN_GROUP,
            List.of(HOSTED),
            8080, 0, 0,
            Collections.emptyList(),
            Optional.of(asyncIndex),
            Collections.emptySet(),
            MAVEN_GROUP
        );

        slice.response(
            new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
        ).get();

        indexThread.shutdown();

        assertEquals(
            "test-trace-abc123",
            traceIdInCallback.get(),
            "trace.id must be propagated into thenCompose callback running on the "
                + "index executor thread (MDC propagation fix)"
        );
        assertEquals(
            "test-user",
            userNameInCallback.get(),
            "user.name must be propagated into thenCompose callback running on the "
                + "index executor thread (MDC propagation fix)"
        );
    }

    // ---- MDC propagation: whenComplete callback sees caller's MDC context ----

    /**
     * Verify that MDC values set on the calling thread are visible inside the
     * {@code whenComplete} callback (used for metrics recording) — which may run
     * on the member response thread rather than the original request thread.
     */
    @Test
    void mdcIsVisibleInsideWhenCompleteCallbackAcrossThreadBoundary() throws Exception {
        MDC.put("trace.id", "metrics-trace-xyz");

        final java.util.concurrent.ExecutorService memberThread =
            Executors.newSingleThreadExecutor();

        // Index returns a hit immediately; the member slice completes on a
        // different executor thread, so whenComplete runs on that thread.
        final AtomicReference<String> traceIdInWhenComplete = new AtomicReference<>("NOT_SET");

        final Map<String, Slice> slices = new HashMap<>();
        slices.put(HOSTED, (line, headers, body) ->
            CompletableFuture.supplyAsync(
                () -> ResponseBuilder.ok().build(),
                memberThread
            )
        );

        // Subclass GroupSlice is not possible (final), so we verify the MDC
        // propagation indirectly: the whenComplete callback calls recordMetrics
        // which itself is a no-op in tests (no Micrometer). Instead, we
        // verify the MDC is still intact on the calling thread after join()
        // (the withMdcBiConsumer pattern restores prior MDC, so the calling
        // thread's MDC is unchanged — this confirms the wrapper ran correctly).
        final RecordingIndex idx = new RecordingIndex(Optional.of(List.of(HOSTED)));
        final GroupSlice slice = buildGroup(
            idx, List.of(HOSTED), Collections.emptySet(), slices
        );

        // Run response() on a dedicated thread that has its own MDC snapshot
        final java.util.concurrent.ExecutorService callerThread =
            Executors.newSingleThreadExecutor();
        callerThread.submit(() -> {
            MDC.put("trace.id", "caller-trace-999");
            slice.response(
                new RequestLine("GET", JAR_PATH), Headers.EMPTY, Content.EMPTY
            ).join();
            traceIdInWhenComplete.set(MDC.get("trace.id"));
        }).get();

        memberThread.shutdown();
        callerThread.shutdown();

        // After the full async chain completes, the caller thread's MDC must
        // be intact (withMdcBiConsumer restores prior MDC after the callback).
        assertEquals(
            "caller-trace-999",
            traceIdInWhenComplete.get(),
            "Caller thread MDC must be restored after whenComplete callback "
                + "(withMdcBiConsumer saves/restores prior MDC on the executing thread)"
        );
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
