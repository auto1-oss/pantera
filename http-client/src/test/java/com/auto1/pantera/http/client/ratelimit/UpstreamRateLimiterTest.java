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
     * Burst tokens are available immediately; the bucket only blocks
     * after the burst is drained.
     */
    @Test
    void acquiresBurstTokensWithoutWaiting() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final RateLimitConfig cfg = RateLimitConfig.uniform(10.0, 5.0);
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(cfg, clock);
        for (int i = 0; i < 5; i++) {
            MatcherAssert.assertThat(
                "burst token " + i,
                limiter.tryAcquire("repo1.maven.org"),
                new IsEqual<>(true)
            );
        }
        MatcherAssert.assertThat(
            "sixth token must wait for refill",
            limiter.tryAcquire("repo1.maven.org"),
            new IsEqual<>(false)
        );
    }

    /**
     * After draining the burst, the bucket refills at the configured
     * rate; advancing the clock makes the next token available.
     */
    @Test
    void refillsAtConfiguredRate() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final RateLimitConfig cfg = RateLimitConfig.uniform(10.0, 1.0); // 10/s, burst 1
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(cfg, clock);
        MatcherAssert.assertThat(
            limiter.tryAcquire("h.example"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            limiter.tryAcquire("h.example"), new IsEqual<>(false)
        );
        clock.advance(Duration.ofMillis(110));
        MatcherAssert.assertThat(
            "after 110 ms with 10 tok/s, one token should be available",
            limiter.tryAcquire("h.example"), new IsEqual<>(true)
        );
    }

    /**
     * The 429 gate trumps token availability — even with tokens in the
     * bucket, an open gate denies acquire until the deadline passes.
     */
    @Test
    void gateBlocksDespiteAvailableTokens() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final RateLimitConfig cfg = RateLimitConfig.uniform(100.0, 100.0);
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(cfg, clock);
        // Burst is large, tokens are plentiful, but the gate is closed:
        limiter.recordRateLimit("h.example", Duration.ofSeconds(10));
        MatcherAssert.assertThat(
            "gate closed for 10 s; acquire must fail",
            limiter.tryAcquire("h.example"), new IsEqual<>(false)
        );
        // 9 s later still gated
        clock.advance(Duration.ofSeconds(9));
        MatcherAssert.assertThat(
            "still gated after 9 s",
            limiter.tryAcquire("h.example"), new IsEqual<>(false)
        );
        // After 10 s, gate re-opens
        clock.advance(Duration.ofSeconds(2));
        MatcherAssert.assertThat(
            "gate re-opens after 11 s",
            limiter.tryAcquire("h.example"), new IsEqual<>(true)
        );
    }

    /**
     * Empty / null Retry-After yields the default gate duration.
     */
    @Test
    void recordRateLimitUsesDefaultDurationWhenAbsent() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-13T10:00:00Z"));
        final RateLimitConfig cfg = RateLimitConfig.uniform(100.0, 100.0);
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(cfg, clock);
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
        final RateLimitConfig cfg = RateLimitConfig.uniform(100.0, 100.0);
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(cfg, clock);
        limiter.recordRateLimit("repo1.maven.org", Duration.ofMinutes(5));
        MatcherAssert.assertThat(
            "maven gated", limiter.tryAcquire("repo1.maven.org"),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "npm is independent", limiter.tryAcquire("registry.npmjs.org"),
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
        final UpstreamRateLimiter limiter = new UpstreamRateLimiter.Default(
            RateLimitConfig.uniform(100.0, 100.0), clock
        );
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
