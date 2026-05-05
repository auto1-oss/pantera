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
package com.auto1.pantera.prefetch;

import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.settings.runtime.CircuitBreakerTuning;
import com.auto1.pantera.settings.runtime.PrefetchTuning;
import io.vertx.core.MultiMap;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PrefetchCoordinator}.
 *
 * <p>All collaborators (cooldown, upstream, breaker) are hand-rolled fakes
 * — Mockito is not on the {@code pantera-main} classpath. The upstream
 * caller and cooldown gate are each {@link AtomicReference}-backed lambdas
 * so test bodies can flip behaviour mid-flight.</p>
 *
 * @since 2.2.0
 */
class PrefetchCoordinatorTest {

    private static final String REPO = "maven-central";
    private static final String REPO_TYPE = "maven-proxy";
    private static final String ECO = "maven";

    private PrefetchMetrics metrics;
    private PrefetchCircuitBreaker breaker;
    private NegativeCache negCache;
    private AtomicReference<CircuitBreakerTuning> cbTuning;
    private FakeCooldown cooldown;
    private FakeUpstream upstream;
    private AtomicReference<PrefetchTuning> tuningRef;
    private PrefetchCoordinator coordinator;

    @BeforeEach
    void setUp() {
        this.metrics = new PrefetchMetrics();
        this.cbTuning = new AtomicReference<>(new CircuitBreakerTuning(1_000_000, 60, 1));
        this.breaker = new PrefetchCircuitBreaker(this.cbTuning::get);
        this.negCache = new NegativeCache(new NegativeCacheConfig());
        this.cooldown = new FakeCooldown();
        this.upstream = new FakeUpstream();
        this.tuningRef = new AtomicReference<>(tuning(8, 4, 16, 2));
    }

    @AfterEach
    void tearDown() {
        if (this.coordinator != null) {
            this.coordinator.stop();
        }
    }

    @Test
    void submit_dropsOnQueueFull() {
        // Tiny queue; do NOT start workers so nothing drains. Submitting
        // capacity+1 tasks must produce exactly one queue_full drop.
        final int capacity = 4;
        this.tuningRef.set(tuning(8, 4, capacity, 1));
        this.coordinator = newCoordinator();
        // Intentionally do not call start().

        for (int idx = 0; idx < capacity; idx += 1) {
            submit(coord("g.id" + idx, "art", "1.0"));
        }
        // Capacity is full — next submit must be rejected by offer().
        submit(coord("g.id_overflow", "art", "1.0"));
        MatcherAssert.assertThat(
            this.metrics.droppedCount(REPO, PrefetchCoordinator.REASON_QUEUE_FULL),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "the rejected task must be removed from the in-flight set",
            this.coordinator.inFlightCount(),
            new IsEqual<>(capacity)
        );
        MatcherAssert.assertThat(
            this.metrics.dispatchedCount(REPO),
            new IsEqual<>((long) capacity)
        );
    }

    @Test
    void submit_dropsOnDedup() {
        // Cooldown stalls so the first submit stays in-flight. Worker has 1
        // thread; the second submit is rejected as dedup_in_flight.
        this.cooldown.delegate = task -> new CompletableFuture<>();
        this.coordinator = newCoordinator();
        // Don't even start workers — the in-flight set is populated on submit
        // (before queue.offer), so the dedup check fires regardless.
        final PrefetchTask task = task(coord("g.id", "art", "1.0"));
        this.coordinator.submit(task);
        this.coordinator.submit(task);
        MatcherAssert.assertThat(
            this.metrics.droppedCount(REPO, PrefetchCoordinator.REASON_DEDUP),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            this.metrics.dispatchedCount(REPO),
            new IsEqual<>(1L)
        );
    }

    @Test
    void submit_dropsOnSemaphoreSaturation() throws Exception {
        // global=1 — the second concurrent task can't acquire and is dropped.
        this.tuningRef.set(tuning(1, 1, 16, 1));
        this.cooldown.delegate = task -> CompletableFuture.completedFuture(false);
        // First upstream call hangs; second won't even be issued because
        // the global semaphore is held by the first.
        final CompletableFuture<Integer> hang = new CompletableFuture<>();
        this.upstream.delegate = task -> hang;
        this.coordinator = newCoordinator();
        this.coordinator.start();

        this.coordinator.submit(task(coord("g.first", "art", "1.0")));
        // Wait until the worker has picked up the first task and is holding
        // the global semaphore (i.e. inflight gauge has incremented).
        for (int idx = 0; idx < 100 && this.metrics.inflight(REPO).get() == 0L; idx += 1) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(
            "first task must be in-flight before we submit the second",
            this.metrics.inflight(REPO).get(),
            new IsEqual<>(1L)
        );
        this.coordinator.submit(task(coord("g.second", "art", "1.0")));
        // Wait for the second task to be drained and dropped.
        for (int idx = 0; idx < 100
            && this.metrics.droppedCount(REPO, PrefetchCoordinator.REASON_SEMAPHORE) == 0L;
            idx += 1
        ) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(
            this.metrics.droppedCount(REPO, PrefetchCoordinator.REASON_SEMAPHORE),
            new IsEqual<>(1L)
        );
        hang.complete(200);
    }

    @Test
    void submit_skipsBlockedDepAndPopulatesNegativeCache() throws Exception {
        this.cooldown.delegate = task -> CompletableFuture.completedFuture(true);
        this.upstream.delegate = task -> {
            throw new AssertionError("upstream must not be called when cooldown blocks");
        };
        this.coordinator = newCoordinator();
        this.coordinator.start();

        final PrefetchTask task = task(coord("com.fresh", "lib", "9.9.9"));
        this.coordinator.submit(task);
        awaitCompletedOrDropped(PrefetchCoordinator.OUTCOME_COOLDOWN_BLOCKED);
        MatcherAssert.assertThat(
            this.metrics.completedCount(REPO, PrefetchCoordinator.OUTCOME_COOLDOWN_BLOCKED),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "negative cache should hold the blocked coordinate",
            this.negCache.isKnown404(new NegativeCacheKey(
                REPO, REPO_TYPE, "com.fresh/lib", "9.9.9"
            )),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no upstream call",
            this.upstream.calls.get(),
            new IsEqual<>(0)
        );
    }

    @Test
    void submit_skipsKnown404FromNegativeCache() throws Exception {
        // Pre-populate the negative cache.
        this.negCache.cacheNotFound(new NegativeCacheKey(
            REPO, REPO_TYPE, "com.cached/missing", "0.0.1"
        ));
        final AtomicBoolean cooldownCalled = new AtomicBoolean(false);
        this.cooldown.delegate = task -> {
            cooldownCalled.set(true);
            return CompletableFuture.completedFuture(false);
        };
        this.upstream.delegate = task -> {
            throw new AssertionError("upstream must not be called for cached 404");
        };
        this.coordinator = newCoordinator();
        this.coordinator.start();

        this.coordinator.submit(task(coord("com.cached", "missing", "0.0.1")));
        awaitCompletedOrDropped(PrefetchCoordinator.OUTCOME_NEG_404);
        MatcherAssert.assertThat(
            this.metrics.completedCount(REPO, PrefetchCoordinator.OUTCOME_NEG_404),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "cooldown must not be evaluated when the 404 is already cached",
            cooldownCalled.get(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(this.upstream.calls.get(), new IsEqual<>(0));
    }

    @Test
    void submit_firesAsyncGetOnAllowedDep() throws Exception {
        this.cooldown.delegate = task -> CompletableFuture.completedFuture(false);
        this.upstream.delegate = task -> CompletableFuture.completedFuture(200);
        this.coordinator = newCoordinator();
        this.coordinator.start();

        this.coordinator.submit(task(coord("com.ok", "lib", "1.2.3")));
        awaitCompletedOrDropped(PrefetchCoordinator.OUTCOME_FETCHED_200);
        MatcherAssert.assertThat(
            this.metrics.completedCount(REPO, PrefetchCoordinator.OUTCOME_FETCHED_200),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(this.upstream.calls.get(), new IsEqual<>(1));
    }

    @Test
    void circuitBreakerOpenSkipsAllSubmissions() {
        // Start with a tuning whose threshold trips on a single drop.
        this.cbTuning.set(new CircuitBreakerTuning(0, 1, 5));
        // Trip the breaker manually.
        this.breaker.recordDrop();
        MatcherAssert.assertThat(this.breaker.isOpen(), new IsEqual<>(true));

        this.cooldown.delegate = task -> {
            throw new AssertionError("cooldown must not be evaluated when breaker is open");
        };
        this.upstream.delegate = task -> {
            throw new AssertionError("upstream must not be called when breaker is open");
        };
        this.coordinator = newCoordinator();
        // Don't start workers — the breaker check fires synchronously in submit().
        this.coordinator.submit(task(coord("g.id", "art", "1.0")));
        this.coordinator.submit(task(coord("g.id", "art", "1.1")));
        MatcherAssert.assertThat(
            this.metrics.droppedCount(REPO, PrefetchCoordinator.REASON_CIRCUIT_OPEN),
            new IsEqual<>(2L)
        );
        MatcherAssert.assertThat(
            this.metrics.dispatchedCount(REPO),
            new IsEqual<>(0L)
        );
    }

    @Test
    void submit_dropsOnPerHostSemaphoreSaturation() throws Exception {
        // perUpstreamConcurrency=1, globalConcurrency=8 — proves the
        // per-host cap is independent of the global cap. Two tasks per
        // host: 1 of each (4 total) holds the per-host permit; the
        // other 1 of each (2 total) drops with semaphore_saturated.
        this.tuningRef.set(tuning(8, 1, 16, 4));
        this.cooldown.delegate = task -> CompletableFuture.completedFuture(false);
        // First two upstream calls (one per host) hang; subsequent
        // submits to the same host can't acquire the per-host permit.
        final CompletableFuture<Integer> hangA = new CompletableFuture<>();
        final CompletableFuture<Integer> hangB = new CompletableFuture<>();
        final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Integer>> hangs =
            new java.util.concurrent.ConcurrentHashMap<>();
        hangs.put("repo-a.example.com", hangA);
        hangs.put("repo-b.example.com", hangB);
        final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> hits =
            new java.util.concurrent.ConcurrentHashMap<>();
        this.upstream.delegate = task -> {
            final String host = java.net.URI.create(task.upstreamUrl()).getHost();
            hits.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
            return hangs.get(host);
        };
        this.coordinator = newCoordinator();
        this.coordinator.start();

        this.coordinator.submit(taskWithUrl(coord("g.a1", "art", "1.0"),
            "https://repo-a.example.com/maven2", "repo-a"));
        this.coordinator.submit(taskWithUrl(coord("g.b1", "art", "1.0"),
            "https://repo-b.example.com/maven2", "repo-b"));
        // Wait until both first-tasks are in-flight on their respective hosts.
        for (int idx = 0; idx < 200
            && (this.metrics.inflight("repo-a").get() == 0L
                || this.metrics.inflight("repo-b").get() == 0L);
            idx += 1
        ) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(
            "host A first task in-flight",
            this.metrics.inflight("repo-a").get(), new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "host B first task in-flight",
            this.metrics.inflight("repo-b").get(), new IsEqual<>(1L)
        );
        // Submit a SECOND task per host — must drop on per-host saturation.
        this.coordinator.submit(taskWithUrl(coord("g.a2", "art", "1.0"),
            "https://repo-a.example.com/maven2", "repo-a"));
        this.coordinator.submit(taskWithUrl(coord("g.b2", "art", "1.0"),
            "https://repo-b.example.com/maven2", "repo-b"));
        // Wait for both drops.
        for (int idx = 0; idx < 200
            && (this.metrics.droppedCount("repo-a", PrefetchCoordinator.REASON_SEMAPHORE) == 0L
                || this.metrics.droppedCount("repo-b", PrefetchCoordinator.REASON_SEMAPHORE) == 0L);
            idx += 1
        ) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(
            "host A second task dropped on per-host semaphore",
            this.metrics.droppedCount("repo-a", PrefetchCoordinator.REASON_SEMAPHORE),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "host B second task dropped on per-host semaphore",
            this.metrics.droppedCount("repo-b", PrefetchCoordinator.REASON_SEMAPHORE),
            new IsEqual<>(1L)
        );
        // Each host's upstream was hit exactly once (the second submit
        // was dropped before reaching upstream.get()).
        MatcherAssert.assertThat(hits.get("repo-a.example.com").get(), new IsEqual<>(1));
        MatcherAssert.assertThat(hits.get("repo-b.example.com").get(), new IsEqual<>(1));
        hangA.complete(200);
        hangB.complete(200);
    }

    @Test
    void perUpstreamCap_isResolvedPerEcosystem() throws Exception {
        // Global perUpstream is high (16) but npm-specific cap is forced to
        // 1 via the per-ecosystem map. A maven task to host A and an npm task
        // to host B both go in-flight; a SECOND npm task to host B must drop
        // on the per-host semaphore (cap=1 for npm), while a SECOND maven
        // task to host A is allowed (cap=16 for maven).
        this.tuningRef.set(new PrefetchTuning(
            true, 8, 16,
            java.util.Map.of("maven", 16, "gradle", 16, "npm", 1),
            16, 4
        ));
        this.cooldown.delegate = task -> CompletableFuture.completedFuture(false);
        final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> hits =
            new java.util.concurrent.ConcurrentHashMap<>();
        final CompletableFuture<Integer> hangNpm = new CompletableFuture<>();
        final CompletableFuture<Integer> hangMaven1 = new CompletableFuture<>();
        final CompletableFuture<Integer> hangMaven2 = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicInteger mavenSeq =
            new java.util.concurrent.atomic.AtomicInteger();
        this.upstream.delegate = task -> {
            final String host = java.net.URI.create(task.upstreamUrl()).getHost();
            hits.computeIfAbsent(host, h -> new AtomicInteger()).incrementAndGet();
            if ("npm.example.com".equals(host)) {
                return hangNpm;
            }
            return mavenSeq.getAndIncrement() == 0 ? hangMaven1 : hangMaven2;
        };
        this.coordinator = newCoordinator();
        this.coordinator.start();

        // Maven task to host A — uses maven cap (16).
        this.coordinator.submit(taskWithUrl(
            Coordinate.maven("g.a", "art", "1.0"),
            "https://maven.example.com/maven2", "maven-repo"
        ));
        // npm task to host B — uses npm cap (1).
        this.coordinator.submit(new PrefetchTask(
            "npm-repo", "npm-proxy",
            "https://npm.example.com",
            Coordinate.npm("react", "18.0.0"),
            io.vertx.core.MultiMap.caseInsensitiveMultiMap(),
            Instant.now()
        ));
        // Wait for both first tasks to be in-flight.
        for (int idx = 0; idx < 200
            && (this.metrics.inflight("maven-repo").get() == 0L
                || this.metrics.inflight("npm-repo").get() == 0L);
            idx += 1
        ) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(this.metrics.inflight("maven-repo").get(), new IsEqual<>(1L));
        MatcherAssert.assertThat(this.metrics.inflight("npm-repo").get(), new IsEqual<>(1L));

        // SECOND npm task to host B — must drop on per-host semaphore (cap=1).
        this.coordinator.submit(new PrefetchTask(
            "npm-repo", "npm-proxy",
            "https://npm.example.com",
            Coordinate.npm("lodash", "4.17.0"),
            io.vertx.core.MultiMap.caseInsensitiveMultiMap(),
            Instant.now()
        ));
        // SECOND maven task to host A — allowed (cap=16).
        this.coordinator.submit(taskWithUrl(
            Coordinate.maven("g.a", "art2", "1.0"),
            "https://maven.example.com/maven2", "maven-repo"
        ));
        // Wait for the npm drop AND for the second maven task to enter
        // upstream (hits["maven.example.com"] reaches 2).
        for (int idx = 0; idx < 200
            && (this.metrics.droppedCount("npm-repo", PrefetchCoordinator.REASON_SEMAPHORE) == 0L
                || hits.getOrDefault("maven.example.com", new AtomicInteger()).get() < 2);
            idx += 1
        ) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(
            "npm second task must drop on per-host semaphore (cap=1)",
            this.metrics.droppedCount("npm-repo", PrefetchCoordinator.REASON_SEMAPHORE),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "maven second task must NOT drop (cap=16 leaves headroom)",
            this.metrics.droppedCount("maven-repo", PrefetchCoordinator.REASON_SEMAPHORE),
            new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "maven host hit twice (both submissions reached upstream.get())",
            hits.get("maven.example.com").get(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "npm host hit only once (the second was dropped before upstream.get())",
            hits.get("npm.example.com").get(), new IsEqual<>(1)
        );
        hangNpm.complete(200);
        hangMaven1.complete(200);
        hangMaven2.complete(200);
    }

    @Test
    void submit_recordsTimeoutOutcomeOnUpstreamHang() throws Exception {
        // Wire a never-completing upstream future, but inject a 200ms
        // upstream timeout so the test resolves in real time. The
        // outcome must be OUTCOME_TIMEOUT (not OUTCOME_UPSTREAM_5XX),
        // which proves that the CompletionException-wrapped
        // TimeoutException is unwrapped correctly.
        this.cooldown.delegate = task -> CompletableFuture.completedFuture(false);
        this.upstream.delegate = task -> new CompletableFuture<>();
        this.coordinator = new PrefetchCoordinator(
            this.metrics, this.breaker, this.negCache,
            this.cooldown, this.upstream,
            this.tuningRef::get,
            Duration.ofSeconds(2),
            Duration.ofMillis(200)
        );
        this.coordinator.start();

        this.coordinator.submit(task(coord("g.timeout", "art", "1.0")));
        awaitCompletedOrDropped(PrefetchCoordinator.OUTCOME_TIMEOUT);
        MatcherAssert.assertThat(
            "upstream hang must record OUTCOME_TIMEOUT",
            this.metrics.completedCount(REPO, PrefetchCoordinator.OUTCOME_TIMEOUT),
            new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "must NOT mis-classify the timeout as a 5xx",
            this.metrics.completedCount(REPO, PrefetchCoordinator.OUTCOME_UPSTREAM_5XX),
            new IsEqual<>(0L)
        );
        // After the timeout fires, the in-flight slot is freed.
        for (int idx = 0; idx < 100 && this.coordinator.inFlightCount() > 0; idx += 1) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(
            "in-flight count returns to 0 after timeout",
            this.coordinator.inFlightCount(), new IsEqual<>(0)
        );
    }

    @Test
    void submit_treatsCooldownTimeoutAsNotBlocked() throws Exception {
        // A cooldown service that hangs forever must NOT pin the worker.
        // Inject 100ms cooldown timeout; the coordinator falls through to
        // upstream (which returns 200 immediately).
        this.cooldown.delegate = task -> new CompletableFuture<>();
        this.upstream.delegate = task -> CompletableFuture.completedFuture(200);
        this.coordinator = new PrefetchCoordinator(
            this.metrics, this.breaker, this.negCache,
            this.cooldown, this.upstream,
            this.tuningRef::get,
            Duration.ofMillis(100),
            Duration.ofSeconds(2)
        );
        this.coordinator.start();

        this.coordinator.submit(task(coord("g.cdtimeout", "art", "1.0")));
        awaitCompletedOrDropped(PrefetchCoordinator.OUTCOME_FETCHED_200);
        MatcherAssert.assertThat(
            "cooldown timeout falls through to upstream",
            this.metrics.completedCount(REPO, PrefetchCoordinator.OUTCOME_FETCHED_200),
            new IsEqual<>(1L)
        );
    }

    @Test
    void settingsChangeResizesSemaphores() throws Exception {
        // Start with concurrency=2 — would only allow 2 concurrent tasks.
        this.tuningRef.set(tuning(2, 2, 64, 4));
        // Cooldown is fast; upstream blocks on a latch we control so we can
        // observe how many concurrent acquires happen.
        this.cooldown.delegate = task -> CompletableFuture.completedFuture(false);
        final int total = 8;
        final CountDownLatch hold = new CountDownLatch(1);
        final AtomicInteger concurrent = new AtomicInteger(0);
        final AtomicInteger peak = new AtomicInteger(0);
        this.upstream.delegate = task -> {
            final int curr = concurrent.incrementAndGet();
            peak.updateAndGet(prev -> Math.max(prev, curr));
            return CompletableFuture.supplyAsync(() -> {
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return 200;
            });
        };
        this.coordinator = newCoordinator();
        this.coordinator.start();

        // Bump concurrency to 8 BEFORE submitting so all 8 can acquire.
        this.tuningRef.set(tuning(8, 8, 64, 4));
        this.coordinator.applyTuning(this.tuningRef.get());

        for (int idx = 0; idx < total; idx += 1) {
            this.coordinator.submit(task(coord("g.id" + idx, "art", "1.0")));
        }
        // Wait until all 8 acquired.
        for (int idx = 0; idx < 200 && peak.get() < total; idx += 1) {
            Thread.sleep(10L);
        }
        MatcherAssert.assertThat(
            "after resizing to concurrency=8, all 8 tasks should run concurrently",
            peak.get(),
            new IsEqual<>(total)
        );
        MatcherAssert.assertThat(
            "no semaphore_saturated drops after the resize",
            this.metrics.droppedCount(REPO, PrefetchCoordinator.REASON_SEMAPHORE),
            new IsEqual<>(0L)
        );
        hold.countDown();
    }

    @Test
    void applyTuning_drainedTasksAreCountedPerRepo() {
        // Don't start workers — pending tasks must stay in the queue until
        // applyTuning drains them, so we can observe the drop count.
        // Capacity 8 lets two repos contribute three tasks each.
        this.tuningRef.set(tuning(4, 4, 8, 1));
        this.coordinator = newCoordinator();
        // Intentionally do not call start() — tasks pile up in the queue.

        for (int idx = 0; idx < 3; idx += 1) {
            this.coordinator.submit(taskWithUrl(
                coord("g.id" + idx, "art", "1.0"),
                "https://repo1.maven.org/maven2", "repoA"
            ));
            this.coordinator.submit(taskWithUrl(
                coord("g.id" + idx, "art", "1.0"),
                "https://repo1.maven.org/maven2", "repoB"
            ));
        }
        MatcherAssert.assertThat(this.coordinator.queueDepth(), new IsEqual<>(6));

        // Trigger a tuning swap with a different snapshot — queueCapacity
        // change forces buildRuntime, which drains the old queue.
        this.coordinator.applyTuning(tuning(4, 4, 16, 1));

        MatcherAssert.assertThat(
            "old queue items must be drained — new runtime starts empty",
            this.coordinator.queueDepth(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "repoA's three tasks counted as tuning_swap drops",
            this.metrics.droppedCount("repoA", PrefetchCoordinator.REASON_TUNING_SWAP),
            new IsEqual<>(3L)
        );
        MatcherAssert.assertThat(
            "repoB's three tasks counted as tuning_swap drops",
            this.metrics.droppedCount("repoB", PrefetchCoordinator.REASON_TUNING_SWAP),
            new IsEqual<>(3L)
        );
        MatcherAssert.assertThat(
            "in-flight set is cleaned up so the same coordinates can be re-submitted later",
            this.coordinator.inFlightCount(),
            new IsEqual<>(0)
        );
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private PrefetchCoordinator newCoordinator() {
        return new PrefetchCoordinator(
            this.metrics,
            this.breaker,
            this.negCache,
            this.cooldown,
            this.upstream,
            this.tuningRef::get
        );
    }

    private void submit(final Coordinate coord) {
        this.coordinator.submit(task(coord));
    }

    private static PrefetchTask task(final Coordinate coord) {
        return new PrefetchTask(
            REPO, REPO_TYPE,
            "https://repo1.maven.org/maven2",
            coord, MultiMap.caseInsensitiveMultiMap(), Instant.now()
        );
    }

    private static PrefetchTask taskWithUrl(
        final Coordinate coord, final String upstreamUrl, final String repo
    ) {
        return new PrefetchTask(
            repo, REPO_TYPE, upstreamUrl, coord,
            MultiMap.caseInsensitiveMultiMap(), Instant.now()
        );
    }

    private static Coordinate coord(final String group, final String name, final String version) {
        return Coordinate.maven(group, name, version);
    }

    /**
     * Build a {@link PrefetchTuning} where every ecosystem inherits the
     * supplied {@code perUpstreamConcurrency}. The pre-2.2.0-perf-pack
     * tests in this class expect the global per-upstream cap to apply
     * uniformly; this helper keeps that contract while the per-ecosystem
     * map is the production default.
     */
    private static PrefetchTuning tuning(
        final int globalConcurrency,
        final int perUpstreamConcurrency,
        final int queueCapacity,
        final int workerThreads
    ) {
        return new PrefetchTuning(
            true, globalConcurrency, perUpstreamConcurrency,
            java.util.Map.of(
                "maven", perUpstreamConcurrency,
                "gradle", perUpstreamConcurrency,
                "npm", perUpstreamConcurrency
            ),
            queueCapacity, workerThreads
        );
    }

    private void awaitCompletedOrDropped(final String outcome) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (this.metrics.completedCount(REPO, outcome) > 0L) {
                return;
            }
            Thread.sleep(10L);
        }
    }

    private static final class FakeCooldown implements PrefetchCoordinator.CooldownGate {
        volatile java.util.function.Function<PrefetchTask, CompletableFuture<Boolean>>
            delegate = task -> CompletableFuture.completedFuture(false);

        @Override
        public CompletableFuture<Boolean> isBlocked(final PrefetchTask task) {
            return this.delegate.apply(task);
        }
    }

    private static final class FakeUpstream implements PrefetchCoordinator.UpstreamCaller {
        final AtomicInteger calls = new AtomicInteger(0);
        volatile java.util.function.Function<PrefetchTask, CompletableFuture<Integer>>
            delegate = task -> CompletableFuture.completedFuture(200);

        @Override
        public CompletableFuture<Integer> get(final PrefetchTask task) {
            this.calls.incrementAndGet();
            return this.delegate.apply(task);
        }
    }
}
