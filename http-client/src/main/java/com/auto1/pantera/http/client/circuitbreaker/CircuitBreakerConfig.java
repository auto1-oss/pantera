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
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Configuration knobs for {@link UpstreamCircuitBreaker}.
 *
 * <p>The {@link #defaults()} factory returns the production-tuned
 * shape: 30 s seed, 60 min cap, trip on every 5xx, trip on every
 * exception except local backpressure
 * ({@link RejectedExecutionException}). 4xx is deliberately not in
 * the trip set — 4xx means the upstream is healthy and is telling us
 * our request is wrong (bad auth, missing permission, not found,
 * rate-limited, …). See {@link #defaultStatusTrip} for the full
 * rationale. 429 in particular is handled by the reactive rate-limit
 * gate in {@link com.auto1.pantera.http.client.ratelimit.RateLimitedClientSlice}
 * which honours the upstream's {@code Retry-After}.
 *
 * @param seedBackoff           Initial cooldown after the first trip.
 *                              Subsequent trips grow along the
 *                              Fibonacci sequence in
 *                              {@link FibonacciBackoff}.
 * @param maxBackoff            Upper bound on any single cooldown.
 * @param shouldTripOnException Returns {@code true} when the given
 *                              exception should trip the breaker.
 *                              Returns {@code false} for local
 *                              backpressure or expected
 *                              connection-lifecycle events.
 * @param shouldTripOnStatus    Returns {@code true} when the given
 *                              HTTP status should trip the breaker.
 *
 * @since 2.2.0
 */
public record CircuitBreakerConfig(
    Duration seedBackoff,
    Duration maxBackoff,
    Predicate<Throwable> shouldTripOnException,
    IntPredicate shouldTripOnStatus
) {

    /**
     * Compact constructor — validates non-null + positive durations.
     */
    public CircuitBreakerConfig {
        Objects.requireNonNull(seedBackoff, "seedBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(shouldTripOnException, "shouldTripOnException");
        Objects.requireNonNull(shouldTripOnStatus, "shouldTripOnStatus");
        if (seedBackoff.isNegative() || seedBackoff.isZero()) {
            throw new IllegalArgumentException(
                "seedBackoff must be strictly positive: " + seedBackoff
            );
        }
        if (maxBackoff.compareTo(seedBackoff) < 0) {
            throw new IllegalArgumentException(
                "maxBackoff must be >= seedBackoff (seed=" + seedBackoff
                    + ", max=" + maxBackoff + ")"
            );
        }
    }

    /**
     * Production defaults. 30 s seed, 60 min cap, trip predicate fires
     * on 5xx only (see {@link #defaultStatusTrip}) and on every
     * exception except {@link RejectedExecutionException}.
     *
     * @return Default config instance.
     */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(
            Duration.ofSeconds(30),
            Duration.ofMinutes(60),
            CircuitBreakerConfig::defaultExceptionTrip,
            CircuitBreakerConfig::defaultStatusTrip
        );
    }

    /**
     * Default exception-trip predicate. Trips on every exception
     * except {@link RejectedExecutionException}, which signals local
     * thread-pool backpressure rather than upstream brokenness.
     *
     * @param err Throwable produced by the outbound call.
     * @return {@code true} iff the breaker should trip.
     */
    private static boolean defaultExceptionTrip(final Throwable err) {
        return !(err instanceof RejectedExecutionException);
    }

    /**
     * Default status-trip predicate. Trips on 5xx only.
     *
     * <p>By HTTP semantics, 4xx is a <em>client</em> error — the upstream
     * is responding correctly, telling us our request is wrong (bad auth,
     * missing permission, not found, wrong method, rate-limited, …). The
     * upstream is healthy; tripping the breaker on 4xx would stop talking
     * to a working server because of our own request shape. Specific
     * cases that surface this:
     * <ul>
     *   <li>{@code 401} — Docker Registry V2 / OCI bearer-auth challenge.
     *       Every first unauthenticated request returns 401 with
     *       {@code WWW-Authenticate: Bearer …}; the auth slice handles
     *       it. Counting it as a breaker failure trips the gate after
     *       ~20 cold pulls and cascades into total upstream failure.</li>
     *   <li>{@code 403} — permission denial: the upstream is correctly
     *       enforcing access policy, not malfunctioning.</li>
     *   <li>{@code 404} — resource genuinely missing.</li>
     *   <li>{@code 407} — same shape as 401 for upstream HTTP proxies.</li>
     *   <li>{@code 408 / 410 / 412 / 413 / 414 / 415 / 422 / 451} —
     *       all client-side issues with the request, not upstream
     *       brokenness.</li>
     *   <li>{@code 429} — rate-limit response. Owned by the reactive
     *       rate-limit gate in
     *       {@link com.auto1.pantera.http.client.ratelimit.RateLimitedClientSlice}
     *       which honours {@code Retry-After}.</li>
     * </ul>
     *
     * <p>5xx is what the breaker is built for: the upstream itself failed
     * to produce a response. {@code 500 / 502 / 503 / 504} all indicate
     * an unhealthy server or gateway and warrant backing off.
     *
     * <p>Connection-level failures (timeout, refused, TLS errors) are
     * handled by {@link #shouldTripOnException}, not this predicate.
     *
     * @param status HTTP status code from the upstream response.
     * @return {@code true} iff the breaker should trip.
     */
    private static boolean defaultStatusTrip(final int status) {
        return status >= 500;
    }
}
