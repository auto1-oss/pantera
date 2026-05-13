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
package com.auto1.pantera.http.client.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-upstream-host token bucket plus 429 / Retry-After gate. One
 * instance is shared across the JVM — held by
 * {@link com.auto1.pantera.http.client.jetty.JettyClientSlices} so that
 * every outbound request through any adapter funnels through the same
 * governor.
 *
 * <p>Two coupled mechanisms run per host:
 * <ol>
 *   <li><b>Token bucket.</b> Refilled at a configured rate
 *       ({@link RateLimitConfig#refillPerSecond(String)}). Each outbound
 *       call invokes {@link #tryAcquire(String)}; an empty bucket returns
 *       {@code false} and the caller fails-fast with {@code outcome="rate_limited"}.</li>
 *   <li><b>429 / Retry-After gate.</b> A 429 (or 503 with Retry-After)
 *       closes the gate until {@code now + retryAfter}; while closed,
 *       {@link #tryAcquire(String)} returns {@code false} regardless of
 *       token state. {@link #gateOpenUntil(String)} exposes the deadline
 *       so foreground responses can carry the right Retry-After value
 *       back to the client.</li>
 * </ol>
 *
 * <p>State is per host (case-insensitive). Granularity matches the
 * per-IP throttling Maven Central and Cloudflare-fronted registries
 * actually enforce; we deliberately do NOT subdivide by repo or
 * caller_tag because the upstream's budget is shared.
 *
 * <p>Thread-safety: every public method is safe under concurrent calls.
 * Per-host state is updated via CAS on an {@link AtomicReference} — no
 * locking.
 *
 * @since 2.2.0
 */
public interface UpstreamRateLimiter {

    /** Fallback gate duration when no Retry-After is provided. */
    Duration DEFAULT_GATE_DURATION = Duration.ofSeconds(30);

    /**
     * @param host Upstream host (lower-cased internally).
     * @return {@code true} when admission was granted (token consumed
     *     and gate open); {@code false} when either the bucket is empty
     *     or the gate is currently closed.
     */
    boolean tryAcquire(String host);

    /**
     * Inspect the upstream response. Implementations close the per-host
     * gate on 429 / 503-with-Retry-After. Other statuses are no-ops.
     */
    void recordResponse(String host, int status, Duration retryAfter);

    /** Explicit rate-limit event (test injection, integration smoke tests). */
    void recordRateLimit(String host, Duration retryAfter);

    /**
     * @return the {@link Instant} the gate re-opens, or {@code null}
     *     when the gate is currently open.
     */
    Instant gateOpenUntil(String host);

    /**
     * Default token-bucket implementation. Public so
     * {@code RepositorySlices} can construct + share a single instance
     * across all per-repo Jetty clients.
     *
     * @since 2.2.0
     */
    final class Default implements UpstreamRateLimiter {

        private final RateLimitConfig config;
        private final Clock clock;
        private final Map<String, AtomicReference<Bucket>> buckets = new ConcurrentHashMap<>();

        public Default(final RateLimitConfig config, final Clock clock) {
            this.config = config;
            this.clock = clock;
        }

        @Override
        public boolean tryAcquire(final String host) {
            final String key = normalise(host);
            final AtomicReference<Bucket> ref = bucketFor(key);
            while (true) {
                final Bucket current = ref.get();
                final Instant now = this.clock.instant();
                if (current.gateUntil != null && now.isBefore(current.gateUntil)) {
                    return false;
                }
                final double refilled = refill(current, now);
                if (refilled < 1.0) {
                    final Bucket touched = new Bucket(
                        refilled, now,
                        current.gateUntil, current.refillPerSecond, current.burstCapacity
                    );
                    // Best-effort timestamp advance — failure means another
                    // thread won and our advance is moot; either way, return false.
                    ref.compareAndSet(current, touched);
                    return false;
                }
                final Bucket next = new Bucket(
                    refilled - 1.0, now,
                    current.gateUntil != null && !now.isBefore(current.gateUntil)
                        ? null : current.gateUntil,
                    current.refillPerSecond, current.burstCapacity
                );
                if (ref.compareAndSet(current, next)) {
                    return true;
                }
            }
        }

        @Override
        public void recordResponse(final String host, final int status, final Duration retryAfter) {
            if (status == 429 || (status == 503 && retryAfter != null && !retryAfter.isZero())) {
                recordRateLimit(host, retryAfter == null ? Duration.ZERO : retryAfter);
            }
        }

        @Override
        public void recordRateLimit(final String host, final Duration retryAfter) {
            final String key = normalise(host);
            final Duration window = retryAfter == null || retryAfter.isZero()
                ? DEFAULT_GATE_DURATION : retryAfter;
            final Instant gateUntil = this.clock.instant().plus(window);
            final AtomicReference<Bucket> ref = bucketFor(key);
            while (true) {
                final Bucket current = ref.get();
                final Instant target = current.gateUntil != null && current.gateUntil.isAfter(gateUntil)
                    ? current.gateUntil : gateUntil;
                final Bucket next = new Bucket(
                    current.tokens, current.lastRefill, target,
                    current.refillPerSecond, current.burstCapacity
                );
                if (ref.compareAndSet(current, next)) {
                    return;
                }
            }
        }

        @Override
        public Instant gateOpenUntil(final String host) {
            final AtomicReference<Bucket> ref = this.buckets.get(normalise(host));
            if (ref == null) {
                return null;
            }
            final Bucket b = ref.get();
            if (b.gateUntil == null || !this.clock.instant().isBefore(b.gateUntil)) {
                return null;
            }
            return b.gateUntil;
        }

        private AtomicReference<Bucket> bucketFor(final String key) {
            return this.buckets.computeIfAbsent(
                key, k -> {
                    final double rate = this.config.refillPerSecond(k);
                    final double burst = this.config.burstCapacity(k);
                    return new AtomicReference<>(
                        new Bucket(burst, this.clock.instant(), null, rate, burst)
                    );
                }
            );
        }

        private static double refill(final Bucket current, final Instant now) {
            final long elapsedNanos = Duration.between(current.lastRefill, now).toNanos();
            if (elapsedNanos <= 0) {
                return current.tokens;
            }
            return Math.min(
                current.tokens + (elapsedNanos / 1_000_000_000.0) * current.refillPerSecond,
                current.burstCapacity
            );
        }

        private static String normalise(final String host) {
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        }

        /**
         * Immutable per-host bucket state. CAS-updated so concurrent
         * acquires see a coherent view.
         */
        private record Bucket(
            double tokens, Instant lastRefill, Instant gateUntil,
            double refillPerSecond, double burstCapacity
        ) { }
    }
}
