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
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Permit controller backing one {@link RepoBulkhead}.
 *
 * <p>Holds the current permit ceiling and the in-flight count
 * (CAS-incremented on acquire, decremented on release). A request that finds
 * every permit taken waits for one ({@link #acquire(Duration)}); it is not
 * refused. Outcomes observed per operation feed into per-window accumulators
 * that the scheduled tick reads, resets, and uses to raise the ceiling from
 * its initial value towards the maximum. The ceiling never falls.
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
    /** Requests waiting for a permit, oldest first. */
    private final Queue<CompletableFuture<Boolean>> waiters;
    /** Number of requests in {@link #waiters} that have not given up. */
    private final AtomicInteger waiting;
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
        this.waiters = new ConcurrentLinkedQueue<>();
        this.waiting = new AtomicInteger(0);
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

    /**
     * Reserve a permit, waiting for one when all are in use.
     *
     * <p>A request that finds every permit taken is not refused: it joins a
     * queue and gets the next permit that is released, oldest request first.
     * It gives up only when {@code wait} has passed, or at once when
     * {@link AdaptiveBulkheadLimits#maxQueueDepth()} requests are already
     * waiting. A burst of requests is thereby spread over the permits
     * instead of being failed.</p>
     *
     * <p>A closed controller admits every request: it belongs to a bulkhead
     * that was replaced, and the requests still holding it must not fail.</p>
     *
     * @param wait Longest time to wait for a permit
     * @return Completes with {@code true} once a permit is reserved (the
     *  caller must call {@link #release()}), with {@code false} when none
     *  came within {@code wait} or the queue was full. Already complete when
     *  a permit was free.
     */
    public CompletableFuture<Boolean> acquire(final Duration wait) {
        final CompletableFuture<Boolean> permit;
        if (this.waiters.isEmpty() && this.reserve()) {
            permit = CompletableFuture.completedFuture(Boolean.TRUE);
        } else if (this.waiting.incrementAndGet() > this.limits.maxQueueDepth()) {
            this.waiting.decrementAndGet();
            permit = CompletableFuture.completedFuture(Boolean.FALSE);
        } else {
            permit = new CompletableFuture<>();
            permit.whenComplete(
                (granted, err) -> {
                    this.waiting.decrementAndGet();
                    if (!Boolean.TRUE.equals(granted)) {
                        this.waiters.remove(permit);
                    }
                }
            );
            this.waiters.add(permit);
            permit.completeOnTimeout(Boolean.FALSE, wait.toMillis(), TimeUnit.MILLISECONDS);
            // A permit may have been released between the failed reservation
            // and the request joining the queue.
            this.handOver();
        }
        return permit;
    }

    /** @return Requests waiting for a permit. */
    public int waitingCount() {
        return this.waiting.get();
    }

    /**
     * Release one previously-acquired permit. It goes to the request that
     * has waited longest, if any.
     */
    public void release() {
        this.inFlight.decrementAndGet();
        this.handOver();
    }

    /**
     * Give free permits to waiting requests, oldest first. Called whenever a
     * permit may have become free: on a release, when the ceiling grows,
     * when a request joins the queue, and on close.
     */
    private void handOver() {
        while (!this.waiters.isEmpty() && this.reserve()) {
            final CompletableFuture<Boolean> next = this.waiters.poll();
            if (next == null || !next.complete(Boolean.TRUE)) {
                // Nobody took the permit: the queue emptied meanwhile, or
                // the request had already given up. Put it back and look
                // again, a request may have joined in between.
                this.inFlight.decrementAndGet();
            }
        }
    }

    /**
     * Count one more request in flight if the ceiling allows it. A closed
     * controller allows every request.
     * @return Whether the request was counted
     */
    private boolean reserve() {
        boolean reserved = false;
        boolean settled = false;
        while (!settled) {
            final int now = this.inFlight.get();
            if (!this.closed && now >= this.permits.get()) {
                settled = true;
            } else if (this.inFlight.compareAndSet(now, now + 1)) {
                reserved = true;
                settled = true;
            }
        }
        return reserved;
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
     * Cancel the window tick and stop limiting. Idempotent. Existing
     * in-flight operations are unaffected — they still call
     * {@link #release()} normally. Requests waiting for a permit are let
     * through, and so is every later {@link #acquire(Duration)}: a closed
     * controller belongs to a bulkhead that was replaced. {@link #tryAcquire()}
     * answers {@code false} from then on.
     */
    public void close() {
        this.closed = true;
        if (this.tick != null) {
            this.tick.cancel(false);
        }
        this.handOver();
    }

    /**
     * Run the AIMD step over the previous window. Package-private so unit
     * tests can drive it deterministically without waiting on the scheduler.
     *
     * <p>A window with at least one request and no failed one raises the
     * ceiling by {@code rampUpStep}, up to {@code maxPermits}. Nothing lowers
     * it: see {@link #computeNextPermits(int, long)}.
     */
    void evaluateWindow() {
        final long ok = this.windowOk.sumThenReset();
        final long err = this.windowErr.sumThenReset();
        final long maxLat = this.windowLatencyMaxMillis.getAndSet(0L);
        if (ok + err == 0L) {
            return;
        }
        final int oldPermits = this.permits.get();
        final int newPermits = computeNextPermits(oldPermits, err);
        if (newPermits == oldPermits) {
            return;
        }
        this.permits.set(newPermits);
        this.handOver();
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

    /**
     * Pure function: the permit ceiling after a window.
     *
     * <p>The ceiling only ever grows. It used to be cut by any failed request
     * and by any request slower than twice the latency target. Neither says
     * that the repository carries too many requests: the cold fetch of a
     * large artifact takes seconds on a healthy upstream. Under a burst of
     * such fetches the ceiling fell to the minimum within a minute and the
     * requests beyond it were refused.</p>
     *
     * @param oldPermits Current ceiling
     * @param err Failed requests in the window
     * @return The ceiling raised by one step after a window without failed
     *  requests, unchanged otherwise
     */
    int computeNextPermits(final int oldPermits, final long err) {
        final int next;
        if (err > 0L) {
            next = oldPermits;
        } else {
            next = Math.min(
                this.limits.maxPermits(),
                oldPermits + this.limits.rampUpStep()
            );
        }
        return next;
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
