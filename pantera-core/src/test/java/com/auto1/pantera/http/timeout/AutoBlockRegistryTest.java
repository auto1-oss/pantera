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
package com.auto1.pantera.http.timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Tests for the 2.2.0 rate-over-sliding-window circuit breaker.
 *
 * <p>Covers the behavioural shift from consecutive-count tripping:</p>
 * <ul>
 *   <li>Cold-start bursts do NOT trip — the minimum-volume gate
 *       filters out low-sample failure bursts.</li>
 *   <li>Sustained high-rate upstream failure DOES trip once enough
 *       volume accumulates in the window.</li>
 *   <li>Flaky upstream (rate below threshold) never trips regardless
 *       of how long it runs.</li>
 *   <li>Recovery: a probe success closes the circuit; a probe failure
 *       re-opens with Fibonacci-scaled back-off.</li>
 *   <li>Concurrent writers produce consistent trip decisions.</li>
 * </ul>
 */
final class AutoBlockRegistryTest {

    /**
     * Default-ish settings for tests: 50% error rate, 10-call minimum,
     * 30s window. Short block durations keep tests fast.
     */
    private static AutoBlockSettings settings(
        final double rate, final int minCalls, final int windowSec
    ) {
        return new AutoBlockSettings(
            rate, minCalls, windowSec,
            Duration.ofMillis(100), Duration.ofSeconds(30)
        );
    }

    @Test
    void startsUnblocked() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(settings(0.5, 10, 30));
        assertThat(registry.isBlocked("remote-1"), is(false));
        assertThat(registry.status("remote-1"), equalTo("online"));
    }

    /**
     * The cold-start false-positive case that motivated the rewrite:
     * three failures in rapid succession must NOT trip when the window
     * hasn't accumulated the minimum volume. Pre-rewrite, the consecutive-
     * count design tripped at exactly this point.
     */
    @Test
    void doesNotTripOnColdStartBurstBelowMinVolume() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(settings(0.5, 20, 30));
        for (int i = 0; i < 5; i++) {
            registry.recordFailure("cold-start");
        }
        assertThat(
            "5 failures is below the 20-call minimum volume — must NOT trip",
            registry.isBlocked("cold-start"),
            is(false)
        );
        assertThat(registry.status("cold-start"), equalTo("online"));
    }

    /** Sustained high-failure-rate upstream DOES trip once volume accumulates. */
    @Test
    void tripsWhenRateAndVolumeBothMet() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(settings(0.5, 10, 30));
        // 10 failures in a row — hits both rate (100% > 50%) and min volume (10 >= 10).
        for (int i = 0; i < 10; i++) {
            registry.recordFailure("r");
        }
        assertThat(
            "10 failures over 10 calls at 50% threshold — must TRIP",
            registry.isBlocked("r"),
            is(true)
        );
        assertThat(registry.status("r"), equalTo("blocked"));
    }

    /**
     * Flaky upstream: 30% error rate never trips, regardless of how much
     * volume accumulates. This is the classic "soft failures don't open
     * the circuit" guarantee that count-based breakers struggle with.
     */
    @Test
    void doesNotTripBelowRateThresholdRegardlessOfVolume() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(settings(0.5, 10, 30));
        // 100 outcomes, deterministic 30% failure rate.
        for (int i = 0; i < 100; i++) {
            if (i % 10 < 3) {
                registry.recordFailure("flaky");
            } else {
                registry.recordSuccess("flaky");
            }
        }
        assertThat(
            "30% failure rate over 100 calls — must NOT trip (threshold=50%)",
            registry.isBlocked("flaky"),
            is(false)
        );
    }

    /**
     * Threshold boundary: a 49% failure rate over enough volume must NOT
     * trip, but 51% MUST. Pins the exact semantics of the rate comparison.
     */
    @Test
    void tripsExactlyAtConfiguredRate() {
        final AutoBlockRegistry under = new AutoBlockRegistry(settings(0.5, 100, 30));
        // 49 failures, 51 successes = 49% rate
        for (int i = 0; i < 49; i++) {
            under.recordFailure("under");
        }
        for (int i = 0; i < 51; i++) {
            under.recordSuccess("under");
        }
        assertThat("49% rate — below 50% threshold, no trip",
            under.isBlocked("under"), is(false));

        final AutoBlockRegistry over = new AutoBlockRegistry(settings(0.5, 100, 30));
        // 51 failures, 49 successes = 51% rate
        for (int i = 0; i < 51; i++) {
            over.recordFailure("over");
        }
        for (int i = 0; i < 49; i++) {
            over.recordSuccess("over");
        }
        assertThat("51% rate — over 50% threshold, must trip",
            over.isBlocked("over"), is(true));
    }

    /**
     * After the initial block expires, {@code isBlocked} transitions the
     * state to PROBING and returns false — the next outcome determines
     * whether the circuit closes or re-opens.
     */
    @Test
    void transitionsToProbingAfterBlockExpires() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 4, 30, Duration.ofMillis(50), Duration.ofSeconds(30)
        ));
        // Trip it: 4 failures at 100% rate.
        for (int i = 0; i < 4; i++) {
            registry.recordFailure("r");
        }
        assertThat(registry.isBlocked("r"), is(true));
        Thread.sleep(80);
        assertThat("block expired — should report unblocked", registry.isBlocked("r"), is(false));
        assertThat(registry.status("r"), equalTo("probing"));
    }

    /** Probe success closes the circuit and clears the window. */
    @Test
    void probeSuccessClosesCircuit() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 4, 30, Duration.ofMillis(50), Duration.ofSeconds(30)
        ));
        for (int i = 0; i < 4; i++) {
            registry.recordFailure("r");
        }
        Thread.sleep(80);
        assertThat(registry.isBlocked("r"), is(false));
        registry.recordSuccess("r");
        assertThat(registry.status("r"), equalTo("online"));
    }

    /** Probe failure re-opens with larger Fibonacci-scaled block window. */
    @Test
    void probeFailureReopensWithFibonacciBackoff() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 2, 30, Duration.ofMillis(50), Duration.ofSeconds(60)
        ));
        // First trip: 50ms block (fib[0]=1).
        registry.recordFailure("r");
        registry.recordFailure("r");
        assertThat(registry.isBlocked("r"), is(true));
        Thread.sleep(70);
        assertThat(registry.isBlocked("r"), is(false));
        assertThat(registry.status("r"), equalTo("probing"));
        // Probe failure → second trip: still 50ms (fib[1]=1, same duration).
        registry.recordFailure("r");
        assertThat("Re-tripped after probe failure", registry.isBlocked("r"), is(true));
        Thread.sleep(70);
        assertThat(registry.isBlocked("r"), is(false));
        // Probe fail again → third trip: 100ms (fib[2]=2).
        registry.recordFailure("r");
        assertThat(registry.isBlocked("r"), is(true));
        Thread.sleep(60);
        assertThat(
            "100ms block has not yet expired at 60ms — still blocked",
            registry.isBlocked("r"),
            is(true)
        );
    }

    /**
     * Regression pin for the high-traffic block-extension bug from the
     * pre-2.2.0 design: while the circuit is OPEN, every in-flight
     * CircuitBreakerSlice.recordFailure call must NOT extend the block
     * window. Otherwise the circuit never self-heals.
     */
    @Test
    void doesNotExtendBlockUnderHighTraffic() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 2, 30, Duration.ofMillis(100), Duration.ofSeconds(30)
        ));
        registry.recordFailure("r");
        registry.recordFailure("r");
        assertThat(registry.isBlocked("r"), is(true));
        // Stream 1000 concurrent failure records. The breaker must NOT
        // re-arm blockedUntil with each call; current block must honour
        // its original 100 ms deadline and transition to PROBING after.
        for (int i = 0; i < 1_000; i++) {
            registry.recordFailure("r");
        }
        Thread.sleep(150);
        assertThat(
            "Block window must expire on schedule despite traffic during block",
            registry.isBlocked("r"),
            is(false)
        );
        assertThat(registry.status("r"), equalTo("probing"));
    }

    @Test
    void tracksMultipleRemotesIndependently() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(settings(0.5, 4, 30));
        for (int i = 0; i < 4; i++) {
            registry.recordFailure("a");
        }
        assertThat(registry.isBlocked("a"), is(true));
        assertThat(registry.isBlocked("b"), is(false));
    }

    /**
     * Concurrent writers to the same remote must produce correct counts.
     * Without per-state synchronisation, the window buckets could lose
     * increments and make trip decisions fire late (or not at all).
     */
    @Test
    void concurrentWritersProduceConsistentCounts() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 100, 30, Duration.ofSeconds(1), Duration.ofSeconds(30)
        ));
        final int threads = 16;
        final int opsPerThread = 500;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch latch = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            // 60% failure rate deterministically across all threads.
                            if ((id * opsPerThread + i) % 5 < 3) {
                                registry.recordFailure("concurrent");
                            } else {
                                registry.recordSuccess("concurrent");
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
        } finally {
            pool.shutdownNow();
        }
        // 8000 total outcomes at 60% failure rate well above 50% threshold,
        // well over 100 min-volume — must trip.
        assertThat(
            "16-way concurrent writers at 60% failure rate must have tripped",
            registry.isBlocked("concurrent"),
            is(true)
        );
    }

    /**
     * A fibonacci-scaled block window eventually caps at
     * {@link AutoBlockSettings#maxBlockDuration}. Exercised with a tiny
     * initial duration + small cap so the test stays fast.
     */
    @Test
    void capsBlockAtMaxDuration() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            0.5, 1, 30, Duration.ofMillis(10), Duration.ofMillis(30)
        ));
        // Rapid-fire trip-probe-retrip loop. At fib[5..]=8+, 10ms * 8 = 80ms
        // exceeds the 30ms cap, so each subsequent block should stay at 30ms.
        long maxBlockObservedMs = 0L;
        for (int trip = 0; trip < 8; trip++) {
            final long before = System.currentTimeMillis();
            registry.recordFailure("r");
            // Spin-wait on isBlocked until it transitions to probing
            while (registry.isBlocked("r")) {
                Thread.sleep(5);
            }
            maxBlockObservedMs = Math.max(
                maxBlockObservedMs, System.currentTimeMillis() - before
            );
        }
        assertThat(
            "Observed block window never exceeds the cap (with headroom for scheduling)",
            maxBlockObservedMs,
            lessThanOrEqualTo(120L)
        );
    }

    /** Validation: record constructor rejects garbage inputs. */
    @Test
    void settingsRecordValidatesInputs() {
        try {
            new AutoBlockSettings(1.5, 10, 30, Duration.ofSeconds(1), Duration.ofSeconds(10));
            throw new AssertionError("rate > 1.0 should have been rejected");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            new AutoBlockSettings(0.5, 0, 30, Duration.ofSeconds(1), Duration.ofSeconds(10));
            throw new AssertionError("minCalls=0 should have been rejected");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            new AutoBlockSettings(0.5, 10, 30, Duration.ofSeconds(10), Duration.ofSeconds(5));
            throw new AssertionError("max < initial should have been rejected");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
