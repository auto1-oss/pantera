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
package com.auto1.pantera.http.context;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Monotonic wall-clock deadline carried through a single request's lifetime.
 *
 * <p>Implements §3.4 of {@code docs/analysis/v2.2-target-architecture.md}: an
 * end-to-end budget that each layer may shrink (never extend). {@link #in(Duration)}
 * fixes the expiry instant relative to {@link System#nanoTime()} at construction
 * time, so drift is bounded by the monotonic clock (not the wall clock, which can
 * jump). {@link #remaining()} is clamped non-negative: once the deadline fires,
 * the remaining budget is {@link Duration#ZERO}, not a negative value.
 *
 * <p>This is a value record — safe to share across threads, safe to embed in
 * the immutable {@link RequestContext}. It is not emitted to ECS logs (the
 * expiry instant has no meaning once the request is gone), but it is carried
 * through so that HTTP clients, JDBC drivers, and upstream RPC wrappers can
 * cap their own timeouts via {@link #remainingClamped(Duration)}.
 *
 * @param expiresAtNanos the {@link System#nanoTime()} value at which the
 *                       deadline fires
 * @since 2.2.0
 */
public record Deadline(long expiresAtNanos) {

    /**
     * Create a deadline {@code d} from now.
     *
     * @param d the budget relative to the monotonic clock at this instant;
     *          must be non-null
     * @return a new deadline whose expiry equals {@code System.nanoTime() + d.toNanos()}
     */
    public static Deadline in(final Duration d) {
        Objects.requireNonNull(d, "d");
        return new Deadline(System.nanoTime() + d.toNanos());
    }

    /**
     * Time remaining until the deadline fires, clamped to zero once reached.
     *
     * @return a non-negative {@link Duration}; {@link Duration#ZERO} once
     *         {@code System.nanoTime() >= expiresAtNanos}
     */
    public Duration remaining() {
        final long left = this.expiresAtNanos - System.nanoTime();
        return left <= 0L ? Duration.ZERO : Duration.ofNanos(left);
    }

    /**
     * Whether the deadline has already fired.
     *
     * @return {@code true} iff {@link #remaining()} is zero
     */
    public boolean expired() {
        return this.remaining().isZero();
    }

    /**
     * Remaining budget, capped at {@code max}. For use with APIs that take a
     * bounded timeout (JDBC {@code setQueryTimeout}, HTTP client read timeout,
     * etc.) — cap so no single operation consumes the whole budget.
     *
     * @param max the maximum per-operation timeout; must be non-null
     * @return {@link #remaining()} if less than or equal to {@code max}, else
     *         {@code max}
     */
    public Duration remainingClamped(final Duration max) {
        Objects.requireNonNull(max, "max");
        final Duration rem = this.remaining();
        return rem.compareTo(max) > 0 ? max : rem;
    }

    /**
     * Wall-clock instant at which this deadline will (or did) fire. Computed
     * from the current wall clock plus {@link #remaining()}; drifts slightly
     * if the wall clock jumps, but is useful for logging and for setting
     * absolute timeouts on APIs that don't accept a {@link Duration}.
     *
     * @return the {@link Instant} at which {@link #expired()} becomes true
     */
    public Instant expiresAt() {
        return Instant.now().plus(this.remaining());
    }
}
