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

import java.time.Duration;
import java.util.Objects;

/**
 * Fibonacci-spaced backoff sequence used by
 * {@link UpstreamCircuitBreaker} to schedule successive block windows.
 *
 * <p>The sequence is {@code F(0)=seed, F(1)=seed, F(n)=F(n-1)+F(n-2)},
 * each value clamped at {@code cap}. With the defaults (seed=30 s,
 * cap=3 600 s) the produced sequence is:
 * {@code 30, 30, 60, 90, 150, 240, 390, 630, 1020, 1650, 2670, 3600,
 * 3600, ...}.
 *
 * <p>Calls to {@link #next()} advance the sequence; {@link #reset()}
 * returns to the seed. All methods are thread-safe under concurrent
 * callers (synchronised on {@code this}); contention is not a concern
 * because callers (circuit breakers) invoke {@code next()} only on
 * trip and {@code reset()} only on recovery — both rare events.
 *
 * @since 2.2.0
 */
public final class FibonacciBackoff {

    /**
     * Seed value in milliseconds (both {@code F(0)} and {@code F(1)}
     * equal this). Stored as millis (rather than seconds) so
     * sub-second seeds — useful in tests with tight wall-clock
     * windows — survive without truncation.
     */
    private final long seedMillis;

    /**
     * Upper bound (millis) on any value returned by {@link #next()}.
     */
    private final long capMillis;

    /**
     * Previous value (millis). Updated under intrinsic lock.
     */
    private long prevMillis;

    /**
     * Current value (millis) about to be returned by {@link #next()}.
     * Updated under intrinsic lock.
     */
    private long currMillis;

    /**
     * Count of {@link #next()} invocations since the last
     * {@link #reset()}; used to keep the first two emissions both
     * equal to {@code seed} per the {@code F(0)=F(1)=seed} contract.
     */
    private long invocations;

    /**
     * @param seed Seed duration. Must be strictly positive.
     * @param cap  Upper bound on any returned duration; values that
     *             would exceed it are clamped. Must be ≥ {@code seed}.
     */
    public FibonacciBackoff(final Duration seed, final Duration cap) {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(cap, "cap");
        if (seed.isNegative() || seed.isZero()) {
            throw new IllegalArgumentException(
                "seed must be strictly positive: " + seed
            );
        }
        if (cap.compareTo(seed) < 0) {
            throw new IllegalArgumentException(
                "cap must be >= seed (seed=" + seed + ", cap=" + cap + ")"
            );
        }
        this.seedMillis = seed.toMillis();
        this.capMillis = cap.toMillis();
        this.prevMillis = this.seedMillis;
        this.currMillis = this.seedMillis;
        this.invocations = 0;
    }

    /**
     * Advance the sequence and return the next backoff value.
     *
     * @return Strictly positive {@link Duration}, ≤ the configured cap.
     */
    public synchronized Duration next() {
        final long result;
        if (this.invocations < 2) {
            result = this.seedMillis;
        } else {
            final long sum = this.prevMillis + this.currMillis;
            result = Math.min(sum, this.capMillis);
            this.prevMillis = this.currMillis;
            this.currMillis = result;
        }
        this.invocations = this.invocations + 1;
        return Duration.ofMillis(result);
    }

    /**
     * Reset the sequence to its initial state. The next {@link #next()}
     * call will return the seed again.
     */
    public synchronized void reset() {
        this.prevMillis = this.seedMillis;
        this.currMillis = this.seedMillis;
        this.invocations = 0;
    }

    /**
     * @return Configured seed duration.
     */
    public Duration seed() {
        return Duration.ofMillis(this.seedMillis);
    }

    /**
     * @return Configured cap duration.
     */
    public Duration cap() {
        return Duration.ofMillis(this.capMillis);
    }
}
