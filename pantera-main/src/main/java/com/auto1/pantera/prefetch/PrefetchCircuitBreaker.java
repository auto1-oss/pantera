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
package com.auto1.pantera.prefetch;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.runtime.CircuitBreakerTuning;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Auto-disable for the prefetch coordinator.
 *
 * <p>Maintains a trailing window of drop timestamps. When the count of drops
 * within {@link CircuitBreakerTuning#windowSeconds()} seconds strictly exceeds
 * {@code threshold * window} (i.e. the average drop rate exceeded
 * {@link CircuitBreakerTuning#dropThresholdPerSec()} for the whole window),
 * the breaker trips: {@link #isOpen()} returns {@code true} until
 * {@link CircuitBreakerTuning#disableMinutes()} have elapsed since the trip,
 * after which it automatically closes again.</p>
 *
 * <p><b>Trip math (deviation from plan code):</b> the spec text reads
 * "100 drops/sec for 30s → disable". The plan's expression
 * {@code recentDrops.size() / windowSeconds > dropThresholdPerSec} relies on
 * integer division and therefore needs (threshold + 1) * window drops to fire.
 * We instead use {@code recentDrops.size() > dropThresholdPerSec * windowSeconds},
 * which fires on exactly one drop above the average rate — the cleaner intent.</p>
 *
 * <p>Tuning is read through a {@link Supplier} so live updates from
 * {@code RuntimeSettingsCache} take effect on the next {@link #recordDrop()} or
 * {@link #isOpen()} call without rebuilding this instance.</p>
 *
 * <p>Thread-safe: drop timestamps live in a {@link ConcurrentLinkedDeque};
 * the trip-state field is an {@link AtomicReference} swapped via CAS.</p>
 *
 * @since 2.2.0
 */
public final class PrefetchCircuitBreaker {

    /**
     * Sentinel for "currently closed".
     */
    private static final Instant CLOSED = Instant.MIN;

    private final Supplier<CircuitBreakerTuning> tuning;
    private final Clock clock;

    /**
     * Drop timestamps within the trailing window. Always appended in
     * monotonic order (per clock); pruning trims the head.
     */
    private final ConcurrentLinkedDeque<Instant> recentDrops = new ConcurrentLinkedDeque<>();

    /**
     * Trip instant, or {@link #CLOSED} when closed.
     */
    private final AtomicReference<Instant> trippedAt = new AtomicReference<>(CLOSED);

    /**
     * Production constructor — uses the system UTC clock.
     *
     * @param tuning Tunable supplier (read on every check; honors live updates).
     */
    public PrefetchCircuitBreaker(final Supplier<CircuitBreakerTuning> tuning) {
        this(tuning, Clock.systemUTC());
    }

    /**
     * Test seam — explicit clock.
     *
     * @param tuning Tunable supplier (read on every check; honors live updates).
     * @param clock Clock to read "now" from.
     */
    public PrefetchCircuitBreaker(final Supplier<CircuitBreakerTuning> tuning, final Clock clock) {
        this.tuning = tuning;
        this.clock = clock;
    }

    /**
     * Record one drop event. Trips the breaker if the trailing-window count
     * crosses the threshold.
     */
    public void recordDrop() {
        final CircuitBreakerTuning snap = this.tuning.get();
        final Instant now = this.clock.instant();
        this.recentDrops.add(now);
        prune(now, snap);
        // Trip math: strictly greater than threshold * window.
        if (this.recentDrops.size() > (long) snap.dropThresholdPerSec() * snap.windowSeconds()
            && this.trippedAt.compareAndSet(CLOSED, now)) {
            EcsLogger.warn("com.auto1.pantera.prefetch.PrefetchCircuitBreaker")
                .message("Prefetch circuit breaker tripped — auto-disabling")
                .field("drops_in_window", this.recentDrops.size())
                .field("threshold_per_sec", snap.dropThresholdPerSec())
                .field("window_seconds", snap.windowSeconds())
                .field("disable_minutes", snap.disableMinutes())
                .eventCategory("process")
                .eventAction("prefetch_breaker_open")
                .eventOutcome("failure")
                .log();
        }
    }

    /**
     * @return {@code true} when the breaker is currently open (prefetch disabled).
     *     Auto-closes after {@code disableMinutes} have elapsed since the trip.
     */
    public boolean isOpen() {
        final Instant tripped = this.trippedAt.get();
        if (tripped == CLOSED) {
            return false;
        }
        final CircuitBreakerTuning snap = this.tuning.get();
        final Instant now = this.clock.instant();
        if (now.isAfter(tripped.plus(Duration.ofMinutes(snap.disableMinutes())))) {
            // Auto-close. CAS so a concurrent caller doesn't see a flipped state mid-check.
            if (this.trippedAt.compareAndSet(tripped, CLOSED)) {
                this.recentDrops.clear();
                EcsLogger.info("com.auto1.pantera.prefetch.PrefetchCircuitBreaker")
                    .message("Prefetch circuit breaker auto-closed — re-enabling")
                    .eventCategory("process")
                    .eventAction("prefetch_breaker_close")
                    .eventOutcome("success")
                    .log();
            }
            return false;
        }
        return true;
    }

    /**
     * Drop count currently in the trailing window (for stats / tests).
     *
     * @return Number of drop timestamps within the last {@code windowSeconds}.
     */
    public int dropCountInWindow() {
        prune(this.clock.instant(), this.tuning.get());
        return this.recentDrops.size();
    }

    private void prune(final Instant now, final CircuitBreakerTuning snap) {
        final Instant cutoff = now.minusSeconds(snap.windowSeconds());
        Instant head = this.recentDrops.peekFirst();
        while (head != null && head.isBefore(cutoff)) {
            // pollFirst returns null if another thread already pulled it — that's fine.
            this.recentDrops.pollFirst();
            head = this.recentDrops.peekFirst();
        }
    }
}
