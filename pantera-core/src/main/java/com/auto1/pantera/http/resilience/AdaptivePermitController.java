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

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.metrics.MicrometerMetrics;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * AIMD-tuned permit controller backing one {@link RepoBulkhead}.
 *
 * <p>Holds two integer counters: the current permit ceiling (volatile, AIMD-tuned)
 * and the in-flight count (CAS-incremented on acquire, decremented on release).
 * Outcomes observed per operation feed into per-window accumulators that the
 * scheduled tick reads, resets, and uses to derive the next permit value.
 *
 * <p>All public methods are safe to call from any thread. The window tick
 * runs on a process-wide daemon scheduler — one tick task per controller —
 * and is the sole writer to the permit ceiling outside the constructor.
 *
 * @since 2.2.0
 */
public final class AdaptivePermitController {

    /** Logger name. */
    private static final String LOGGER = "com.auto1.pantera.http.resilience";

    /**
     * Initialization-on-demand holder for the shared process-wide tick
     * scheduler. Loaded only when {@link #sharedScheduler()} is first
     * called, which means tests that pass their own scheduler never
     * spin up the shared one. JVM class-loading guarantees the
     * initialization is thread-safe without explicit synchronization.
     */
    private static final class SharedSchedulerHolder {
        static final ScheduledExecutorService INSTANCE = create();

        private static ScheduledExecutorService create() {
            final int threads = Math.max(
                1, Runtime.getRuntime().availableProcessors() / 4
            );
            return Executors.newScheduledThreadPool(threads, r -> {
                final Thread t = new Thread(r, "bulkhead-aimd");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private final String name;
    private final AdaptiveBulkheadLimits limits;
    private final AtomicInteger permits;
    private final AtomicInteger inFlight;
    private final LongAdder windowOk;
    private final LongAdder windowErr;
    private final AtomicLong windowLatencyMaxMillis;
    private final LongAdder rampUpCount;
    private final LongAdder rampDownCount;
    private final ScheduledFuture<?> tick;
    private volatile boolean closed;

    /**
     * Construct an AIMD controller and (when adaptive) schedule the window tick.
     *
     * @param name      Identifier used in logs and metrics (typically the repo name).
     * @param limits    Configuration record.
     * @param scheduler Scheduler used to run the periodic window tick. Pass
     *                  {@code null} to use the shared process-wide daemon
     *                  scheduler; tests should pass a dedicated scheduler
     *                  to avoid cross-test interference.
     */
    public AdaptivePermitController(
        final String name,
        final AdaptiveBulkheadLimits limits,
        final ScheduledExecutorService scheduler
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.permits = new AtomicInteger(limits.initialPermits());
        this.inFlight = new AtomicInteger(0);
        this.windowOk = new LongAdder();
        this.windowErr = new LongAdder();
        this.windowLatencyMaxMillis = new AtomicLong(0);
        this.rampUpCount = new LongAdder();
        this.rampDownCount = new LongAdder();
        if (limits.adaptive()) {
            final ScheduledExecutorService sched = scheduler != null ? scheduler : sharedScheduler();
            this.tick = sched.scheduleAtFixedRate(
                this::evaluateWindowSafe,
                limits.windowSeconds(),
                limits.windowSeconds(),
                TimeUnit.SECONDS
            );
        } else {
            this.tick = null;
        }
    }

    /** Convenience ctor using the shared scheduler. */
    public AdaptivePermitController(final String name, final AdaptiveBulkheadLimits limits) {
        this(name, limits, null);
    }

    /**
     * Non-blocking permit acquisition.
     *
     * @return {@code true} if a permit was reserved (caller must
     *         eventually call {@link #release()}); {@code false} if the
     *         controller is closed or all permits are in use.
     */
    public boolean tryAcquire() {
        if (this.closed) {
            return false;
        }
        while (true) {
            final int cap = this.permits.get();
            final int now = this.inFlight.get();
            if (now >= cap) {
                return false;
            }
            if (this.inFlight.compareAndSet(now, now + 1)) {
                return true;
            }
        }
    }

    /** Release one previously-acquired permit. */
    public void release() {
        this.inFlight.decrementAndGet();
    }

    /**
     * Record the outcome of one operation. The window tick uses these to
     * decide whether to AIMD-grow, shrink, or hold the permit ceiling.
     *
     * @param latencyMillis Wall-clock duration of the operation, in milliseconds.
     * @param ok            {@code true} on success; {@code false} on any error
     *                      (Result.err, exception, timeout, etc.).
     */
    public void observe(final long latencyMillis, final boolean ok) {
        if (ok) {
            this.windowOk.increment();
        } else {
            this.windowErr.increment();
        }
        if (latencyMillis > 0) {
            this.windowLatencyMaxMillis.accumulateAndGet(latencyMillis, Math::max);
        }
    }

    /** @return Current permit ceiling (AIMD-tuned). */
    public int currentPermits() {
        return this.permits.get();
    }

    /** @return Current in-flight operation count. */
    public int inFlightCount() {
        return this.inFlight.get();
    }

    /** @return Total ramp-up events since this controller was created. */
    public long rampUpEvents() {
        return this.rampUpCount.sum();
    }

    /** @return Total ramp-down events since this controller was created. */
    public long rampDownEvents() {
        return this.rampDownCount.sum();
    }

    /** @return Controller identifier (typically the repo name). */
    public String name() {
        return this.name;
    }

    /** @return Configuration this controller was constructed with. */
    public AdaptiveBulkheadLimits limits() {
        return this.limits;
    }

    /**
     * Cancel the window tick and prevent further acquisitions. Idempotent.
     * Existing in-flight operations are unaffected — they still call
     * {@link #release()} normally.
     */
    public void close() {
        this.closed = true;
        if (this.tick != null) {
            this.tick.cancel(false);
        }
    }

    /**
     * Run the AIMD step over the previous window. Package-private so unit
     * tests can drive it deterministically without waiting on the scheduler.
     *
     * <p>The math:
     * <ul>
     *   <li>any error → {@code newPermits = floor(old * rampDownFactor)}, clamped at {@code minPermits}</li>
     *   <li>max latency in window {@code > 2 × targetP99Millis} → softer decrease using {@code sqrt(rampDownFactor)}</li>
     *   <li>max latency {@code &le; targetP99Millis} and no errors → {@code newPermits = old + rampUpStep}, clamped at {@code maxPermits}</li>
     *   <li>otherwise hold steady</li>
     * </ul>
     */
    void evaluateWindow() {
        final long ok = this.windowOk.sumThenReset();
        final long err = this.windowErr.sumThenReset();
        final long maxLat = this.windowLatencyMaxMillis.getAndSet(0L);
        if (ok + err == 0L) {
            return;
        }
        final int oldPermits = this.permits.get();
        final int newPermits = computeNextPermits(oldPermits, err, maxLat);
        if (newPermits == oldPermits) {
            return;
        }
        this.permits.set(newPermits);
        final String direction;
        if (newPermits > oldPermits) {
            this.rampUpCount.increment();
            direction = "up";
        } else {
            this.rampDownCount.increment();
            direction = "down";
        }
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordBulkheadRampEvent(this.name, direction);
        }
        // Per-window numeric details (permits, window counts, max latency)
        // are already exposed as Prometheus metrics
        // (pantera_bulkhead_permits_current, pantera_bulkhead_ramp_events_total)
        // and embedded in the message text. Non-ECS fields like
        // "bulkhead.repo" would 400 in Elastic and drop the line, so this
        // log carries only the canonical ECS shape.
        EcsLogger.info(LOGGER)
            .message("Bulkhead AIMD " + direction + " " + this.name
                + ": " + oldPermits + " -> " + newPermits
                + " (ok=" + ok + " err=" + err + " maxLatMs=" + maxLat + ")")
            .eventCategory("process")
            .eventAction("bulkhead_aimd")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /** Pure function: compute the next permit value from the window outcome. */
    int computeNextPermits(final int oldPermits, final long err, final long maxLat) {
        if (err > 0L) {
            return Math.max(
                this.limits.minPermits(),
                (int) Math.floor(oldPermits * this.limits.rampDownFactor())
            );
        }
        final long badLatencyThreshold = saturatingDouble(this.limits.targetP99Millis());
        if (maxLat > badLatencyThreshold) {
            return Math.max(
                this.limits.minPermits(),
                (int) Math.floor(oldPermits * Math.sqrt(this.limits.rampDownFactor()))
            );
        }
        if (maxLat <= this.limits.targetP99Millis()) {
            return Math.min(
                this.limits.maxPermits(),
                oldPermits + this.limits.rampUpStep()
            );
        }
        return oldPermits;
    }

    private static long saturatingDouble(final long value) {
        if (value > Long.MAX_VALUE / 2L) {
            return Long.MAX_VALUE;
        }
        return value * 2L;
    }

    private void evaluateWindowSafe() {
        try {
            evaluateWindow();
        } catch (final RuntimeException ex) {
            EcsLogger.warn(LOGGER)
                .message("AIMD window evaluation failed for " + this.name)
                .eventCategory("process")
                .eventAction("bulkhead_aimd")
                .eventOutcome("failure")
                .field("log.source", "application")
                .error(ex)
                .log();
        }
    }

    private static ScheduledExecutorService sharedScheduler() {
        return SharedSchedulerHolder.INSTANCE;
    }
}
