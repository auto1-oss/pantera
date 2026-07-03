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
package com.auto1.pantera.http.timeout;

import java.time.Duration;

/**
 * Configuration for the rate-over-sliding-window circuit breaker.
 *
 * <p>Tripping rule: the breaker opens when the failure rate within the
 * sliding window exceeds {@link #failureRateThreshold()} AND the window
 * has accumulated at least {@link #minimumNumberOfCalls()} outcomes.
 * The minimum-volume gate is what protects low-traffic upstreams and
 * cold-start bursts: a handful of failures at startup never trip the
 * breaker on their own.</p>
 *
 * <p>After a trip the breaker blocks for {@link #initialBlockDuration()}
 * on the first trip; subsequent trips scale the window through a Fibonacci
 * sequence up to {@link #maxBlockDuration()} (handled by
 * {@link AutoBlockRegistry}, not this record).</p>
 *
 * <p>Replaces the pre-2.2.0 consecutive-count design, which tripped on any
 * {@code N} failures in a row regardless of total volume — a cold-start
 * burst of three TCP timeouts would open the circuit for 40 s, producing
 * silent 503s for every in-flight client request during that window.
 * See CHANGELOG v2.2.0 "Circuit breaker: rate-over-sliding-window replaces
 * consecutive-count".</p>
 *
 * @since 2.2.0
 */
public record AutoBlockSettings(
    double failureRateThreshold,
    int minimumNumberOfCalls,
    int slidingWindowSeconds,
    Duration initialBlockDuration,
    Duration maxBlockDuration
) {

    /**
     * Default values tuned for a public-internet artifact proxy:
     * <ul>
     *   <li>{@code failureRateThreshold = 0.5} — trip at 50 % errors in
     *       the window (Hystrix / Resilience4j convention).</li>
     *   <li>{@code minimumNumberOfCalls = 20} — require 20 outcomes
     *       before any trip can happen. Immune to cold-start bursts,
     *       and immune to single-digit transient failure bursts on
     *       low-traffic endpoints.</li>
     *   <li>{@code slidingWindowSeconds = 30} — trailing 30 s window.
     *       Long enough to smooth over single-request blips; short
     *       enough that a recovered upstream closes the circuit
     *       quickly as stale failure data ages out.</li>
     *   <li>{@code initialBlockDuration = 20 s} — first block. Halved
     *       from the pre-2.2.0 40 s to shrink the client-facing
     *       blast radius on any trip.</li>
     *   <li>{@code maxBlockDuration = 5 min} — upper bound under
     *       repeated probe failures (Fibonacci back-off, unchanged).</li>
     * </ul>
     */
    public static AutoBlockSettings defaults() {
        return new AutoBlockSettings(
            0.5, 20, 30,
            Duration.ofSeconds(20), Duration.ofMinutes(5)
        );
    }

    /**
     * Validate invariants.
     *
     * <ul>
     *   <li>{@code 0.0 < failureRateThreshold ≤ 1.0}</li>
     *   <li>{@code minimumNumberOfCalls ≥ 1}</li>
     *   <li>{@code slidingWindowSeconds ≥ 1}</li>
     *   <li>{@code initialBlockDuration}, {@code maxBlockDuration} both positive</li>
     *   <li>{@code initialBlockDuration ≤ maxBlockDuration}</li>
     * </ul>
     */
    public AutoBlockSettings {
        if (failureRateThreshold <= 0.0 || failureRateThreshold > 1.0) {
            throw new IllegalArgumentException(
                "failureRateThreshold must be in (0.0, 1.0], got " + failureRateThreshold
            );
        }
        if (minimumNumberOfCalls < 1) {
            throw new IllegalArgumentException(
                "minimumNumberOfCalls must be >= 1, got " + minimumNumberOfCalls
            );
        }
        if (slidingWindowSeconds < 1) {
            throw new IllegalArgumentException(
                "slidingWindowSeconds must be >= 1, got " + slidingWindowSeconds
            );
        }
        if (initialBlockDuration == null || initialBlockDuration.isNegative()
            || initialBlockDuration.isZero()) {
            throw new IllegalArgumentException(
                "initialBlockDuration must be positive, got " + initialBlockDuration
            );
        }
        if (maxBlockDuration == null || maxBlockDuration.compareTo(initialBlockDuration) < 0) {
            throw new IllegalArgumentException(
                "maxBlockDuration must be >= initialBlockDuration, got "
                    + maxBlockDuration + " vs " + initialBlockDuration
            );
        }
    }
}
