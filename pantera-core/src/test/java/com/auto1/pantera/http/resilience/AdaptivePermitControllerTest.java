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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the AIMD math + acquire/release lifecycle of {@link AdaptivePermitController}.
 *
 * <p>Each test constructs the controller with a dedicated scheduler that
 * is shut down in the test body so the shared process-wide scheduler is
 * never touched. The window tick is driven manually via the
 * package-private {@link AdaptivePermitController#evaluateWindow()} so
 * the tests are deterministic and do not rely on wall-clock timing.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
final class AdaptivePermitControllerTest {

    /** No-op scheduler that never fires its tasks — tests drive the window manually. */
    private ScheduledExecutorService quietScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "test-aimd");
            t.setDaemon(true);
            return t;
        });
    }

    private AdaptiveBulkheadLimits limits(
        final int min, final int max, final int initial,
        final long targetMs, final double rampDown, final int rampUp
    ) {
        return new AdaptiveBulkheadLimits(
            true, min, max, initial,
            targetMs, 5, rampUp, rampDown,
            1000, Duration.ofSeconds(1)
        );
    }

    @Test
    void tryAcquireRespectsCurrentPermits() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 10, 3, 500L, 0.5, 1), sched);
            assertTrue(c.tryAcquire(), "first acquire");
            assertTrue(c.tryAcquire(), "second acquire");
            assertTrue(c.tryAcquire(), "third acquire");
            assertFalse(c.tryAcquire(), "fourth acquire blocked by ceiling=3");
            assertEquals(3, c.inFlightCount(), "in-flight matches acquired");
            c.release();
            assertTrue(c.tryAcquire(), "acquire after release succeeds");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void healthyWindowRampsUp() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 10, 3, 500L, 0.5, 2), sched);
            c.observe(100L, true);
            c.observe(200L, true);
            c.observe(300L, true);
            c.evaluateWindow();
            assertEquals(5, c.currentPermits(), "3 + rampUpStep(2) = 5");
            assertEquals(1L, c.rampUpEvents(), "one up event");
            assertEquals(0L, c.rampDownEvents(), "no down events");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void failedRequestsDoNotShrinkThePermits() {
        // A failed request says nothing about how many requests the
        // repository can carry at once.
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 100, 20, 500L, 0.5, 1), sched);
            c.observe(100L, true);
            c.observe(100L, false);
            c.evaluateWindow();
            assertEquals(20, c.currentPermits(), "the permits are held, not cut");
            assertEquals(0L, c.rampDownEvents(), "no down event");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void slowRequestsDoNotShrinkThePermits() {
        // The cold fetch of a large artifact takes seconds on a healthy
        // upstream. On 2026-11-17 one such request per window cut the
        // permits of a Maven Central proxy 266 times, down to 5, and the
        // requests beyond them were refused: waves of 502 with no error
        // anywhere.
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 100, 16, 500L, 0.5, 1), sched);
            c.observe(6092L, true);
            c.observe(200L, true);
            c.evaluateWindow();
            assertEquals(17, c.currentPermits(), "a window without failures grows the permits");
            assertEquals(0L, c.rampDownEvents(), "no down event");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void latencyPlaysNoPartInTheNextPermits() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 100, 20, 500L, 0.5, 1), sched);
            c.observe(700L, true);
            c.observe(600L, true);
            c.evaluateWindow();
            assertEquals(21, c.currentPermits(), "grown as after any window without failures");
            assertEquals(1L, c.rampUpEvents(), "one up event");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void rampUpClampsAtMaxPermits() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 5, 4, 500L, 0.5, 10), sched);
            c.observe(100L, true);
            c.evaluateWindow();
            assertEquals(5, c.currentPermits(), "4 + 10 capped at maxPermits=5");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void permitsNeverShrinkHoweverBadTheWindow() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(3, 100, 40, 500L, 0.1, 1), sched);
            for (int window = 0; window < 20; window += 1) {
                c.observe(30_000L, false);
                c.evaluateWindow();
            }
            assertEquals(40, c.currentPermits(), "still the initial permits");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void emptyWindowDoesNotAdjust() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 100, 7, 500L, 0.5, 1), sched);
            c.evaluateWindow();
            assertEquals(7, c.currentPermits(), "no observations -> no change");
            assertEquals(0L, c.rampUpEvents(), "no up events");
            assertEquals(0L, c.rampDownEvents(), "no down events");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void nonAdaptiveLimitsKeepPermitsFixed() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptiveBulkheadLimits fixed =
                AdaptiveBulkheadLimits.fixed(7, 100, Duration.ofSeconds(1));
            final AdaptivePermitController c =
                new AdaptivePermitController("r", fixed, sched);
            // Even after many observations, evaluateWindow() in non-adaptive
            // mode never runs from the scheduler (it was not registered);
            // calling it directly still produces no change because min=max.
            for (int i = 0; i < 10; i++) {
                c.observe(50L, true);
            }
            c.evaluateWindow();
            assertEquals(7, c.currentPermits(), "fixed mode permits stay constant");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void closeBlocksFurtherAcquires() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 10, 5, 500L, 0.5, 1), sched);
            assertTrue(c.tryAcquire(), "acquire before close");
            c.close();
            assertFalse(c.tryAcquire(), "acquire after close blocked");
            c.release();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void acquireWaitsForAPermitInsteadOfRefusing() throws Exception {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c = new AdaptivePermitController(
                "r", AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)), sched
            );
            final CompletableFuture<Boolean> first = c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> second = c.acquire(Duration.ofSeconds(30));
            assertTrue(first.isDone() && first.get(), "a free permit is granted at once");
            assertFalse(second.isDone(), "the next request waits");
            assertEquals(1, c.waitingCount(), "one request is waiting");
            c.release();
            assertTrue(second.get(10, TimeUnit.SECONDS), "and gets the permit when it is released");
            assertEquals(1, c.inFlightCount(), "the permit moved to the waiting request");
            assertEquals(0, c.waitingCount(), "nobody is waiting any more");
            c.release();
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void acquireServesWaitingRequestsInArrivalOrder() throws Exception {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c = new AdaptivePermitController(
                "r", AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)), sched
            );
            c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> older = c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> newer = c.acquire(Duration.ofSeconds(30));
            c.release();
            assertTrue(older.get(10, TimeUnit.SECONDS), "the request that waited longest goes first");
            assertFalse(newer.isDone(), "the newer one still waits");
            c.release();
            assertTrue(newer.get(10, TimeUnit.SECONDS), "and goes next");
            c.release();
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void acquireGivesUpAfterTheWaitBound() throws Exception {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c = new AdaptivePermitController(
                "r", AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)), sched
            );
            c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> waiter = c.acquire(Duration.ofMillis(20));
            assertFalse(waiter.get(10, TimeUnit.SECONDS), "no permit within the bound");
            assertEquals(0, c.waitingCount(), "the request left the queue");
            c.release();
            assertEquals(0, c.inFlightCount(), "the released permit went to nobody");
            assertTrue(
                c.acquire(Duration.ofSeconds(30)).isDone(),
                "and is free for the next request"
            );
            c.release();
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void acquireRefusesAtOnceWhenTheQueueIsFull() throws Exception {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c = new AdaptivePermitController(
                "r", AdaptiveBulkheadLimits.fixed(1, 2, Duration.ofSeconds(1)), sched
            );
            c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> one = c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> two = c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> three = c.acquire(Duration.ofSeconds(30));
            assertFalse(one.isDone() || two.isDone(), "two requests fit in the queue");
            assertTrue(three.isDone() && !three.get(), "the third is refused without waiting");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void growingPermitsAdmitWaitingRequests() throws Exception {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 3, 1, 500L, 0.5, 1), sched);
            c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> waiter = c.acquire(Duration.ofSeconds(30));
            assertFalse(waiter.isDone(), "one permit, one request waits");
            c.observe(100L, true);
            c.evaluateWindow();
            assertTrue(waiter.get(10, TimeUnit.SECONDS), "the second permit goes to it");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void closingLetsWaitingRequestsThrough() throws Exception {
        // A closed controller belongs to a bulkhead that was replaced after
        // a settings change: requests still queued on it must not fail.
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c = new AdaptivePermitController(
                "r", AdaptiveBulkheadLimits.fixed(1, 100, Duration.ofSeconds(1)), sched
            );
            c.acquire(Duration.ofSeconds(30));
            final CompletableFuture<Boolean> waiter = c.acquire(Duration.ofSeconds(30));
            c.close();
            assertTrue(waiter.get(10, TimeUnit.SECONDS), "the waiting request is let through");
            assertTrue(
                c.acquire(Duration.ofSeconds(30)).get(10, TimeUnit.SECONDS),
                "and so is a request that arrives after the close"
            );
        } finally {
            sched.shutdownNow();
        }
    }
}
