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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PrefetchMetrics}.
 *
 * <p>Uses a {@link MutableClock} to drive the 24h sliding window without
 * sleeping in real wall-clock time. Each test exercises a distinct
 * window-expiry / per-repo-isolation invariant.</p>
 *
 * @since 2.2.0
 */
class PrefetchMetricsTest {

    @Test
    void incrementsAreVisibleWithin24h() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        final PrefetchMetrics metrics = new PrefetchMetrics(clock);
        for (int idx = 0; idx < 5; idx += 1) {
            metrics.dispatched("maven-central", "maven");
        }
        clock.advance(Duration.ofHours(23));
        MatcherAssert.assertThat(
            metrics.dispatchedCount("maven-central"),
            new IsEqual<>(5L)
        );
    }

    @Test
    void eventsExpireAfter24h() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        final PrefetchMetrics metrics = new PrefetchMetrics(clock);
        for (int idx = 0; idx < 5; idx += 1) {
            metrics.dispatched("maven-central", "maven");
        }
        clock.advance(Duration.ofHours(25));
        MatcherAssert.assertThat(
            metrics.dispatchedCount("maven-central"),
            new IsEqual<>(0L)
        );
    }

    @Test
    void perRepoStatsAreIsolated() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        final PrefetchMetrics metrics = new PrefetchMetrics(clock);
        metrics.dispatched("repo-a", "maven");
        metrics.dispatched("repo-a", "maven");
        metrics.dispatched("repo-a", "maven");
        for (int idx = 0; idx < 5; idx += 1) {
            metrics.completed("repo-b", "npm", "success");
        }
        MatcherAssert.assertThat(
            "repo-a should have 3 dispatched",
            metrics.dispatchedCount("repo-a"),
            new IsEqual<>(3L)
        );
        MatcherAssert.assertThat(
            "repo-b should have 0 dispatched",
            metrics.dispatchedCount("repo-b"),
            new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "repo-b should have 5 completed (success)",
            metrics.completedCount("repo-b", "success"),
            new IsEqual<>(5L)
        );
    }

    @Test
    void lastFetchAtTracksMostRecentSuccess() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        final PrefetchMetrics metrics = new PrefetchMetrics(clock);
        metrics.completed("maven-central", "maven", "success");
        clock.advance(Duration.ofMinutes(15));
        metrics.completed("maven-central", "maven", "success");
        final Instant expected = clock.instant();
        clock.advance(Duration.ofMinutes(30));
        // A failed completion must not bump lastFetchAt.
        metrics.completed("maven-central", "maven", "error");
        MatcherAssert.assertThat(
            metrics.lastFetchAt("maven-central").isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            metrics.lastFetchAt("maven-central").get(),
            new IsEqual<>(expected)
        );
    }

    /**
     * Test-only fake clock — package-private, mutable instant.
     */
    private static final class MutableClock extends java.time.Clock {
        private Instant now;

        MutableClock(final Instant start) {
            this.now = start;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }

        void advance(final Duration delta) {
            this.now = this.now.plus(delta);
        }
    }
}
