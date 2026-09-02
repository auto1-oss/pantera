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
package com.auto1.pantera.auth;

import com.auto1.pantera.settings.policy.LoginThrottleConfig;
import com.auto1.pantera.settings.policy.LoginThrottleSettingsLoader;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * In-memory login attempt throttle keyed by an opaque string (the caller
 * supplies {@code username|clientIp}). After {@code maxFailures} failures the
 * key is locked out for {@code window}; a success clears it. Prevents unbounded
 * online password guessing against the public login endpoint (SecOps
 * import-misc).
 *
 * <p>Single-node / best-effort: state is per-instance. That is the right
 * trade-off for a slow-down control — a distributed limiter would add a hot
 * shared-store round trip to every login. Entries are self-expiring so the map
 * stays bounded under sustained attack against many keys.
 *
 * @since 2.2.9
 */
public final class LoginThrottle {

    private final Supplier<LoginThrottleConfig> config;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Attempt> attempts;

    /**
     * Production ctor: thresholds from the DB-backed admin setting
     * ({@code login_throttle_*}), re-read on every check.
     */
    public LoginThrottle() {
        this(LoginThrottleSettingsLoader.activeSupplier(), System::nanoTime);
    }

    /**
     * Fixed thresholds (tests).
     * @param maxFailures Failures before lockout
     * @param window Lockout window
     * @param clock Nano-time source
     */
    public LoginThrottle(final int maxFailures, final Duration window, final LongSupplier clock) {
        this(
            LoginThrottle.fixed(new LoginThrottleConfig(maxFailures, Math.toIntExact(window.toSeconds()))),
            clock
        );
    }

    /**
     * The single field-initializing ctor.
     * @param config Live thresholds, read on every decision
     * @param clock Nano-time source
     */
    public LoginThrottle(final Supplier<LoginThrottleConfig> config, final LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        this.attempts = new ConcurrentHashMap<>();
    }

    private static Supplier<LoginThrottleConfig> fixed(final LoginThrottleConfig config) {
        return () -> config;
    }

    /**
     * @param key Opaque throttle key ({@code username|clientIp})
     * @return {@code true} iff the key is currently locked out
     */
    public boolean isThrottled(final String key) {
        final Attempt att = this.attempts.get(key);
        if (att == null) {
            return false;
        }
        final LoginThrottleConfig current = this.config.get();
        if (this.clock.getAsLong() - att.firstNanos > current.window().toNanos()) {
            this.attempts.remove(key);
            return false;
        }
        return att.count.get() >= current.maxFailures();
    }

    /**
     * Record a failed attempt for the key.
     *
     * @param key Opaque throttle key
     */
    public void recordFailure(final String key) {
        final long now = this.clock.getAsLong();
        final long window = this.config.get().window().toNanos();
        this.attempts.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.firstNanos > window) {
                return new Attempt(now);
            }
            existing.count.incrementAndGet();
            return existing;
        });
    }

    /**
     * Clear the counter for the key (successful login).
     *
     * @param key Opaque throttle key
     */
    public void recordSuccess(final String key) {
        this.attempts.remove(key);
    }

    /**
     * One key's failure window.
     */
    private static final class Attempt {
        private final long firstNanos;
        private final AtomicInteger count;

        Attempt(final long firstNanos) {
            this.firstNanos = firstNanos;
            this.count = new AtomicInteger(1);
        }
    }
}
