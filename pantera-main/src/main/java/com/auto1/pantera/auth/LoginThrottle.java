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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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

    private final int maxFailures;
    private final long windowNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Attempt> attempts;

    /**
     * Ctor with the system monotonic clock and default policy
     * (5 failures / 15 minutes).
     */
    public LoginThrottle() {
        this(5, Duration.ofMinutes(15), System::nanoTime);
    }

    /**
     * Ctor.
     *
     * @param maxFailures Failures before lockout
     * @param window Lockout / counting window
     * @param clock Monotonic nano clock (injectable for tests)
     */
    public LoginThrottle(final int maxFailures, final Duration window, final LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.windowNanos = window.toNanos();
        this.clock = clock;
        this.attempts = new ConcurrentHashMap<>();
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
        if (this.clock.getAsLong() - att.firstNanos > this.windowNanos) {
            this.attempts.remove(key);
            return false;
        }
        return att.count.get() >= this.maxFailures;
    }

    /**
     * Record a failed attempt for the key.
     *
     * @param key Opaque throttle key
     */
    public void recordFailure(final String key) {
        final long now = this.clock.getAsLong();
        this.attempts.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.firstNanos > this.windowNanos) {
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
