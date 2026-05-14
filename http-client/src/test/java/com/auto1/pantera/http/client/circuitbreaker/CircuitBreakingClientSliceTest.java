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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link CircuitBreakingClientSlice}: fast-fail
 * synthesises 502 with the marker header, qualifying failures trip
 * the breaker, 429 does NOT trip, and the HEAD probe schedules
 * automatically on every trip.
 *
 * @since 2.2.0
 */
final class CircuitBreakingClientSliceTest {

    private static final RequestLine GET = new RequestLine(
        RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar"
    );

    private ScheduledExecutorService probeExecutor;

    @BeforeEach
    void setUp() {
        this.probeExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                final Thread t = new Thread(r, "test-probe");
                t.setDaemon(true);
                return t;
            }
        );
    }

    @AfterEach
    void tearDown() {
        this.probeExecutor.shutdownNow();
    }

    @Test
    void openBreakerFastFailsWith502CarryingCircuitOpenHeader() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-14T12:00:00Z"));
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        // Trip via a direct API call rather than a downstream response —
        // this isolates the test from the trip-on-response path covered
        // in the next test.
        breaker.recordFailure(503);
        final RecordingSlice downstream = new RecordingSlice();
        final CircuitBreakingClientSlice slice = new CircuitBreakingClientSlice(
            downstream, "repo1.maven.org", breaker,
            CircuitBreakerConfig.defaults(), clock, this.probeExecutor
        );
        final Response response = slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "downstream is never invoked while breaker is open",
            downstream.calls.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "synthesised response status is 502",
            response.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "synthesised 502 carries the X-Pantera-Circuit-Open marker",
            response.headers().values(CircuitBreakingClientSlice.CIRCUIT_OPEN_HEADER),
            new IsEqual<>(java.util.List.of("true"))
        );
        MatcherAssert.assertThat(
            "synthesised 502 carries Retry-After equal to seed (30s)",
            response.headers().values("Retry-After"),
            new IsEqual<>(java.util.List.of("30"))
        );
    }

    @Test
    void downstream5xxResponseTripsBreakerForNextCaller() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-14T12:00:00Z"));
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        final RecordingSlice downstream = new RecordingSlice();
        downstream.enqueue(ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build());
        downstream.enqueue(ResponseBuilder.ok().body(Flowable.empty()).build());
        final CircuitBreakingClientSlice slice = new CircuitBreakingClientSlice(
            downstream, "repo1.maven.org", breaker,
            CircuitBreakerConfig.defaults(), clock, this.probeExecutor
        );
        final Response first = slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "first call returns the upstream 500 verbatim",
            first.status(), new IsEqual<>(RsStatus.INTERNAL_ERROR)
        );
        MatcherAssert.assertThat(
            "breaker opens after the first qualifying failure",
            breaker.isOpen(), new IsEqual<>(true)
        );
        final Response second = slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "second call fast-fails with 502 without hitting downstream",
            second.status(), new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "downstream saw exactly one call (the first)",
            downstream.calls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void downstream429DoesNotTripTheBreaker() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-14T12:00:00Z"));
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        final RecordingSlice downstream = new RecordingSlice();
        downstream.enqueue(
            ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "10").build()
        );
        final CircuitBreakingClientSlice slice = new CircuitBreakingClientSlice(
            downstream, "repo1.maven.org", breaker,
            CircuitBreakerConfig.defaults(), clock, this.probeExecutor
        );
        final Response response = slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "429 propagates verbatim",
            response.status(), new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
        MatcherAssert.assertThat(
            "breaker does NOT open on 429 — owned by the rate-limit gate",
            breaker.isOpen(), new IsEqual<>(false)
        );
    }

    @Test
    void downstream200ClearsAnyPriorOpenState() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-14T12:00:00Z"));
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        breaker.recordFailure(503);
        // Time-travel past the block so isOpen() reports closed; the
        // backoff is still at "tripped" until a recordSuccess() lands.
        clock.advance(Duration.ofSeconds(31));
        final RecordingSlice downstream = new RecordingSlice();
        downstream.enqueue(ResponseBuilder.ok().body(Flowable.empty()).build());
        final CircuitBreakingClientSlice slice = new CircuitBreakingClientSlice(
            downstream, "repo1.maven.org", breaker,
            CircuitBreakerConfig.defaults(), clock, this.probeExecutor
        );
        final Response response = slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "200 passes through",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        // Next failure: the recordSuccess in the 200 path should have
        // reset the backoff, so the next trip's window is the seed.
        breaker.recordFailure(503);
        MatcherAssert.assertThat(
            "after recordSuccess, the next trip uses the seed window again",
            breaker.timeRemaining(), new IsEqual<>(Duration.ofSeconds(30))
        );
    }

    @Test
    void downstreamExceptionTripsTheBreaker() {
        final TestClock clock = new TestClock(Instant.parse("2026-05-14T12:00:00Z"));
        final UpstreamCircuitBreaker breaker = newBreaker(clock);
        final FailingSlice downstream = new FailingSlice(new IOException("connection reset"));
        final CircuitBreakingClientSlice slice = new CircuitBreakingClientSlice(
            downstream, "repo1.maven.org", breaker,
            CircuitBreakerConfig.defaults(), clock, this.probeExecutor
        );
        final Throwable caught = expectFailure(
            slice.response(GET, Headers.EMPTY, Content.EMPTY)
        );
        MatcherAssert.assertThat(
            "the downstream exception is propagated to the caller",
            caught.getCause() instanceof IOException, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the breaker opens after the IOException",
            breaker.isOpen(), new IsEqual<>(true)
        );
    }

    @Test
    void successfulHeadProbeClosesTheBreaker() throws InterruptedException {
        final TestClock clock = new TestClock(Instant.parse("2026-05-14T12:00:00Z"));
        // Tiny seed so the test does not actually wait 30s; the probe
        // is scheduled with a Duration relative to the test clock, but
        // the executor uses real wall-clock for the delay computation.
        final CircuitBreakerConfig config = new CircuitBreakerConfig(
            Duration.ofMillis(50), Duration.ofMillis(500),
            CircuitBreakerConfig.defaults().shouldTripOnException(),
            CircuitBreakerConfig.defaults().shouldTripOnStatus()
        );
        final UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(
            "repo1.maven.org", config, clock
        );
        final RecordingSlice downstream = new RecordingSlice();
        downstream.enqueue(ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build());
        // The HEAD probe response — a 200 means "upstream alive again".
        downstream.enqueue(ResponseBuilder.ok().body(Flowable.empty()).build());
        final CircuitBreakingClientSlice slice = new CircuitBreakingClientSlice(
            downstream, "repo1.maven.org", breaker, config, clock, this.probeExecutor
        );
        slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "breaker is open after the first 500",
            breaker.isOpen(), new IsEqual<>(true)
        );
        // Wait for the probe to run. The breaker's blockedUntil uses
        // the test clock (which has not advanced) but the executor uses
        // wall-clock — so the scheduled delay is ~0 ms.
        final boolean closed = waitUntil(() -> !breaker.isOpen(), Duration.ofSeconds(2));
        MatcherAssert.assertThat(
            "after the probe runs and reports success, the breaker is closed",
            closed, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "downstream observed the failed call plus the HEAD probe",
            downstream.calls.get(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the second downstream call was the HEAD probe",
            downstream.lastMethod, new IsEqual<>("HEAD")
        );
    }

    @Test
    void failedHeadProbeKeepsBreakerOpenAndSchedulesAnother() throws InterruptedException {
        final TestClock clock = new TestClock(Instant.parse("2026-05-14T12:00:00Z"));
        final CircuitBreakerConfig config = new CircuitBreakerConfig(
            Duration.ofMillis(50), Duration.ofMillis(500),
            CircuitBreakerConfig.defaults().shouldTripOnException(),
            CircuitBreakerConfig.defaults().shouldTripOnStatus()
        );
        final UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(
            "repo1.maven.org", config, clock
        );
        final RecordingSlice downstream = new RecordingSlice();
        downstream.enqueue(ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build());
        // Two consecutive failed probes.
        downstream.enqueue(ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build());
        downstream.enqueue(ResponseBuilder.from(RsStatus.INTERNAL_ERROR).build());
        final CircuitBreakingClientSlice slice = new CircuitBreakingClientSlice(
            downstream, "repo1.maven.org", breaker, config, clock, this.probeExecutor
        );
        slice.response(GET, Headers.EMPTY, Content.EMPTY).join();
        // Wait until downstream has observed the original call + at least one probe.
        final boolean atLeastOneProbe = waitUntil(
            () -> downstream.calls.get() >= 2, Duration.ofSeconds(2)
        );
        MatcherAssert.assertThat(
            "at least one HEAD probe runs after the trip",
            atLeastOneProbe, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "breaker remains open after a failed probe",
            breaker.isOpen(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "breaker trip count advanced past the initial trip",
            breaker.tripCount() >= 2L, new IsEqual<>(true)
        );
    }

    private static UpstreamCircuitBreaker newBreaker(final Clock clock) {
        return new UpstreamCircuitBreaker(
            "repo1.maven.org", CircuitBreakerConfig.defaults(), clock
        );
    }

    private static Throwable expectFailure(final CompletableFuture<?> future) {
        try {
            future.join();
            throw new IllegalStateException("future was expected to fail but did not");
        } catch (final java.util.concurrent.CompletionException ex) {
            return ex;
        }
    }

    private static boolean waitUntil(
        final java.util.function.BooleanSupplier predicate, final Duration timeout
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (predicate.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return predicate.getAsBoolean();
    }

    /**
     * Recording downstream slice — captures invocation counts +
     * returns canned responses.
     */
    private static final class RecordingSlice implements Slice {

        private final AtomicInteger calls = new AtomicInteger();
        private final Queue<Response> canned = new ConcurrentLinkedQueue<>();
        private volatile String lastMethod;

        void enqueue(final Response response) {
            this.canned.add(response);
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.calls.incrementAndGet();
            this.lastMethod = line.method().value();
            final Response next = this.canned.poll();
            return CompletableFuture.completedFuture(
                next == null ? ResponseBuilder.ok().body(Flowable.empty()).build() : next
            );
        }
    }

    /**
     * Downstream slice that always fails with a configured throwable.
     */
    private static final class FailingSlice implements Slice {

        private final Throwable err;

        FailingSlice(final Throwable err) {
            this.err = err;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return CompletableFuture.failedFuture(this.err);
        }
    }

    /**
     * Hand-rolled controllable clock.
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
