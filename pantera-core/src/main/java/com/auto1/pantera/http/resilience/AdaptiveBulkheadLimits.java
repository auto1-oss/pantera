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
package com.auto1.pantera.http.resilience;

import java.time.Duration;

/**
 * Configuration for an {@link AdaptivePermitController}-backed {@link RepoBulkhead}.
 *
 * <p>When {@link #adaptive()} is {@code true}, the controller AIMD-tunes the
 * in-flight permit count between {@link #minPermits()} and {@link #maxPermits()}
 * starting from {@link #initialPermits()}. Every {@link #windowSeconds()}
 * seconds the controller evaluates the previous window's outcomes:
 *
 * <ul>
 *   <li>any error in the window → multiplicative decrease by
 *       {@link #rampDownFactor()} (clamped at {@link #minPermits()})</li>
 *   <li>peak latency in the window above {@code 2 ×}{@link #targetP99Millis()}
 *       → softer multiplicative decrease (square-root of the factor)</li>
 *   <li>peak latency in the window at or below {@link #targetP99Millis()} and
 *       no errors → additive increase by {@link #rampUpStep()} (clamped at
 *       {@link #maxPermits()})</li>
 *   <li>otherwise → hold steady</li>
 * </ul>
 *
 * <p>When {@link #adaptive()} is {@code false}, the controller uses a fixed
 * permit count equal to {@link #initialPermits()} and the AIMD step is a no-op.
 * Use {@link #fixed(int, int, Duration)} to construct that mode.
 *
 * @param adaptive         {@code true} to enable AIMD tuning; {@code false} for fixed permits
 * @param minPermits       Lower bound on dynamic permits (also used as the fixed value's floor)
 * @param maxPermits       Upper bound on dynamic permits (also the hard cap on concurrency)
 * @param initialPermits   Starting permit count (must lie in {@code [minPermits, maxPermits]})
 * @param targetP99Millis  Per-op latency target; the window's max latency is compared to this
 * @param windowSeconds    AIMD evaluation interval
 * @param rampUpStep       Permits added per healthy window
 * @param rampDownFactor   Multiplier applied to permits on a bad window (strictly in {@code (0, 1)})
 * @param maxQueueDepth    Per-repo drain pool queue depth (unchanged from legacy {@link BulkheadLimits})
 * @param retryAfter       Suggested {@code Retry-After} duration when the bulkhead rejects
 * @since 2.2.0
 */
public record AdaptiveBulkheadLimits(
    boolean adaptive,
    int minPermits,
    int maxPermits,
    int initialPermits,
    long targetP99Millis,
    int windowSeconds,
    int rampUpStep,
    double rampDownFactor,
    int maxQueueDepth,
    Duration retryAfter
) {

    /** Canonical constructor with validation. */
    public AdaptiveBulkheadLimits {
        if (minPermits < 1) {
            throw new IllegalArgumentException(
                "minPermits must be >= 1: " + minPermits
            );
        }
        if (maxPermits < minPermits) {
            throw new IllegalArgumentException(
                "maxPermits (" + maxPermits + ") must be >= minPermits (" + minPermits + ")"
            );
        }
        if (initialPermits < minPermits || initialPermits > maxPermits) {
            throw new IllegalArgumentException(
                "initialPermits (" + initialPermits + ") must lie in ["
                    + minPermits + ", " + maxPermits + "]"
            );
        }
        if (targetP99Millis <= 0) {
            throw new IllegalArgumentException(
                "targetP99Millis must be > 0: " + targetP99Millis
            );
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException(
                "windowSeconds must be > 0: " + windowSeconds
            );
        }
        if (rampUpStep <= 0) {
            throw new IllegalArgumentException(
                "rampUpStep must be > 0: " + rampUpStep
            );
        }
        if (rampDownFactor <= 0.0 || rampDownFactor >= 1.0) {
            throw new IllegalArgumentException(
                "rampDownFactor must be in (0, 1): " + rampDownFactor
            );
        }
        if (maxQueueDepth <= 0) {
            throw new IllegalArgumentException(
                "maxQueueDepth must be > 0: " + maxQueueDepth
            );
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException(
                "retryAfter must be strictly positive: " + retryAfter
            );
        }
    }

    /**
     * Default adaptive limits suitable for a typical proxy upstream.
     *
     * <p>Initial=10 lets a single user fan out 10 concurrent fetches; the
     * AIMD step grows toward {@code maxPermits=100} as long as upstream
     * stays healthy (no errors, peak latency &le; 500&nbsp;ms). The first
     * sign of trouble halves the permits, immediately preserving fairness
     * for other repos.
     *
     * @return Adaptive defaults.
     */
    public static AdaptiveBulkheadLimits defaults() {
        return new AdaptiveBulkheadLimits(
            true,
            5,
            100,
            10,
            500L,
            5,
            1,
            0.5,
            1000,
            Duration.ofSeconds(1)
        );
    }

    /**
     * Construct fixed (non-adaptive) limits — equivalent to the legacy
     * {@link BulkheadLimits} record's behaviour.
     *
     * @param permits        Fixed permit count
     * @param maxQueueDepth  Drain pool queue depth
     * @param retryAfter     Retry-After suggestion on overflow
     * @return Limits with {@code adaptive = false} and {@code min = max = initial = permits}
     */
    public static AdaptiveBulkheadLimits fixed(
        final int permits,
        final int maxQueueDepth,
        final Duration retryAfter
    ) {
        return new AdaptiveBulkheadLimits(
            false, permits, permits, permits,
            Long.MAX_VALUE, 60, 1, 0.5, maxQueueDepth, retryAfter
        );
    }

    /**
     * Bridge a legacy {@link BulkheadLimits} into the adaptive shape with
     * {@code adaptive = false}, preserving source compatibility for
     * {@link RepoBulkhead#RepoBulkhead(String, BulkheadLimits, java.util.concurrent.Executor)}.
     *
     * @param legacy Legacy fixed limits.
     * @return Adaptive-shaped fixed limits.
     */
    public static AdaptiveBulkheadLimits fromLegacy(final BulkheadLimits legacy) {
        return fixed(legacy.maxConcurrent(), legacy.maxQueueDepth(), legacy.retryAfter());
    }
}
