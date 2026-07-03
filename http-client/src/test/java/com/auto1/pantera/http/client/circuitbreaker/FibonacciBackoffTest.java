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
package com.auto1.pantera.http.client.circuitbreaker;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FibonacciBackoff}: sequence correctness, cap
 * enforcement, reset, and thread-safety under contention.
 *
 * @since 2.2.0
 */
final class FibonacciBackoffTest {

    @Test
    void emitsSeedTwiceThenFibonacciSequenceClampedAtCap() {
        final FibonacciBackoff backoff = new FibonacciBackoff(
            Duration.ofSeconds(30), Duration.ofSeconds(3600)
        );
        final long[] expected = {30, 30, 60, 90, 150, 240, 390, 630, 1020, 1650, 2670, 3600};
        for (int i = 0; i < expected.length; i = i + 1) {
            MatcherAssert.assertThat(
                "next() #" + i + " must equal " + expected[i] + "s",
                backoff.next(), new IsEqual<>(Duration.ofSeconds(expected[i]))
            );
        }
    }

    @Test
    void clampedValuesStayAtCapAfterFirstClamp() {
        final FibonacciBackoff backoff = new FibonacciBackoff(
            Duration.ofSeconds(30), Duration.ofSeconds(3600)
        );
        Duration last = Duration.ZERO;
        for (int i = 0; i < 30; i = i + 1) {
            last = backoff.next();
        }
        MatcherAssert.assertThat(
            "long-tail next() must remain at cap",
            last, new IsEqual<>(Duration.ofSeconds(3600))
        );
    }

    @Test
    void resetReturnsToSeed() {
        final FibonacciBackoff backoff = new FibonacciBackoff(
            Duration.ofSeconds(30), Duration.ofSeconds(3600)
        );
        for (int i = 0; i < 5; i = i + 1) {
            backoff.next();
        }
        backoff.reset();
        MatcherAssert.assertThat(
            "first next() after reset must equal seed",
            backoff.next(), new IsEqual<>(Duration.ofSeconds(30))
        );
        MatcherAssert.assertThat(
            "second next() after reset must equal seed",
            backoff.next(), new IsEqual<>(Duration.ofSeconds(30))
        );
        MatcherAssert.assertThat(
            "third next() after reset must equal seed+seed",
            backoff.next(), new IsEqual<>(Duration.ofSeconds(60))
        );
    }

    @Test
    void rejectsZeroOrNegativeSeed() {
        boolean threwOnZero = false;
        try {
            new FibonacciBackoff(Duration.ZERO, Duration.ofSeconds(3600));
        } catch (final IllegalArgumentException expected) {
            threwOnZero = true;
        }
        MatcherAssert.assertThat(
            "zero seed must be rejected",
            threwOnZero, new IsEqual<>(true)
        );
        boolean threwOnNegative = false;
        try {
            new FibonacciBackoff(Duration.ofSeconds(-1), Duration.ofSeconds(3600));
        } catch (final IllegalArgumentException expected) {
            threwOnNegative = true;
        }
        MatcherAssert.assertThat(
            "negative seed must be rejected",
            threwOnNegative, new IsEqual<>(true)
        );
    }

    @Test
    void rejectsCapBelowSeed() {
        boolean threw = false;
        try {
            new FibonacciBackoff(Duration.ofSeconds(60), Duration.ofSeconds(30));
        } catch (final IllegalArgumentException expected) {
            threw = true;
        }
        MatcherAssert.assertThat(
            "cap below seed must be rejected",
            threw, new IsEqual<>(true)
        );
    }

    @Test
    void concurrentNextProducesSequencePermutationWithoutCorruption() throws InterruptedException {
        // 100 threads each call next() exactly once. With synchronized
        // next() the union of their returned values must be exactly the
        // first 100 emissions of a serial-call FibonacciBackoff. We
        // pre-compute the serial sequence into a set and assert the
        // concurrent observations exactly match.
        final int iterations = 100;
        final FibonacciBackoff reference = new FibonacciBackoff(
            Duration.ofSeconds(30), Duration.ofSeconds(3600)
        );
        final Set<Duration> expected = new HashSet<>();
        for (int i = 0; i < iterations; i = i + 1) {
            expected.add(reference.next());
        }
        final FibonacciBackoff target = new FibonacciBackoff(
            Duration.ofSeconds(30), Duration.ofSeconds(3600)
        );
        final AtomicReferenceArray<Duration> observed = new AtomicReferenceArray<>(iterations);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(iterations);
        final ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < iterations; i = i + 1) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        observed.set(idx, target.next());
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            MatcherAssert.assertThat(
                "all 100 concurrent next() calls must complete within 5 s",
                done.await(5, TimeUnit.SECONDS), new IsEqual<>(true)
            );
        } finally {
            pool.shutdownNow();
        }
        final Set<Duration> actual = new HashSet<>();
        for (int i = 0; i < iterations; i = i + 1) {
            actual.add(observed.get(i));
        }
        MatcherAssert.assertThat(
            "concurrent observations must equal the serial sequence as a set",
            actual, new IsEqual<>(expected)
        );
    }
}
