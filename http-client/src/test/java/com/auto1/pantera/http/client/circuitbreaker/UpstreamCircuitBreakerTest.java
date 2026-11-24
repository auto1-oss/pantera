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
    void staysOpenAfterTheBlockUntilAProbeSucceeds() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(500);
        clock.advance(Duration.ofSeconds(31));
        MatcherAssert.assertThat(
            "the end of the block is the time of the probe, not a recovery",
            breaker.isOpen(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "no time remains: the probe is due",
            breaker.timeRemaining(), new IsEqual<>(null)
        );
        MatcherAssert.assertThat(
            "the answered probe reports the close",
            breaker.probeSucceeded(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and closes the breaker",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void answerToARequestSentBeforeTheTripDoesNotClose() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.recordFailure(503);
        breaker.recordSuccess();
        MatcherAssert.assertThat(breaker.isOpen(), new IsEqual<>(true));
    }

    @Test
    void backoffGrowsWhenTheUpstreamFailsAgainSoonAfterAnAnsweredProbe() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        final long[] expected = {30, 30, 60, 90, 150};
        for (int idx = 0; idx < expected.length; idx = idx + 1) {
            breaker.recordFailure(503);
            MatcherAssert.assertThat(
                "trip #" + (idx + 1) + " blocks for " + expected[idx] + "s",
                breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(expected[idx]))
            );
            clock.advance(Duration.ofSeconds(expected[idx] + 1));
            // The upstream answers the probe and then fails real requests
            // again: 40 s later, more than one window after the close.
            breaker.probeSucceeded();
            clock.advance(Duration.ofSeconds(40));
        }
    }

    @Test
    void backoffReturnsToTheSeedOnceTheUpstreamStayedHealthy() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(503);
        clock.advance(Duration.ofSeconds(31));
        breaker.probeFailed(503);
        clock.advance(Duration.ofSeconds(31));
        breaker.probeFailed(503);
        MatcherAssert.assertThat(
            "the third trip of one outage blocks for 60s",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(60))
        );
        clock.advance(Duration.ofSeconds(61));
        breaker.probeSucceeded();
        clock.advance(
            Duration.ofSeconds(30L * UpstreamCircuitBreaker.PROBATION_WINDOWS)
        );
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "a failure after ten healthy windows is a new outage",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(30))
        );
    }

    @Test
    void handsOutOneProbePerBlock() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        MatcherAssert.assertThat(
            "a closed breaker has no probe to send",
            breaker.claimProbe(), new IsEqual<>(false)
        );
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "the first caller sends the probe",
            breaker.claimProbe(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a second caller does not",
            breaker.claimProbe(), new IsEqual<>(false)
        );
        breaker.probeFailed(503);
        MatcherAssert.assertThat(
            "the failed probe gives the claim back for the next block",
            breaker.claimProbe(), new IsEqual<>(true)
        );
        breaker.releaseProbe();
        MatcherAssert.assertThat(
            "so does a probe that could not be sent",
            breaker.claimProbe(), new IsEqual<>(true)
        );
    }

    @Test
    void countsRefusedRequestsUntilTheyAreReported() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        breaker.fastFailed();
        breaker.fastFailed();
        MatcherAssert.assertThat(
            "both refusals are reported",
            breaker.drainFastFails(), new IsEqual<>(2L)
        );
        MatcherAssert.assertThat(
            "and the count starts again",
            breaker.drainFastFails(), new IsEqual<>(0L)
        );
    }

    @Test
    void probeAsksForWhatLastFailed() {
        final UpstreamCircuitBreaker breaker = newBreaker(new TestClock(T0));
        MatcherAssert.assertThat(
            "the root, while nothing failed",
            breaker.probeTarget(), new IsEqual<>("/")
        );
        breaker.failedOn("/org/acme/app/1.0/app-1.0.pom");
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "the path of the failed request",
            breaker.probeTarget(), new IsEqual<>("/org/acme/app/1.0/app-1.0.pom")
        );
        breaker.probeSucceeded();
        MatcherAssert.assertThat(
            "the root again after the recovery",
            breaker.probeTarget(), new IsEqual<>("/")
        );
    }

    @Test
    void successiveTripsFollowFibonacciSequence() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        final long[] expected = {30, 30, 60, 90, 150, 240};
        breaker.recordFailure(503);
        for (int i = 0; i < expected.length; i = i + 1) {
            if (i > 0) {
                breaker.probeFailed(503);
            }
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

    @Test
    void singleStray5xxDoesNotTripGatedBreaker() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = gatedBreaker(clock);
        for (int i = 0; i < 9; i = i + 1) {
            breaker.recordSuccess();
        }
        final boolean tripped = breaker.recordFailure(500);
        MatcherAssert.assertThat(
            "one 500 among nine successes must not trip (rate below threshold)",
            tripped, new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "breaker stays closed on a stray 5xx",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void failuresBelowMinimumCallsDoNotTrip() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = gatedBreaker(clock);
        for (int i = 0; i < 9; i = i + 1) {
            breaker.recordFailure(503);
        }
        MatcherAssert.assertThat(
            "nine failures are below the 10-call minimum — no trip yet",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void tripsWhenWindowMeetsVolumeAndRate() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = gatedBreaker(clock);
        for (int i = 0; i < 5; i = i + 1) {
            breaker.recordSuccess();
        }
        boolean tripped = false;
        for (int i = 0; i < 5; i = i + 1) {
            tripped = breaker.recordFailure(502);
        }
        MatcherAssert.assertThat(
            "5 failures / 10 calls meets the 50% threshold — the last failure trips",
            tripped, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "breaker is open after the gate trips",
            breaker.isOpen(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "gated trip uses the 2s seed backoff",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(2))
        );
    }

    @Test
    void failuresOutsideSlidingWindowExpire() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = gatedBreaker(clock);
        for (int i = 0; i < 9; i = i + 1) {
            breaker.recordFailure(500);
        }
        clock.advance(Duration.ofSeconds(31));
        final boolean tripped = breaker.recordFailure(500);
        MatcherAssert.assertThat(
            "failures older than the 30s window expired — 1 call in window, no trip",
            tripped, new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "breaker stays closed when the window is stale",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void probeFailureRetripsWithoutVolumeGate() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = gatedBreaker(clock);
        for (int i = 0; i < 10; i = i + 1) {
            breaker.recordFailure(503);
        }
        MatcherAssert.assertThat(
            "10/10 failures trip the gated breaker",
            breaker.isOpen(), new IsEqual<>(true)
        );
        clock.advance(Duration.ofSeconds(3));
        breaker.probeFailed(503);
        MatcherAssert.assertThat(
            "a single failed probe re-trips despite the empty window",
            breaker.isOpen(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "probe re-trip advances tripCount",
            breaker.tripCount(), new IsEqual<>(2L)
        );
    }

    @Test
    void answeredProbeReportsCloseExactlyOnce() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = gatedBreaker(clock);
        for (int i = 0; i < 10; i = i + 1) {
            breaker.recordFailure(503);
        }
        MatcherAssert.assertThat(
            "the first answered probe after a trip reports the close",
            breaker.probeSucceeded(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "a later one does not re-report it",
            breaker.probeSucceeded(), new IsEqual<>(false)
        );
    }

    @Test
    void windowIsClearedOnTripSoRecoveryIsNotInstantlyReconvicted() {
        final TestClock clock = new TestClock(T0);
        final UpstreamCircuitBreaker breaker = gatedBreaker(clock);
        for (int i = 0; i < 10; i = i + 1) {
            breaker.recordFailure(503);
        }
        breaker.probeSucceeded();
        final boolean tripped = breaker.recordFailure(500);
        MatcherAssert.assertThat(
            "pre-trip failures were cleared — one new failure cannot re-trip",
            tripped, new IsEqual<>(false)
        );
    }

    @Test
    void configSupplierSwapAppliesToExistingBreaker() {
        final TestClock clock = new TestClock(T0);
        final java.util.concurrent.atomic.AtomicReference<CircuitBreakerConfig> live =
            new java.util.concurrent.atomic.AtomicReference<>(CircuitBreakerConfig.defaults());
        final UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(
            "repo1.maven.org", live::get, clock
        );
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "under defaults (min 10 calls) one failure does not trip",
            breaker.isOpen(), new IsEqual<>(false)
        );
        final CircuitBreakerConfig defaults = CircuitBreakerConfig.defaults();
        live.set(new CircuitBreakerConfig(
            Duration.ofSeconds(7),
            Duration.ofMinutes(60),
            defaults.shouldTripOnException(),
            defaults.shouldTripOnStatus(),
            0.01,
            1,
            60
        ));
        final boolean tripped = breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "after the admin lowers the gate (min 1 call), the SAME breaker trips",
            tripped, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the trip uses the NEW seed backoff (7s), not the construction-time 2s",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(7))
        );
    }

    private static UpstreamCircuitBreaker newBreaker(final Clock clock) {
        return new UpstreamCircuitBreaker(
            "repo1.maven.org",
            UpstreamCircuitBreakerTest.hairTrigger(),
            clock
        );
    }

    /**
     * Config with the volume/rate gate collapsed (1 call, 100% rate) and
     * the pre-gating 30s/60min backoff shape — the state-machine tests
     * exercise trip windows and transitions, not the gate.
     */
    private static CircuitBreakerConfig hairTrigger() {
        final CircuitBreakerConfig defaults = CircuitBreakerConfig.defaults();
        return new CircuitBreakerConfig(
            Duration.ofSeconds(30),
            Duration.ofMinutes(60),
            defaults.shouldTripOnException(),
            defaults.shouldTripOnStatus(),
            0.01,
            1,
            30
        );
    }

    private static UpstreamCircuitBreaker gatedBreaker(final Clock clock) {
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
