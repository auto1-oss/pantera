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
package com.auto1.pantera.http.client.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UpstreamRateLimiter.Default}. Uses a
 * test-controlled {@link Clock} so we can step time deterministically.
 *
 * @since 2.2.0
 */
final class UpstreamRateLimiterTest {

    /**
     * The 429 gate closes for the Retry-After window and re-opens once
     * the deadline passes.
     */
    @Test
    void gateClosesAndReopens() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(clock);
        limiter.recordRateLimit("h.example", Duration.ofSeconds(10));
        MatcherAssert.assertThat(
            "gate closed for 10 s",
            limiter.gateOpenUntil("h.example") != null, new IsEqual<>(true)
        );
        clock.advance(Duration.ofSeconds(9));
        MatcherAssert.assertThat(
            "still gated after 9 s",
            limiter.gateOpenUntil("h.example") != null, new IsEqual<>(true)
        );
        clock.advance(Duration.ofSeconds(2));
        MatcherAssert.assertThat(
            "gate re-opens after 11 s",
            limiter.gateOpenUntil("h.example") == null, new IsEqual<>(true)
        );
    }

    /**
     * Empty / null Retry-After yields the default gate duration.
     */
    @Test
    void recordRateLimitUsesDefaultDurationWhenAbsent() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(clock);
        limiter.recordRateLimit("h.example", Duration.ZERO);
        final Instant gateUntil = limiter.gateOpenUntil("h.example");
        MatcherAssert.assertThat(
            "gateUntil should be set", gateUntil != null, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "gateUntil should be ~30 s out",
            Duration.between(clock.instant(), gateUntil).getSeconds(),
            new IsEqual<>(30L)
        );
    }

    /**
     * Per-host isolation: gating {@code maven.org} does not affect
     * {@code npmjs.org}, even though they share the registry instance.
     */
    @Test
    void hostsAreIndependent() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(clock);
        limiter.recordRateLimit("repo1.maven.org", Duration.ofMinutes(5));
        MatcherAssert.assertThat(
            "maven gated", limiter.gateOpenUntil("repo1.maven.org") != null,
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "npm is independent", limiter.gateOpenUntil("registry.npmjs.org") == null,
            new IsEqual<>(true)
        );
    }

    /**
     * {@link UpstreamRateLimiter#recordResponse(String, int, Duration)}
     * only gates on 429 / 503-with-RetryAfter; a 200 must not close it.
     */
    @Test
    void recordResponseOnlyGatesOn429() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(clock);
        limiter.recordResponse("h.example", 200, Duration.ofSeconds(60));
        MatcherAssert.assertThat(
            "200 must not gate",
            limiter.gateOpenUntil("h.example") == null,
            new IsEqual<>(true)
        );
        limiter.recordResponse("h.example", 503, Duration.ZERO);
        MatcherAssert.assertThat(
            "503 without Retry-After must not gate (transient server error, not throttle)",
            limiter.gateOpenUntil("h.example") == null,
            new IsEqual<>(true)
        );
        limiter.recordResponse("h.example", 503, Duration.ofSeconds(10));
        MatcherAssert.assertThat(
            "503 + Retry-After IS a gating event",
            limiter.gateOpenUntil("h.example") != null,
            new IsEqual<>(true)
        );
    }

    /** Step-controllable clock for deterministic tests. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;

        TestClock(final Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(final Duration delta) {
            this.now.updateAndGet(prev -> prev.plus(delta));
        }

        @Override
        public Instant instant() {
            return this.now.get();
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }
    }
}
