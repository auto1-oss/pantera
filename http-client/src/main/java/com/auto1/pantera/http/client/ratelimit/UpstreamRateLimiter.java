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
 * Per-upstream-host reactive 429 / Retry-After gate. One instance is
 * shared across the JVM — held by
 * {@link com.auto1.pantera.http.client.jetty.JettyClientSlices} so that
 * every outbound request through any adapter funnels through the same
 * governor.
 *
 * <p>A 429 (or 503 with Retry-After) closes the per-host gate until
 * {@code now + retryAfter}; {@link #gateOpenUntil(String)} exposes the
 * deadline so callers fast-fail with the right Retry-After while the
 * gate is closed. There is deliberately no proactive token-bucket
 * admission: Pantera forwards at line rate and honours the upstream's
 * own throttle signals (the last remnant of the old proactive bucket
 * was removed as dead code — it had no production callers).
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

        private final Clock clock;
        private final Map<String, AtomicReference<Bucket>> buckets = new ConcurrentHashMap<>();

        public Default(final Clock clock) {
            this.clock = clock;
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
                final Bucket next = new Bucket(target);
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
                key, k -> new AtomicReference<>(new Bucket(null))
            );
        }

        private static String normalise(final String host) {
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        }

        /**
         * Immutable per-host gate state. CAS-updated so concurrent
         * writers see a coherent view. {@code gateUntil == null} means
         * the gate is open.
         */
        private record Bucket(Instant gateUntil) { }
    }
}
