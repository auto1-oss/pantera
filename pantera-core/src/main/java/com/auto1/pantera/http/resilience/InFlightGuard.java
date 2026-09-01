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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Deduplication guard for fire-and-forget background operations, with a
 * staleness override so a hung operation cannot pin its key forever.
 *
 * <p>The npm and pypi proxies deduplicate stale-while-revalidate refreshes
 * with a bare {@code ConcurrentHashMap.KeySetView}: the key is added before
 * the refresh starts and removed in the terminal callback. If the refresh
 * future never reaches a terminal event (a wedged upstream connection, a
 * dropped subscription), the key stays in the set until process restart and
 * every subsequent refresh for that package is silently skipped — the
 * artifact is then served stale forever with no log trace. This class keeps
 * the same single-flight semantics but stamps each key with an acquisition
 * time: an entry older than {@code maxAge} is treated as abandoned and taken
 * over by the next caller.</p>
 *
 * <p>If the abandoned operation later completes after a takeover, its
 * {@link #end(String)} releases the new owner's entry early; the only cost
 * is one extra (deduplicated) refresh on the next request — never a lost
 * update, never a permanent block.</p>
 *
 * @since 2.2.7
 */
public final class InFlightGuard {

    /**
     * In-flight keys mapped to their acquisition timestamp (nanos from
     * {@link #clock}).
     */
    private final ConcurrentHashMap<String, Long> inflight;

    /**
     * Age past which an in-flight entry is considered abandoned, in nanos.
     */
    private final long maxAgeNanos;

    /**
     * Monotonic clock; injectable so tests can time-travel without sleeps.
     */
    private final LongSupplier clock;

    /**
     * Ctor with the system monotonic clock.
     *
     * @param maxAge Age past which an in-flight entry is considered abandoned
     */
    public InFlightGuard(final Duration maxAge) {
        this(maxAge, System::nanoTime);
    }

    /**
     * Ctor with an injectable clock (tests).
     *
     * @param maxAge Age past which an in-flight entry is considered abandoned
     * @param clock Monotonic nano clock
     */
    public InFlightGuard(final Duration maxAge, final LongSupplier clock) {
        this.inflight = new ConcurrentHashMap<>();
        this.maxAgeNanos = maxAge.toNanos();
        this.clock = clock;
    }

    /**
     * Try to acquire the key for a new background operation.
     *
     * @param key Operation key (e.g. package name)
     * @return {@code true} when the caller may proceed — the key was free,
     *  or its previous holder exceeded {@code maxAge} and was taken over;
     *  {@code false} when a fresh operation is already in flight
     */
    public boolean tryBegin(final String key) {
        final long now = this.clock.getAsLong();
        final Long existing = this.inflight.putIfAbsent(key, now);
        // Free key: acquired. Held key: proceed only when the entry is
        // abandoned (older than maxAge — the previous operation never
        // reached its terminal event), CAS-ing the timestamp so exactly
        // one caller wins the takeover.
        return existing == null
            || now - existing > this.maxAgeNanos
            && this.inflight.replace(key, existing, now);
    }

    /**
     * Release the key after the operation reached a terminal event.
     *
     * @param key Operation key passed to {@link #tryBegin(String)}
     */
    public void end(final String key) {
        this.inflight.remove(key);
    }

    /**
     * Number of keys currently held. Exposed for tests and diagnostics.
     *
     * @return In-flight key count
     */
    public int size() {
        return this.inflight.size();
    }
}
