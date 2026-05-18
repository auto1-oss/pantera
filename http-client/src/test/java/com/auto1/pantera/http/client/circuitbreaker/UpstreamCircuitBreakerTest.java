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

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UpstreamCircuitBreaker}: state-machine
 * transitions on success / failure / non-trip statuses, trip
 * predicates, and time-aware {@link UpstreamCircuitBreaker#isOpen()}
 * decisions.
 *
 * @since 2.2.0
 */
final class UpstreamCircuitBreakerTest {

    private static final Instant T0 = Instant.parse("2026-05-14T12:00:00Z");

    @Test
    void initialStateIsClosed() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        MatcherAssert.assertThat(
            "newly-constructed breaker reports closed",
            breaker.isOpen(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "newly-constructed breaker has zero trips",
            breaker.tripCount(), new IsEqual<>(0L)
        );
        MatcherAssert.assertThat(
            "newly-constructed breaker has no time remaining",
            breaker.timeRemaining(), new IsEqual<>(null)
        );
    }

    @Test
    void first5xxOpensBreakerForSeedDuration() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "5xx must open the breaker",
            breaker.isOpen(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "first trip increments tripCount to 1",
            breaker.tripCount(), new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "block window equals the seed duration",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(30))
        );
    }

    @Test
    void breakerAutoClosesAfterWindowElapses() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(500);
        clock.advance(Duration.ofSeconds(31));
        MatcherAssert.assertThat(
            "breaker reports closed once blockedUntil has elapsed",
            breaker.isOpen(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "time remaining is null after block elapses",
            breaker.timeRemaining(), new IsEqual<>(null)
        );
    }

    @Test
    void recordSuccessImmediatelyClosesAndResetsBackoff() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(503);
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "two trips elapses time remaining beyond seed",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(30))
        );
        breaker.recordSuccess();
        MatcherAssert.assertThat(
            "success closes the breaker",
            breaker.isOpen(), new IsEqual<>(false)
        );
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "after success + failure, the backoff has reset to seed",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(30))
        );
    }

    @Test
    void successiveTripsFollowFibonacciSequence() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        final long[] expected = {30, 30, 60, 90, 150, 240};
        for (int i = 0; i < expected.length; i = i + 1) {
            breaker.recordFailure(503);
            MatcherAssert.assertThat(
                "trip #" + (i + 1) + " window is " + expected[i] + "s",
                breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(expected[i]))
            );
            clock.advance(Duration.ofSeconds(expected[i] + 1));
        }
    }

    @Test
    void status429DoesNotTrip() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(429);
        MatcherAssert.assertThat(
            "429 must not trip — it is owned by the rate-limit gate",
            breaker.isOpen(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "tripCount is zero after a non-trip status",
            breaker.tripCount(), new IsEqual<>(0L)
        );
    }

    @Test
    void status404DoesNotTrip() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(404);
        MatcherAssert.assertThat(
            "404 must not trip — it is not upstream brokenness",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void status401DoesNotTrip() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(401);
        MatcherAssert.assertThat(
            "401 must NOT trip — Docker Registry V2 returns 401 with a Bearer "
                + "challenge as part of normal auth flow; counting it as a "
                + "failure trips the gate during every cold pull and cascades "
                + "into total upstream failure",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void status407DoesNotTrip() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(407);
        MatcherAssert.assertThat(
            "407 must NOT trip — same shape as 401 (upstream HTTP proxy "
                + "auth challenge); client provides credentials and retries",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void status403DoesNotTrip() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(403);
        MatcherAssert.assertThat(
            "403 must NOT trip — permission denial is upstream working "
                + "correctly, not malfunctioning",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void ioExceptionTrips() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(new IOException("connection reset"));
        MatcherAssert.assertThat(
            "IOException must trip — upstream brokenness signal",
            breaker.isOpen(), new IsEqual<>(true)
        );
    }

    @Test
    void rejectedExecutionExceptionDoesNotTrip() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(new RejectedExecutionException("pool saturated"));
        MatcherAssert.assertThat(
            "RejectedExecutionException is local backpressure, not upstream",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void blockedUntilReturnsFutureInstantWhileOpen() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(500);
        final Instant until = breaker.blockedUntil();
        MatcherAssert.assertThat(
            "blockedUntil is non-null while breaker is open",
            until != null, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "blockedUntil equals now + 30s on the first trip",
            until, new IsEqual<>(T0.plus(Duration.ofSeconds(30)))
        );
    }

    @Test
    void blockedUntilIsNullAfterWindowElapses() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(500);
        clock.advance(Duration.ofSeconds(31));
        MatcherAssert.assertThat(
            "blockedUntil returns null once the window elapses",
            breaker.blockedUntil(), new IsEqual<>(null)
        );
    }

    private static UpstreamCircuitBreaker newBreaker(final Clock clock) {
        return new UpstreamCircuitBreaker(
            "repo1.maven.org",
            CircuitBreakerConfig.defaults(),
            clock
        );
    }

    /**
     * Test clock that lets the test advance time deterministically.
     */
    private static final class TestClock extends Clock {

        private Instant now;

        TestClock(final Instant start) {
            this.now = start;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
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
