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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-host circuit-breaker registry. Mirrors
 * {@link com.auto1.pantera.http.client.ratelimit.UpstreamRateLimiter}'s
 * one-instance-per-JVM pattern so every adapter's per-host slice
 * decorator consults the same breaker for a given upstream host.
 *
 * <p>Breakers are created lazily on first {@link #breakerFor(String)}
 * call using the registry's configured {@link CircuitBreakerConfig}
 * and {@link Clock}. Hosts are normalised to lower-case ASCII so
 * {@code Repo1.Maven.org} and {@code repo1.maven.org} share state.
 *
 * @since 2.2.0
 */
public interface UpstreamCircuitBreakerRegistry {

    /**
     * Resolve (creating on first call) the breaker for {@code host}.
     *
     * @param host Upstream host. May be any case; normalised to lower
     *     case internally. Must be non-null.
     * @return The shared breaker instance for that host.
     */
    UpstreamCircuitBreaker breakerFor(String host);

    /**
     * Default in-memory registry. One instance per JVM, held by
     * {@link com.auto1.pantera.http.client.jetty.JettyClientSlices}.
     */
    final class Default implements UpstreamCircuitBreakerRegistry {

        /**
         * Live configuration source shared by every breaker.
         */
        private final java.util.function.Supplier<CircuitBreakerConfig> config;

        /**
         * Clock injected into every breaker.
         */
        private final Clock clock;

        /**
         * Host → breaker mapping. {@link ConcurrentHashMap#computeIfAbsent}
         * gives lazy single-init.
         */
        private final Map<String, UpstreamCircuitBreaker> breakers = new ConcurrentHashMap<>();

        /**
         * @param config Configuration applied to every constructed breaker.
         * @param clock  Clock used by every constructed breaker.
         */
        public Default(
            final java.util.function.Supplier<CircuitBreakerConfig> config,
            final Clock clock
        ) {
            this.config = Objects.requireNonNull(config, "config");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        /**
         * Fixed-config convenience constructor (tests).
         * @param config Immutable configuration for every breaker.
         * @param clock  Clock injected into every breaker.
         */
        public Default(final CircuitBreakerConfig config, final Clock clock) {
            this(() -> config, clock);
        }

        @Override
        public UpstreamCircuitBreaker breakerFor(final String host) {
            final String key = normalise(host);
            return this.breakers.computeIfAbsent(
                key, k -> {
                    final UpstreamCircuitBreaker breaker =
                        new UpstreamCircuitBreaker(k, this.config, this.clock);
                    // T-P02b: register a polling state gauge so the
                    // breaker's open/closed state is visible in
                    // /metrics without needing explicit transition
                    // events. The gauge is registered exactly once
                    // per host by MicrometerMetrics' idempotency
                    // guard.
                    if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                        com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                            .registerCircuitBreakerStateGauge(k, breaker::isOpen);
                    }
                    return breaker;
                }
            );
        }

        private static String normalise(final String host) {
            Objects.requireNonNull(host, "host");
            return host.toLowerCase(Locale.ROOT);
        }
    }
}
