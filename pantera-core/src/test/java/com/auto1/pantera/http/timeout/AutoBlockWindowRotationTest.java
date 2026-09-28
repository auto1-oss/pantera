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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Sliding-window rotation of the group-member breaker after an idle period.
 *
 * <p>Regression: after an idle longer than the window, the bucket start only
 * advanced by one window, so every call for the next {@code idle/window}
 * seconds wiped the whole window again and failures never accumulated.
 * Driven with synthetic timestamps: no wall clock.</p>
 */
final class AutoBlockWindowRotationTest {

    @Test
    void failuresAccumulateRightAfterALongIdle() {
        final AutoBlockRegistry.WindowState state = new AutoBlockRegistry.WindowState(30);
        final long start = state.currentBucketStartMs;
        for (int call = 0; call < 20; call += 1) {
            state.rotateTo(start + 600_000L + call * 10L, 30);
            state.failures[state.currentBucket] += 1;
        }
        MatcherAssert.assertThat(
            AutoBlockWindowRotationTest.failures(state), new IsEqual<>(20)
        );
    }

    @Test
    void failuresAcrossSecondsAfterIdleStayInTheWindow() {
        final AutoBlockRegistry.WindowState state = new AutoBlockRegistry.WindowState(30);
        final long start = state.currentBucketStartMs;
        for (int call = 0; call < 20; call += 1) {
            state.rotateTo(start + 300_000L + call * 1_000L, 30);
            state.failures[state.currentBucket] += 1;
        }
        MatcherAssert.assertThat(
            AutoBlockWindowRotationTest.failures(state), new IsEqual<>(20)
        );
    }

    @Test
    void oldFailuresExpireAfterTheWindow() {
        final AutoBlockRegistry.WindowState state = new AutoBlockRegistry.WindowState(30);
        final long start = state.currentBucketStartMs;
        state.rotateTo(start + 100L, 30);
        state.failures[state.currentBucket] += 5;
        state.rotateTo(start + 31_000L, 30);
        MatcherAssert.assertThat(
            AutoBlockWindowRotationTest.failures(state), new IsEqual<>(0)
        );
    }

    private static int failures(final AutoBlockRegistry.WindowState state) {
        int sum = 0;
        for (final int count : state.failures) {
            sum += count;
        }
        return sum;
    }
}
