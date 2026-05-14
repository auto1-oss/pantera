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
 *       with a status / exception that satisfies the configured
 *       trip predicate: advances the Fibonacci backoff and sets
 *       {@code blockedUntil = now + backoff}.</li>
 *   <li>{@link #recordSuccess()}: clears {@code blockedUntil} and
 *       resets the backoff to seed.</li>
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
     * Static configuration (seed, cap, trip predicates).
     */
    private final CircuitBreakerConfig config;

    /**
     * Clock used to derive {@code blockedUntil} and {@link #timeRemaining()}.
     * Injectable for tests.
     */
    private final Clock clock;

    /**
     * Mutable Fibonacci sequence advanced on every trip and reset on
     * recovery. Owns its own intrinsic lock; we still hold this
     * breaker's lock while interacting with it so the entire trip /
     * reset transaction is atomic.
     */
    private final FibonacciBackoff backoff;

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
     * @param host   Upstream host label (used for diagnostics + logs).
     * @param config Static configuration; must be non-null.
     * @param clock  Clock for time-of-day reads; must be non-null.
     */
    public UpstreamCircuitBreaker(
        final String host,
        final CircuitBreakerConfig config,
        final Clock clock
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.backoff = new FibonacciBackoff(config.seedBackoff(), config.maxBackoff());
        this.tripCount = new AtomicLong();
        this.blockedUntil = null;
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
     * Record an upstream HTTP status. If the status satisfies
     * {@link CircuitBreakerConfig#shouldTripOnStatus(int)}, trip the
     * breaker.
     *
     * @param status HTTP status code from the upstream response.
     */
    public synchronized void recordFailure(final int status) {
        if (this.config.shouldTripOnStatus().test(status)) {
            this.trip();
        }
    }

    /**
     * Record an exception observed on the outbound call. If the
     * exception satisfies
     * {@link CircuitBreakerConfig#shouldTripOnException(Throwable)},
     * trip the breaker.
     *
     * @param error Throwable observed; must be non-null.
     */
    public synchronized void recordFailure(final Throwable error) {
        Objects.requireNonNull(error, "error");
        if (this.config.shouldTripOnException().test(error)) {
            this.trip();
        }
    }

    /**
     * Record a successful upstream response. Closes the breaker
     * (clears {@code blockedUntil}) and resets the Fibonacci backoff
     * to the configured seed.
     */
    public synchronized void recordSuccess() {
        this.blockedUntil = null;
        this.backoff.reset();
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
     * Advance the backoff and set {@code blockedUntil}. Called from
     * the synchronized {@code recordFailure} entry points.
     */
    private void trip() {
        final Duration window = this.backoff.next();
        this.blockedUntil = this.clock.instant().plus(window);
        this.tripCount.incrementAndGet();
    }
}
