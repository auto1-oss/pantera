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
    void errorWindowRampsDownByFactor() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 100, 20, 500L, 0.5, 1), sched);
            c.observe(100L, true);
            c.observe(100L, false);
            c.evaluateWindow();
            assertEquals(10, c.currentPermits(), "20 * 0.5 = 10");
            assertEquals(1L, c.rampDownEvents(), "one down event");
            assertEquals(0L, c.rampUpEvents(), "no up events");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void highLatencyWindowSoftRampsDown() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 100, 16, 500L, 0.5, 1), sched);
            // No errors, but max latency > 2 × target (500 ms) → soft ramp-down
            c.observe(1500L, true);
            c.observe(200L, true);
            c.evaluateWindow();
            // Soft factor = floor(16 * sqrt(0.5)) ≈ floor(11.31) = 11
            assertEquals(11, c.currentPermits(), "soft ramp-down: floor(16 * sqrt(0.5)) = 11");
            assertEquals(1L, c.rampDownEvents(), "one down event");
            c.close();
        } finally {
            sched.shutdownNow();
        }
    }

    @Test
    void modestLatencyWindowHoldsSteady() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(1, 100, 20, 500L, 0.5, 1), sched);
            // latency above target (500) but below 2× target (1000): hold steady
            c.observe(700L, true);
            c.observe(600L, true);
            c.evaluateWindow();
            assertEquals(20, c.currentPermits(), "hold steady on lukewarm latency");
            assertEquals(0L, c.rampUpEvents(), "no up events");
            assertEquals(0L, c.rampDownEvents(), "no down events");
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
    void rampDownClampsAtMinPermits() {
        final ScheduledExecutorService sched = quietScheduler();
        try {
            final AdaptivePermitController c =
                new AdaptivePermitController("r", limits(3, 100, 4, 500L, 0.1, 1), sched);
            c.observe(100L, false);
            c.evaluateWindow();
            assertEquals(3, c.currentPermits(), "4 * 0.1 = 0 but floored at minPermits=3");
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
}
