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

import com.auto1.pantera.http.log.EcsLogger;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Thread-safe registry tracking circuit-breaker state per remote endpoint.
 *
 * <p>Trip policy: rate-over-sliding-window. Each remote has a ring buffer of
 * per-second buckets covering {@link AutoBlockSettings#slidingWindowSeconds()}
 * seconds. Every recorded outcome (success or failure) writes into the bucket
 * covering {@code now}. The breaker opens when, across the full window, the
 * failure rate meets or exceeds {@link AutoBlockSettings#failureRateThreshold()}
 * AND the total outcome count meets or exceeds
 * {@link AutoBlockSettings#minimumNumberOfCalls()}. The minimum-volume gate
 * is what protects cold starts and low-traffic endpoints from tripping on
 * single-digit transient failure bursts — a problem the pre-2.2.0
 * consecutive-count design had.</p>
 *
 * <p>After a trip the remote transitions BLOCKED → PROBING once the block
 * window expires; the next outcome determines whether the circuit closes
 * ({@link #recordSuccess} in PROBING) or re-opens with a longer block
 * via Fibonacci back-off ({@link #recordFailure} in PROBING).</p>
 *
 * <p>Settings are resolved lazily via a {@link Supplier} on every outcome.
 * Production code wires this to the DB-backed loader so runtime updates
 * via the admin settings UI take effect without a process restart. Tests
 * typically pass a constant supplier.</p>
 *
 * @since 1.20.13 — rewritten in 2.2.0 for rate-based tripping
 */
public final class AutoBlockRegistry {

    /**
     * Fibonacci multiplier sequence used for back-off on repeated trips.
     * Unchanged from the pre-2.2.0 design.
     */
    private static final long[] FIBONACCI = {1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89};

    private final Supplier<AutoBlockSettings> settingsSupplier;
    private final ConcurrentMap<String, WindowState> states;

    /**
     * Construct with a settings supplier (recommended for production —
     * lets settings updates flow through without rebuilding the registry).
     * @param settingsSupplier Returns the current {@link AutoBlockSettings};
     *                         called on every record / isBlocked invocation
     */
    public AutoBlockRegistry(final Supplier<AutoBlockSettings> settingsSupplier) {
        this.settingsSupplier = settingsSupplier;
        this.states = new ConcurrentHashMap<>();
    }

    /**
     * Backward-compatible constructor pinning a constant settings value.
     * Use the {@link Supplier} form for production.
     * @param settings Immutable settings; treated as a constant supplier
     */
    public AutoBlockRegistry(final AutoBlockSettings settings) {
        this(() -> settings);
    }

    /**
     * Check whether the remote is currently in fast-fail (BLOCKED) state.
     * Transitions BLOCKED → PROBING when the block window has expired.
     * Never calls the settings supplier (read-only lookup).
     */
    public boolean isBlocked(final String remoteId) {
        final WindowState state = this.states.get(remoteId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.status == Status.BLOCKED) {
                if (System.currentTimeMillis() >= state.blockedUntilMs) {
                    state.status = Status.PROBING;
                    EcsLogger.info("com.auto1.pantera.http.timeout")
                        .message("Circuit breaker transition BLOCKED → PROBING — block expired"
                            + " (remote=" + remoteId + ")")
                        .eventCategory("web")
                        .eventAction("circuit_breaker_probing")
                        .eventOutcome("success")
                        .log();
                    return false;
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Current status for diagnostics / admin API.
     * @return one of {@code "online"}, {@code "blocked"}, {@code "probing"}
     */
    public String status(final String remoteId) {
        final WindowState state = this.states.get(remoteId);
        if (state == null) {
            return "online";
        }
        synchronized (state) {
            if (state.status == Status.BLOCKED
                && System.currentTimeMillis() >= state.blockedUntilMs) {
                return "probing";
            }
            return state.status.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Record a failure outcome. May trip the circuit if the configured
     * rate and minimum-volume thresholds are now met within the window.
     */
    public void recordFailure(final String remoteId) {
        this.recordOutcome(remoteId, false);
    }

    /**
     * Record a success outcome. In PROBING state, closes the circuit and
     * clears the window (upstream is back). In ONLINE state, updates the
     * current bucket; no trip evaluation needed since successes can only
     * decrease the failure rate.
     */
    public void recordSuccess(final String remoteId) {
        this.recordOutcome(remoteId, true);
    }

    private void recordOutcome(final String remoteId, final boolean succeeded) {
        final AutoBlockSettings settings = this.settingsSupplier.get();
        final WindowState state = this.states.computeIfAbsent(
            remoteId, id -> new WindowState(settings.slidingWindowSeconds())
        );
        // In the rare case settings change and the new window size is
        // different, reallocate. Live swap — callers already accept
        // brief state loss on a settings change.
        synchronized (state) {
            state.ensureCapacity(settings.slidingWindowSeconds());
            state.rotateTo(System.currentTimeMillis(), settings.slidingWindowSeconds());
            if (succeeded) {
                state.successes[state.currentBucket]++;
                if (state.status == Status.PROBING) {
                    this.closeCircuitLocked(remoteId, state);
                    return;
                }
                // A successful outcome still advances total volume.
                // If earlier failures had already pushed the rate above
                // the threshold but volume was under the min-calls gate,
                // this success can be the one that crosses the gate while
                // the rate is still above threshold. Evaluate here too.
                if (state.status == Status.ONLINE) {
                    this.maybeTripLocked(remoteId, state, settings);
                }
            } else {
                state.failures[state.currentBucket]++;
                if (state.status == Status.PROBING) {
                    this.tripLocked(remoteId, state, settings, true);
                    return;
                }
                if (state.status == Status.ONLINE) {
                    this.maybeTripLocked(remoteId, state, settings);
                }
            }
        }
    }

    /** Sum failure / total across the full window; trip if thresholds met. */
    private void maybeTripLocked(
        final String remoteId, final WindowState state, final AutoBlockSettings settings
    ) {
        int failures = 0;
        int total = 0;
        for (int i = 0; i < state.successes.length; i++) {
            failures += state.failures[i];
            total += state.failures[i] + state.successes[i];
        }
        if (total < settings.minimumNumberOfCalls()) {
            return;
        }
        final double rate = total == 0 ? 0.0 : (double) failures / (double) total;
        if (rate >= settings.failureRateThreshold()) {
            this.tripLocked(remoteId, state, settings, false);
            // Also record the volume + rate at trip time for ops forensics.
            EcsLogger.warn("com.auto1.pantera.http.timeout")
                .message("Circuit breaker OPENED — failure rate "
                    + String.format(Locale.ROOT, "%.2f", rate * 100)
                    + "% over " + total + " requests in "
                    + settings.slidingWindowSeconds() + "s window"
                    + " (remote=" + remoteId
                    + ", failure_count=" + failures
                    + ", threshold=" + settings.failureRateThreshold()
                    + ", blocked_until=" + Instant.ofEpochMilli(state.blockedUntilMs) + ")")
                .eventCategory("web")
                .eventAction("circuit_breaker_opened")
                .eventOutcome("failure")
                .field("event.reason", "failure_rate_threshold_reached")
                .log();
        }
    }

    /**
     * Transition to BLOCKED. Computes the next block window via Fibonacci
     * back-off, capped at {@link AutoBlockSettings#maxBlockDuration()}.
     * Called from both the rate-based trip path (from ONLINE) and the
     * probe-failed path (from PROBING).
     */
    private void tripLocked(
        final String remoteId, final WindowState state,
        final AutoBlockSettings settings, final boolean probeFailure
    ) {
        final int fibIdx = state.status == Status.ONLINE
            ? 0
            : Math.min(state.fibonacciIndex + 1, FIBONACCI.length - 1);
        final long blockMs = Math.min(
            settings.initialBlockDuration().toMillis() * FIBONACCI[fibIdx],
            settings.maxBlockDuration().toMillis()
        );
        final long blockedUntilMs = System.currentTimeMillis() + blockMs;
        state.fibonacciIndex = fibIdx;
        state.status = Status.BLOCKED;
        state.blockedUntilMs = blockedUntilMs;
        if (probeFailure) {
            EcsLogger.warn("com.auto1.pantera.http.timeout")
                .message("Circuit breaker re-OPENED — probe failed"
                    + " (remote=" + remoteId
                    + ", fibonacci_index=" + fibIdx
                    + ", block_duration_ms=" + blockMs
                    + ", blocked_until=" + Instant.ofEpochMilli(blockedUntilMs) + ")")
                .eventCategory("web")
                .eventAction("circuit_breaker_probe_failed")
                .eventOutcome("failure")
                .field("event.reason", "probe_failure")
                .log();
        }
    }

    /** Reset to ONLINE + clear counters. Called on PROBING → success. */
    private void closeCircuitLocked(final String remoteId, final WindowState state) {
        state.status = Status.ONLINE;
        state.fibonacciIndex = 0;
        state.blockedUntilMs = 0L;
        for (int i = 0; i < state.successes.length; i++) {
            state.successes[i] = 0;
            state.failures[i] = 0;
        }
        state.currentBucketStartMs = System.currentTimeMillis();
        state.currentBucket = 0;
        EcsLogger.info("com.auto1.pantera.http.timeout")
            .message("Circuit breaker CLOSED — probe succeeded, upstream recovered"
                + " (remote=" + remoteId + ")")
            .eventCategory("web")
            .eventAction("circuit_breaker_closed")
            .eventOutcome("success")
            .log();
    }

    /** Per-remote mutable state. Guarded by {@code synchronized(this)}. */
    private static final class WindowState {
        int[] successes;
        int[] failures;
        int currentBucket;
        long currentBucketStartMs;
        int fibonacciIndex;
        long blockedUntilMs;
        Status status;

        WindowState(final int windowSeconds) {
            this.successes = new int[windowSeconds];
            this.failures = new int[windowSeconds];
            this.currentBucketStartMs = System.currentTimeMillis();
            this.status = Status.ONLINE;
        }

        /**
         * Resize the ring buffer if the configured window length changed
         * at runtime. Discards existing counters — acceptable cost given
         * settings changes are rare and the window is < 1 minute typically.
         */
        void ensureCapacity(final int windowSeconds) {
            if (this.successes.length != windowSeconds) {
                this.successes = new int[windowSeconds];
                this.failures = new int[windowSeconds];
                this.currentBucket = 0;
                this.currentBucketStartMs = System.currentTimeMillis();
            }
        }

        /**
         * Advance {@link #currentBucket} so it covers {@code nowMs}. Any
         * buckets skipped over (due to elapsed idle time) are zeroed as
         * they become the new current bucket. Under steady traffic this
         * advances by 0 or 1 bucket per call.
         */
        void rotateTo(final long nowMs, final int windowSeconds) {
            final long elapsedMs = nowMs - this.currentBucketStartMs;
            if (elapsedMs < 1000L) {
                return;
            }
            final int secondsElapsed = (int) Math.min(
                elapsedMs / 1000L, (long) windowSeconds
            );
            for (int i = 0; i < secondsElapsed; i++) {
                this.currentBucket = (this.currentBucket + 1) % windowSeconds;
                this.successes[this.currentBucket] = 0;
                this.failures[this.currentBucket] = 0;
            }
            this.currentBucketStartMs += (long) secondsElapsed * 1000L;
        }
    }

    /** Circuit-breaker lifecycle states. Package-private for tests. */
    enum Status { ONLINE, BLOCKED, PROBING }
}
