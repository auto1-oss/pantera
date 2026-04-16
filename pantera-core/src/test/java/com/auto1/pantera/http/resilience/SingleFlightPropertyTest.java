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
package com.auto1.pantera.http.resilience;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Property-style tests for {@link SingleFlight}. Covers the five invariants
 * listed in WI-05 DoD (§12 of {@code docs/analysis/v2.2-target-architecture.md}):
 * coalescing, cancellation isolation, zombie eviction, exception propagation,
 * and stack-flat synchronous completion.
 */
final class SingleFlightPropertyTest {

    /**
     * Dedicated thread pool for the {@link SingleFlight} under test. A fresh
     * pool per test avoids cross-test contamination for the cancellation and
     * stack-safety properties.
     */
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        this.executor = Executors.newFixedThreadPool(16, r -> {
            final Thread t = new Thread(r, "sf-test");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        this.executor.shutdownNow();
    }

    /**
     * N = 1000 concurrent {@code load(k, loader)} calls for the same key must
     * invoke the loader exactly once. All 1000 callers receive the same value.
     *
     * <p>The coalescer invalidates its entry on loader completion (to allow the
     * next {@code load} for the same key to refetch). The test must therefore
     * hold the loader uncompleted until <em>every</em> caller has invoked
     * {@link SingleFlight#load}; otherwise a late caller would miss the shared
     * entry and spawn a second loader — which would be correct SingleFlight
     * behaviour, just not the property we are asserting.
     *
     * <p>The load-issuing phase is separated from the join phase: a dedicated
     * 1000-thread pool is used for load issuance so no thread blocks a sibling
     * from reaching {@code sf.load}. Once every caller is attached, the loader
     * is released and every future is awaited.
     */
    @Test
    @Timeout(30)
    void coalescesNConcurrentLoads() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(30), 1024, this.executor
        );
        final int callers = 1_000;
        final AtomicInteger loaderInvocations = new AtomicInteger(0);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch submitGate = new CountDownLatch(1);
        final CountDownLatch allCalledLoad = new CountDownLatch(callers);

        // One thread per caller so `load()` issuance is truly parallel. The
        // threads only issue the load and return — they do NOT join the
        // future, so the pool size does not need to absorb 1000 blocked
        // join()s.
        final ExecutorService submitters = Executors.newFixedThreadPool(callers);
        final List<CompletableFuture<Integer>> futures = new ArrayList<>(callers);
        final Object futuresLock = new Object();
        try {
            for (int i = 0; i < callers; i++) {
                submitters.execute(() -> {
                    try {
                        submitGate.await();
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    final CompletableFuture<Integer> f = sf.load(
                        "shared-key",
                        () -> {
                            loaderInvocations.incrementAndGet();
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    release.await();
                                } catch (final InterruptedException iex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(iex);
                                }
                                return 42;
                            }, this.executor);
                        }
                    );
                    synchronized (futuresLock) {
                        futures.add(f);
                    }
                    allCalledLoad.countDown();
                });
            }
            submitGate.countDown();
            MatcherAssert.assertThat(
                "all " + callers + " threads called sf.load",
                allCalledLoad.await(20, TimeUnit.SECONDS), is(true)
            );
            release.countDown();
            final List<CompletableFuture<Integer>> snapshot;
            synchronized (futuresLock) {
                snapshot = new ArrayList<>(futures);
            }
            MatcherAssert.assertThat(snapshot.size(), equalTo(callers));
            for (final CompletableFuture<Integer> fut : snapshot) {
                MatcherAssert.assertThat(
                    fut.get(15, TimeUnit.SECONDS), equalTo(42)
                );
            }
        } finally {
            submitters.shutdownNow();
        }

        MatcherAssert.assertThat(
            "N=" + callers + " concurrent loads must trigger exactly ONE loader",
            loaderInvocations.get(), equalTo(1)
        );
    }

    /**
     * 100 callers; cancel 50 of them mid-load; remaining 50 receive the value.
     * The loader ran exactly once and was not aborted by any cancellation.
     */
    @Test
    @Timeout(30)
    void cancellationDoesNotAbortOthers() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(10), 1024, this.executor
        );
        final int callers = 100;
        final AtomicInteger loaderInvocations = new AtomicInteger(0);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch loaderStarted = new CountDownLatch(1);

        final List<CompletableFuture<Integer>> futures = new ArrayList<>(callers);
        for (int i = 0; i < callers; i++) {
            futures.add(sf.load("shared-key", () -> {
                loaderInvocations.incrementAndGet();
                loaderStarted.countDown();
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        release.await();
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    return 99;
                }, this.executor);
            }));
        }
        MatcherAssert.assertThat(
            "loader started before cancellations",
            loaderStarted.await(5, TimeUnit.SECONDS), is(true)
        );
        // Cancel the first 50 callers' futures.
        for (int i = 0; i < 50; i++) {
            MatcherAssert.assertThat(
                "cancellation accepted", futures.get(i).cancel(true), is(true)
            );
        }
        // Let the loader finish.
        release.countDown();

        for (int i = 0; i < 50; i++) {
            final CompletableFuture<Integer> fut = futures.get(i);
            MatcherAssert.assertThat(
                "cancelled future reports cancelled", fut.isCancelled(), is(true)
            );
        }
        for (int i = 50; i < callers; i++) {
            MatcherAssert.assertThat(
                "non-cancelled caller sees value",
                futures.get(i).get(10, TimeUnit.SECONDS), equalTo(99)
            );
        }
        MatcherAssert.assertThat(
            "loader ran exactly once despite 50 cancellations",
            loaderInvocations.get(), equalTo(1)
        );
    }

    /**
     * A loader that never completes is held only for {@code inflightTtl};
     * after that window the entry is evicted and the next {@link
     * SingleFlight#load} invokes a fresh loader.
     *
     * <p>Zombie eviction is implemented by {@code orTimeout(inflightTtl)} on
     * the wrapped loader future: once the TTL expires, the wrapper completes
     * exceptionally with {@link TimeoutException}, which triggers the
     * {@code whenCompleteAsync(invalidate)} hook and frees the slot. We wait
     * past the TTL plus a buffer for the scheduler to fire.
     */
    @Test
    @Timeout(10)
    void zombieEvictedAfterTtl() throws Exception {
        final Duration ttl = Duration.ofMillis(200);
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            ttl, 1024, this.executor
        );
        final AtomicInteger loaderInvocations = new AtomicInteger(0);
        // A loader that never completes — stays "in-flight" until the
        // orTimeout wrapper fires.
        final CompletableFuture<Integer> zombie = sf.load("zombie", () -> {
            loaderInvocations.incrementAndGet();
            return new CompletableFuture<Integer>();
        });
        MatcherAssert.assertThat(loaderInvocations.get(), equalTo(1));
        // The wrapper future (inside SingleFlight) fires TimeoutException at
        // the TTL boundary; the invalidate callback then runs on the executor.
        // Expect a TimeoutException at the caller side too.
        final ExecutionException ee = assertThrows(
            ExecutionException.class,
            () -> zombie.get(ttl.toMillis() * 10, TimeUnit.MILLISECONDS)
        );
        MatcherAssert.assertThat(
            rootCause(ee), Matchers.instanceOf(TimeoutException.class)
        );
        // Small settle so the whenCompleteAsync(invalidate) hook has run.
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (sf.inFlightCount() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        MatcherAssert.assertThat(
            "zombie entry was invalidated", sf.inFlightCount(), equalTo(0)
        );
        // Second load for the same key must trigger a fresh loader.
        final CompletableFuture<Integer> second = sf.load("zombie", () -> {
            loaderInvocations.incrementAndGet();
            return CompletableFuture.completedFuture(7);
        });
        MatcherAssert.assertThat(second.get(5, TimeUnit.SECONDS), equalTo(7));
        MatcherAssert.assertThat(
            "zombie was evicted; fresh loader ran for the second load",
            loaderInvocations.get(), equalTo(2)
        );
    }

    /**
     * When the loader completes exceptionally, every waiter sees the same
     * exception. The entry is then removed so the next {@link
     * SingleFlight#load} retries with a fresh loader invocation.
     */
    @Test
    @Timeout(10)
    void loaderFailurePropagatesToAllWaiters() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(10), 1024, this.executor
        );
        final int waiters = 20;
        final AtomicInteger loaderInvocations = new AtomicInteger(0);
        final CountDownLatch release = new CountDownLatch(1);
        final RuntimeException failure = new RuntimeException("upstream down");

        final List<CompletableFuture<Integer>> futures = new ArrayList<>(waiters);
        for (int i = 0; i < waiters; i++) {
            futures.add(sf.load("fail-key", () -> {
                loaderInvocations.incrementAndGet();
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        release.await();
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    throw failure;
                }, this.executor);
            }));
        }
        release.countDown();
        for (final CompletableFuture<Integer> fut : futures) {
            final ExecutionException ee = assertThrows(
                ExecutionException.class,
                () -> fut.get(5, TimeUnit.SECONDS)
            );
            // CompletableFuture.supplyAsync wraps thrown exceptions in
            // CompletionException; whatever wrapper Caffeine adds, the root
            // cause must be our sentinel.
            Throwable root = ee.getCause();
            while (root != null && root.getCause() != null && root != root.getCause()) {
                if (root == failure) {
                    break;
                }
                root = root.getCause();
            }
            MatcherAssert.assertThat(
                "each waiter sees the loader's exception at the root",
                root, Matchers.is(failure)
            );
        }
        MatcherAssert.assertThat(
            "loader ran once for all waiters despite failure",
            loaderInvocations.get(), equalTo(1)
        );
        // Entry must be removed: the next load triggers a new loader call.
        // Small settle so the invalidation callback has run on the executor.
        Thread.sleep(100);
        final CompletableFuture<Integer> retry = sf.load("fail-key", () -> {
            loaderInvocations.incrementAndGet();
            return CompletableFuture.completedFuture(11);
        });
        MatcherAssert.assertThat(retry.get(5, TimeUnit.SECONDS), equalTo(11));
        MatcherAssert.assertThat(
            "entry invalidated on failure; next load ran a fresh loader",
            loaderInvocations.get(), equalTo(2)
        );
    }

    /**
     * Stack-safety regression guard.
     *
     * <p>Before WI-05, {@code GroupSlice.inFlightFanouts} chained {@code
     * .thenCompose} on a shared gate future. When the leader completed the
     * gate synchronously, all queued {@code thenCompose} callbacks ran on the
     * leader's stack — with N &ge; ~400 followers this overflowed the stack
     * (commit {@code ccc155f6} fixed the leak via {@code thenComposeAsync}).
     *
     * <p>This test locks in the same guarantee for {@link SingleFlight}: the
     * 500 followers' {@code thenCompose} callbacks must NOT run on the
     * leader's stack, regardless of whether the loader completes
     * synchronously. We trigger the worst case: loader returns an already-
     * completed future, so Caffeine has the shared future "done" the moment
     * it's installed; followers attaching {@code thenCompose} after that
     * point would, without executor dispatch, run on the caller's own stack
     * — still not a stack-overflow, but the regression shape is identical
     * and worth guarding. A 500-deep thenCompose chain on a single stack is
     * the SOE that matters; we emulate that by having each follower's
     * callback itself dispatch another thenCompose.
     */
    @Test
    @Timeout(30)
    void stackFlatUnderSynchronousCompletion() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(10), 1024, this.executor
        );
        final int followers = 500;
        // Leader completes synchronously — the worst case for the old bug.
        final CompletableFuture<Integer> shared = sf.load(
            "sync-key", () -> CompletableFuture.completedFuture(123)
        );
        // Wait for the leader's future to settle before attaching followers —
        // this puts us in the "future already done when I call thenCompose"
        // regime that triggered the original stack bug.
        MatcherAssert.assertThat(shared.get(5, TimeUnit.SECONDS), equalTo(123));

        // 500 followers each attach thenCompose chains on fresh load() calls.
        // Since each load() returns a NEW forwarded CompletableFuture completed
        // via whenCompleteAsync(executor), the thenCompose callbacks must not
        // all collapse onto one stack.
        final List<CompletableFuture<Integer>> chain = new ArrayList<>(followers);
        for (int i = 0; i < followers; i++) {
            final CompletionStage<Integer> f = sf
                .load("sync-key", () -> CompletableFuture.completedFuture(123))
                .thenCompose(v -> CompletableFuture.completedFuture(v + 1))
                .thenCompose(v -> CompletableFuture.completedFuture(v + 1));
            chain.add(f.toCompletableFuture());
        }
        for (final CompletableFuture<Integer> fut : chain) {
            // Any StackOverflowError on the leader's stack would have been
            // rethrown through CompletableFuture.get — the explicit type check
            // is the regression guard.
            try {
                MatcherAssert.assertThat(
                    fut.get(10, TimeUnit.SECONDS), equalTo(125)
                );
            } catch (final ExecutionException ex) {
                if (ex.getCause() instanceof StackOverflowError) {
                    throw new AssertionError(
                        "StackOverflowError on follower chain "
                            + "— SingleFlight re-introduced the ccc155f6 bug",
                        ex.getCause()
                    );
                }
                throw ex;
            }
        }
    }

    /**
     * Additional guard: {@link SingleFlight#load} never throws at the call
     * site. A loader supplier that itself throws a {@link RuntimeException}
     * must surface only inside the returned future.
     */
    @Test
    @Timeout(5)
    void supplierThrowSurfacesAsFailedFuture() {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(5), 1024, this.executor
        );
        final RuntimeException bang = new IllegalStateException("boom");
        final CompletableFuture<Integer> result = sf.load(
            "thrower", () -> {
                throw bang;
            }
        );
        final ExecutionException ee = assertThrows(
            ExecutionException.class,
            () -> result.get(2, TimeUnit.SECONDS)
        );
        Throwable cause = ee.getCause();
        while (cause != null && cause != bang && cause.getCause() != null
            && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        MatcherAssert.assertThat(cause, is((Throwable) bang));
    }

    /**
     * Cancelling a returned future must not cancel the underlying loader
     * future observed by callers who did not cancel.
     */
    @Test
    @Timeout(10)
    void cancellingOneCallerDoesNotCompleteOthersAsCancelled() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(5), 1024, this.executor
        );
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Integer> first = sf.load("k", () ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    release.await();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return 7;
            }, this.executor)
        );
        final CompletableFuture<Integer> second =
            sf.load("k", () -> CompletableFuture.completedFuture(-1));
        first.cancel(true);
        release.countDown();
        MatcherAssert.assertThat(
            "non-cancelled follower completes with value",
            second.get(5, TimeUnit.SECONDS), equalTo(7)
        );
        // Sanity: first is cancelled, second is not.
        MatcherAssert.assertThat(first.isCancelled(), is(true));
        MatcherAssert.assertThat(second.isCancelled(), is(false));
    }

    /**
     * Explicit {@link SingleFlight#invalidate} removes an entry without
     * completing it: the loader's future continues independently, but a
     * subsequent {@link SingleFlight#load} for the same key starts afresh.
     */
    @Test
    @Timeout(5)
    void invalidateAllowsSubsequentFreshLoad() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(10), 1024, this.executor
        );
        final AtomicInteger loaderInvocations = new AtomicInteger(0);
        final CompletableFuture<Integer> unfinished = new CompletableFuture<>();
        sf.load("k", () -> {
            loaderInvocations.incrementAndGet();
            return unfinished;
        });
        sf.invalidate("k");
        final CompletableFuture<Integer> second = sf.load("k", () -> {
            loaderInvocations.incrementAndGet();
            return CompletableFuture.completedFuture(5);
        });
        MatcherAssert.assertThat(second.get(2, TimeUnit.SECONDS), equalTo(5));
        MatcherAssert.assertThat(loaderInvocations.get(), equalTo(2));
    }

    /**
     * Different keys must not coalesce even when loaders run concurrently.
     */
    @Test
    @Timeout(5)
    void differentKeysDoNotCoalesce() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(5), 1024, this.executor
        );
        final AtomicInteger loaderInvocations = new AtomicInteger(0);
        final CompletableFuture<Integer> a = sf.load("a", () -> {
            loaderInvocations.incrementAndGet();
            return CompletableFuture.completedFuture(1);
        });
        final CompletableFuture<Integer> b = sf.load("b", () -> {
            loaderInvocations.incrementAndGet();
            return CompletableFuture.completedFuture(2);
        });
        MatcherAssert.assertThat(a.get(2, TimeUnit.SECONDS), equalTo(1));
        MatcherAssert.assertThat(b.get(2, TimeUnit.SECONDS), equalTo(2));
        MatcherAssert.assertThat(loaderInvocations.get(), equalTo(2));
    }

    /**
     * Guard: constructor input validation.
     */
    @Test
    void constructorRejectsInvalidInputs() {
        assertThrows(NullPointerException.class,
            () -> new SingleFlight<>(null, 16, this.executor));
        assertThrows(NullPointerException.class,
            () -> new SingleFlight<>(Duration.ofSeconds(1), 16, null));
        assertThrows(IllegalArgumentException.class,
            () -> new SingleFlight<>(Duration.ZERO, 16, this.executor));
        assertThrows(IllegalArgumentException.class,
            () -> new SingleFlight<>(Duration.ofSeconds(-1), 16, this.executor));
        assertThrows(IllegalArgumentException.class,
            () -> new SingleFlight<>(Duration.ofSeconds(1), 0, this.executor));
    }

    /**
     * Guard: {@link SingleFlight#load} null-checks.
     */
    @Test
    void loadRejectsNullKeyOrLoader() {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(1), 16, this.executor
        );
        assertThrows(NullPointerException.class,
            () -> sf.load(null, () -> CompletableFuture.completedFuture(0)));
        assertThrows(NullPointerException.class,
            () -> sf.load("k", null));
    }

    /**
     * The {@code inFlightCount} metric reflects approximate in-flight size.
     */
    @Test
    @Timeout(5)
    void inFlightCountTracksPendingLoads() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(5), 1024, this.executor
        );
        MatcherAssert.assertThat(sf.inFlightCount(), equalTo(0));
        final CompletableFuture<Integer> pending = new CompletableFuture<>();
        sf.load("k", () -> pending);
        MatcherAssert.assertThat(sf.inFlightCount(), equalTo(1));
        pending.complete(1);
        // Allow the invalidate callback to run.
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (sf.inFlightCount() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        MatcherAssert.assertThat(sf.inFlightCount(), equalTo(0));
    }

    /**
     * A waiter that times out independently must not affect the loader or
     * other waiters.
     */
    @Test
    @Timeout(10)
    void waiterTimeoutIsLocal() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(10), 1024, this.executor
        );
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Integer> first = sf.load("k", () ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    release.await();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return 100;
            }, this.executor)
        );
        assertThrows(TimeoutException.class, () -> first.get(50, TimeUnit.MILLISECONDS));
        // Load again while still in-flight — must join the same loader.
        final CompletableFuture<Integer> second =
            sf.load("k", () -> CompletableFuture.completedFuture(-1));
        release.countDown();
        MatcherAssert.assertThat(
            first.get(5, TimeUnit.SECONDS), equalTo(100)
        );
        MatcherAssert.assertThat(
            second.get(5, TimeUnit.SECONDS), equalTo(100)
        );
    }

    /**
     * A loader that returns an already-cancelled stage causes all waiters to
     * see a {@link CancellationException} (either thrown directly by {@code
     * get()} or wrapped in an {@link ExecutionException} depending on how
     * {@link CompletableFuture} propagates cancellation). Either shape is
     * acceptable; we only assert the terminal exception type.
     */
    @Test
    @Timeout(5)
    void loaderReturningCancelledStage() throws Exception {
        final SingleFlight<String, Integer> sf = new SingleFlight<>(
            Duration.ofSeconds(5), 1024, this.executor
        );
        final CompletableFuture<Integer> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        final CompletableFuture<Integer> result = sf.load("k", () -> cancelled);
        final Exception thrown = assertThrows(
            Exception.class,
            () -> result.get(2, TimeUnit.SECONDS)
        );
        MatcherAssert.assertThat(
            "thrown is (Cancellation|ExecutionException wrapping Cancellation)",
            thrown instanceof CancellationException
                || (thrown instanceof ExecutionException
                    && rootCause(thrown) instanceof CancellationException),
            is(true)
        );
    }

    private static Throwable rootCause(final Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur != cur.getCause()) {
            cur = cur.getCause();
        }
        return cur;
    }
}
