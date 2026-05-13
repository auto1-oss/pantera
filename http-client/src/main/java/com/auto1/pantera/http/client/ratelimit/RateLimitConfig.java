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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Per-upstream-host rate-limit configuration.
 *
 * <p>Holds two scalars per host:
 * <ul>
 *   <li><b>refill rate</b> — tokens added per second (steady-state RPS cap).</li>
 *   <li><b>burst capacity</b> — bucket size (how many tokens can accumulate
 *       during a quiet period before they cap out).</li>
 * </ul>
 *
 * <p>Defaults are conservative and derived from observed third-party
 * registry tolerances:
 * <ul>
 *   <li>{@code repo1.maven.org}: 20 req/s, burst 40. Maven Central's
 *       Cloudflare front-end starts 429-ing around 25-30 req/s
 *       per-IP; 20 leaves headroom.</li>
 *   <li>{@code registry.npmjs.org}: 30 req/s, burst 60. npm's CDN is
 *       more permissive but still bursty under cold-cache walks.</li>
 *   <li>Default (any other host): 10 req/s, burst 20. Conservative so
 *       a misconfigured upstream cannot induce a 429 storm before an
 *       operator notices.</li>
 * </ul>
 *
 * <p>Per-host overrides are case-insensitive and matched on the full
 * hostname (no wildcards). Unknown hosts fall back to the global
 * default. The override map is immutable; reconfiguration requires
 * boot-time wiring (a future runtime-tunable feature can hot-reload
 * this by replacing the {@link UpstreamRateLimiter} reference).
 *
 * @since 2.2.0
 */
public final class RateLimitConfig {

    /** Maven Central's Cloudflare front-end. */
    public static final String MAVEN_CENTRAL = "repo1.maven.org";

    /** npm public registry. */
    public static final String NPM_PUBLIC = "registry.npmjs.org";

    private final double defaultRefillPerSecond;
    private final double defaultBurstCapacity;
    private final Map<String, double[]> overrides;

    private RateLimitConfig(
        final double defaultRefillPerSecond,
        final double defaultBurstCapacity,
        final Map<String, double[]> overrides
    ) {
        this.defaultRefillPerSecond = defaultRefillPerSecond;
        this.defaultBurstCapacity = defaultBurstCapacity;
        this.overrides = Map.copyOf(overrides);
    }

    /**
     * Conservative production defaults. Use this in normal wiring;
     * tests and the perf harness construct alternative configs via
     * {@link #builder()}.
     *
     * @return Config with the default per-registry rates.
     */
    public static RateLimitConfig defaults() {
        return builder()
            .perHost(MAVEN_CENTRAL, 20.0, 40.0)
            .perHost(NPM_PUBLIC, 30.0, 60.0)
            .build();
    }

    /**
     * Constructs a config with no per-host overrides at all and a
     * global rate/burst of {@code rate}, {@code burst}. Used by tests
     * that want to verify behaviour without leaking production defaults.
     */
    public static RateLimitConfig uniform(final double rate, final double burst) {
        return new Builder()
            .defaultRefillPerSecond(rate)
            .defaultBurstCapacity(burst)
            .build();
    }

    public double refillPerSecond(final String host) {
        final double[] hostCfg = this.overrides.get(normalise(host));
        return hostCfg == null ? this.defaultRefillPerSecond : hostCfg[0];
    }

    public double burstCapacity(final String host) {
        final double[] hostCfg = this.overrides.get(normalise(host));
        return hostCfg == null ? this.defaultBurstCapacity : hostCfg[1];
    }

    /** New mutable builder pre-seeded with defaults. */
    public static Builder builder() {
        return new Builder();
    }

    private static String normalise(final String host) {
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    public static final class Builder {

        private double defaultRefillPerSecond = 10.0;
        private double defaultBurstCapacity = 20.0;
        private final java.util.Map<String, double[]> overrides = new java.util.HashMap<>();

        public Builder defaultRefillPerSecond(final double rate) {
            check(rate > 0.0, "rate must be > 0");
            this.defaultRefillPerSecond = rate;
            return this;
        }

        public Builder defaultBurstCapacity(final double burst) {
            check(burst > 0.0, "burst must be > 0");
            this.defaultBurstCapacity = burst;
            return this;
        }

        public Builder perHost(final String host, final double rate, final double burst) {
            Objects.requireNonNull(host, "host");
            check(rate > 0.0, "rate must be > 0");
            check(burst > 0.0, "burst must be > 0");
            this.overrides.put(normalise(host), new double[]{rate, burst});
            return this;
        }

        public RateLimitConfig build() {
            return new RateLimitConfig(
                this.defaultRefillPerSecond,
                this.defaultBurstCapacity,
                this.overrides
            );
        }

        private static void check(final boolean condition, final String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
