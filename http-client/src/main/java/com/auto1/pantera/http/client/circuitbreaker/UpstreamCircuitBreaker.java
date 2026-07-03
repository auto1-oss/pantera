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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-host circuit-breaker state machine. Pure state + decisions;
 * no I/O. Wiring (HEAD probe scheduling, host→breaker map, metric
 * recording) lives in the upstream client decorator that owns this
 * instance.
 *
 * <p>States:
 * <ul>
 *   <li><b>Closed</b> — {@code blockedUntil == null} (or in the past).
 *       Outbound calls pass through.</li>
 *   <li><b>Open</b> — {@code blockedUntil} is in the future. Outbound
 *       calls fast-fail via the decorator. {@link #isOpen()} returns
 *       {@code true}.</li>
 * </ul>
 *
 * <p>Transitions:
 * <ul>
 *   <li>{@link #recordFailure(int)} or {@link #recordFailure(Throwable)}
 *       with a status / exception that satisfies the configured trip
 *       predicate records a failure into the per-second sliding window
 *       and trips ONLY when the window holds at least
 *       {@link CircuitBreakerConfig#minimumCalls()} calls with a
 *       failure rate at or above
 *       {@link CircuitBreakerConfig#failureRateThreshold()}. A single
 *       stray 5xx among healthy traffic never opens the breaker.</li>
 *   <li>{@link #probeFailed(Throwable)} / {@link #probeFailed(int)}:
 *       unconditional re-trip — used by the recovery probe, where the
 *       breaker already convicted the upstream and one failed probe is
 *       authoritative evidence it is still down.</li>
 *   <li>{@link #recordSuccess()}: clears {@code blockedUntil}, resets
 *       the Fibonacci backoff to seed, and records a success into the
 *       window.</li>
 *   <li>A failure that does NOT satisfy the trip predicate: no state
 *       change (e.g. a 429 — owned by the rate-limit gate; a 403 —
 *       authoritative auth signal from upstream).</li>
 * </ul>
 *
 * <p>Thread-safety: every public method is synchronized on
 * {@code this}. Contention is not a concern — the methods are called
 * once per outbound request at most, and the per-host fan-out at line
 * rate is bounded by the HTTP pool ({@code maxConnectionsPerDestination},
 * default 64). The fast-path is {@link #isOpen()}, which is a single
 * volatile read of {@code blockedUntil} plus a clock read.
 *
 * @since 2.2.0
 */
public final class UpstreamCircuitBreaker {

    /**
     * Host this breaker protects. Exposed for diagnostics; the
     * decorator owns the host→breaker mapping.
     */
    private final String host;

    /**
     * Configuration source. Read live on every decision so DB-backed
     * admin settings (rate threshold, min calls, window, backoff) apply
     * without restarting or rebuilding breakers. Predicates come from
     * the same object.
     */
    private final Supplier<CircuitBreakerConfig> config;

    /**
     * Clock used to derive {@code blockedUntil} and {@link #timeRemaining()}.
     * Injectable for tests.
     */
    private final Clock clock;

    /**
     * Mutable Fibonacci sequence advanced on every trip and reset on
     * recovery. Rebuilt (sequence reset) when the configured seed/cap
     * change at runtime. Guarded by {@code this}.
     */
    private FibonacciBackoff backoff;

    /**
     * Seed/cap the current {@link #backoff} was built from — compared
     * against live config on each trip to detect runtime changes.
     */
    private java.time.Duration backoffSeed;

    /** See {@link #backoffSeed}. */
    private java.time.Duration backoffCap;

    /**
     * Monotonic count of trips since this breaker was created.
     * Surface in metrics so operators can spot flaps.
     */
    private final AtomicLong tripCount;

    /**
     * Wall-clock instant the breaker re-opens. {@code null} when
     * closed. Reads on the {@link #isOpen()} hot path are unlocked
     * (volatile semantics via the synchronized writes).
     */
    private volatile Instant blockedUntil;

    /**
     * Per-second sliding window: successes per epoch-second bucket.
     * Index is {@code epochSecond % windowSeconds}; {@code stamps}
     * detects stale buckets from earlier revolutions. Guarded by
     * {@code this} (all writers are synchronized).
     */
    private int[] successes;

    /**
     * Per-second sliding window: qualifying failures per bucket.
     */
    private int[] failures;

    /**
     * Epoch second each bucket was last written; buckets whose stamp
     * is outside the current window are treated as empty. Re-allocated
     * (cleared) when the configured window length changes at runtime.
     */
    private long[] stamps;

    /**
     * @param host   Upstream host label (used for diagnostics + logs).
     * @param config Static configuration; must be non-null.
     * @param clock  Clock for time-of-day reads; must be non-null.
     */
    public UpstreamCircuitBreaker(
        final String host,
        final Supplier<CircuitBreakerConfig> config,
        final Clock clock
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        final CircuitBreakerConfig initial =
            Objects.requireNonNull(config.get(), "config.get()");
        this.backoffSeed = initial.seedBackoff();
        this.backoffCap = initial.maxBackoff();
        this.backoff = new FibonacciBackoff(this.backoffSeed, this.backoffCap);
        this.tripCount = new AtomicLong();
        this.blockedUntil = null;
        this.successes = new int[initial.windowSeconds()];
        this.failures = new int[initial.windowSeconds()];
        this.stamps = new long[initial.windowSeconds()];
    }

    /**
     * Fixed-config convenience constructor (tests, DB-less boots).
     * @param host   Upstream key label.
     * @param config Immutable configuration.
     * @param clock  Clock for time-of-day reads.
     */
    public UpstreamCircuitBreaker(
        final String host,
        final CircuitBreakerConfig config,
        final Clock clock
    ) {
        this(host, () -> config, clock);
    }

    /**
     * @return Host label this breaker is associated with.
     */
    public String host() {
        return this.host;
    }

    /**
     * @return {@code true} iff the breaker is currently open
     *     (outbound calls must fast-fail). The check is wall-clock
     *     aware: an entry whose {@code blockedUntil} has elapsed
     *     reports closed without requiring an explicit
     *     {@link #recordSuccess()}.
     */
    public boolean isOpen() {
        final Instant until = this.blockedUntil;
        return until != null && this.clock.instant().isBefore(until);
    }

    /**
     * Record an upstream HTTP status. A qualifying status (per
     * {@link CircuitBreakerConfig#shouldTripOnStatus()}) enters the
     * sliding window; the breaker trips only when the window meets the
     * configured minimum-calls and failure-rate thresholds.
     *
     * @param status HTTP status code from the upstream response.
     * @return {@code true} iff this call transitioned the breaker to open.
     */
    public synchronized boolean recordFailure(final int status) {
        return this.config.get().shouldTripOnStatus().test(status) && this.failureInWindow();
    }

    /**
     * Record an exception observed on the outbound call. A qualifying
     * exception (per {@link CircuitBreakerConfig#shouldTripOnException()})
     * enters the sliding window; the breaker trips only when the window
     * meets the configured minimum-calls and failure-rate thresholds.
     *
     * @param error Throwable observed; must be non-null.
     * @return {@code true} iff this call transitioned the breaker to open.
     */
    public synchronized boolean recordFailure(final Throwable error) {
        Objects.requireNonNull(error, "error");
        return this.config.get().shouldTripOnException().test(error) && this.failureInWindow();
    }

    /**
     * Unconditional re-trip after a failed recovery probe. The window
     * gate does not apply: the breaker already convicted this upstream
     * on window evidence, and a failed probe is authoritative proof it
     * is still down — one probe per block window is all the volume
     * there is.
     *
     * @param error Probe failure; must be non-null.
     */
    public synchronized void probeFailed(final Throwable error) {
        Objects.requireNonNull(error, "error");
        if (this.config.get().shouldTripOnException().test(error)) {
            this.trip();
        }
    }

    /**
     * Unconditional re-trip after a probe answered with a qualifying
     * failure status. See {@link #probeFailed(Throwable)}.
     *
     * @param status HTTP status the probe received.
     */
    public synchronized void probeFailed(final int status) {
        if (this.config.get().shouldTripOnStatus().test(status)) {
            this.trip();
        }
    }

    /**
     * Record a successful upstream response into the window, close the
     * breaker (clear {@code blockedUntil}) and reset the Fibonacci
     * backoff to the configured seed.
     *
     * @return {@code true} iff the breaker had a block set (still open
     *     or wall-clock-expired) and this success closed it — the
     *     caller logs the recovery exactly once.
     */
    public synchronized boolean recordSuccess() {
        this.markWindow(true);
        final boolean closed = this.blockedUntil != null;
        this.blockedUntil = null;
        this.backoff.reset();
        return closed;
    }

    /**
     * @return Time remaining before the breaker closes, or
     *     {@code null} when already closed. The returned duration is
     *     never negative.
     */
    public Duration timeRemaining() {
        final Instant until = this.blockedUntil;
        if (until == null) {
            return null;
        }
        final Instant now = this.clock.instant();
        if (!now.isBefore(until)) {
            return null;
        }
        return Duration.between(now, until);
    }

    /**
     * @return Wall-clock instant the breaker re-opens, or {@code null}
     *     when closed. Surface this to callers that need to set
     *     {@code Retry-After} on synthesised responses.
     */
    public Instant blockedUntil() {
        final Instant until = this.blockedUntil;
        if (until == null || !this.clock.instant().isBefore(until)) {
            return null;
        }
        return until;
    }

    /**
     * @return Total number of trips since construction. Monotonic,
     *     never reset. Use in metrics + dashboards.
     */
    public long tripCount() {
        return this.tripCount.get();
    }

    /**
     * Record a qualifying failure into the window and evaluate the
     * volume + rate gate. Caller holds the monitor.
     *
     * @return {@code true} iff this failure tripped the breaker.
     */
    private boolean failureInWindow() {
        final CircuitBreakerConfig cfg = this.config.get();
        this.markWindow(false);
        if (this.isOpen()) {
            return false;
        }
        int good = 0;
        int bad = 0;
        final long now = this.clock.instant().getEpochSecond();
        final long oldest = now - this.stamps.length + 1;
        for (int idx = 0; idx < this.stamps.length; idx = idx + 1) {
            if (this.stamps[idx] >= oldest) {
                good = good + this.successes[idx];
                bad = bad + this.failures[idx];
            }
        }
        final int total = good + bad;
        if (total < cfg.minimumCalls()) {
            return false;
        }
        final double rate = (double) bad / total;
        if (rate < cfg.failureRateThreshold()) {
            return false;
        }
        this.trip();
        this.clearWindow();
        return true;
    }

    /**
     * Stamp the current second's bucket (resetting it when it belongs
     * to an earlier window revolution) and count one outcome.
     * Caller holds the monitor.
     *
     * @param success Whether the outcome was a success.
     */
    private void markWindow(final boolean success) {
        final int configured = this.config.get().windowSeconds();
        if (configured != this.stamps.length) {
            // Admin changed the window length at runtime: re-allocate and
            // start counting fresh — a mixed-length window is meaningless.
            this.successes = new int[configured];
            this.failures = new int[configured];
            this.stamps = new long[configured];
        }
        final long now = this.clock.instant().getEpochSecond();
        final int idx = Math.floorMod(now, this.stamps.length);
        if (this.stamps[idx] != now) {
            this.stamps[idx] = now;
            this.successes[idx] = 0;
            this.failures[idx] = 0;
        }
        if (success) {
            this.successes[idx] = this.successes[idx] + 1;
        } else {
            this.failures[idx] = this.failures[idx] + 1;
        }
    }

    /**
     * Drop all window state — called on trip so the stale pre-trip
     * window cannot immediately re-convict a recovering upstream.
     * Caller holds the monitor.
     */
    private void clearWindow() {
        java.util.Arrays.fill(this.stamps, 0L);
        java.util.Arrays.fill(this.successes, 0);
        java.util.Arrays.fill(this.failures, 0);
    }

    /**
     * Advance the backoff and set {@code blockedUntil}. Called from
     * the synchronized failure paths.
     */
    private void trip() {
        final CircuitBreakerConfig cfg = this.config.get();
        if (!cfg.seedBackoff().equals(this.backoffSeed)
            || !cfg.maxBackoff().equals(this.backoffCap)) {
            // Admin changed the backoff shape at runtime: rebuild the
            // sequence from the new seed (progress resets — acceptable
            // on an explicit config change).
            this.backoffSeed = cfg.seedBackoff();
            this.backoffCap = cfg.maxBackoff();
            this.backoff = new FibonacciBackoff(this.backoffSeed, this.backoffCap);
        }
        final Duration window = this.backoff.next();
        this.blockedUntil = this.clock.instant().plus(window);
        this.tripCount.incrementAndGet();
    }
}
