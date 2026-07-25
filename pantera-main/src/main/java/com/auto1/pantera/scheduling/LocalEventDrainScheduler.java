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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.trace.SpanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.MDC;

/**
 * Per-node periodic drain of a node-local {@link Queue} into one or more
 * {@link Consumer}s, on a dedicated {@link ScheduledExecutorService} — mirrors
 * {@code RuntimeSettingsCache}'s own timer.
 * <p>
 * Node-local in-memory work — like draining this JVM's artifact-events queue
 * into its {@code DbConsumer} batchers — must never be scheduled through
 * Quartz's cluster-shared JDBC job store: clustered Quartz does not pin
 * repeating triggers to the node that registered them, so another node
 * acquiring the trigger finds nothing in its own {@link JobDataRegistry} and,
 * before 2.3.0, self-destructed the shared job entry — permanently orphaning
 * the owning node's queue (lost {@code artifact_publish} audit records and
 * search-index rows; see the WS2.2 fix). This class replaces
 * {@code QuartzService.addPeriodicEventsProcessor}/{@link EventsProcessor}
 * for that specific pipeline. A dedicated per-node executor guarantees the
 * drain always runs on, and only on, the node that owns the queue —
 * regardless of RAM or JDBC-clustered Quartz mode.
 *
 * @param <T> Queue element type
 * @since 2.3.0
 */
public final class LocalEventDrainScheduler<T> implements AutoCloseable {

    /**
     * Retry attempts per item on {@link EventProcessingError}, matching the
     * pre-2.3.0 {@code EventsProcessor} behaviour.
     */
    private static final int MAX_RETRY = 3;

    /**
     * Await-termination budget for {@link #close()}.
     */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    /**
     * Distinguishes thread names across multiple schedulers in one JVM
     * (production runs one; tests may construct several).
     */
    private static final AtomicInteger POOL_COUNTER = new AtomicInteger();

    /**
     * Dedicated per-node executor — never shared with Quartz.
     */
    private final ScheduledExecutorService executor;

    /**
     * Handles for the scheduled repeating drain tasks, one per consumer.
     */
    private final List<ScheduledFuture<?>> tasks;

    /**
     * Ctor. Starts draining immediately.
     * @param queue Node-local queue to drain
     * @param consumers One drain worker per consumer, all polling {@code queue}
     * @param intervalSeconds Fixed-rate drain interval, in seconds
     */
    public LocalEventDrainScheduler(
        final Queue<T> queue, final List<Consumer<T>> consumers, final int intervalSeconds
    ) {
        final int poolId = LocalEventDrainScheduler.POOL_COUNTER.incrementAndGet();
        this.executor = Executors.newScheduledThreadPool(
            Math.max(1, consumers.size()), LocalEventDrainScheduler.threadFactory(poolId)
        );
        this.tasks = new ArrayList<>(consumers.size());
        for (final Consumer<T> consumer : consumers) {
            this.tasks.add(
                this.executor.scheduleAtFixedRate(
                    () -> LocalEventDrainScheduler.drain(queue, consumer),
                    intervalSeconds, intervalSeconds, TimeUnit.SECONDS
                )
            );
        }
        EcsLogger.info("com.auto1.pantera.scheduling")
            .message("Local event drain scheduler started (workers=" + consumers.size()
                + ", interval_seconds=" + intervalSeconds + ")")
            .eventCategory("process")
            .eventAction("event_drain_start")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    @Override
    public void close() {
        this.tasks.forEach(task -> task.cancel(false));
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (final InterruptedException ie) {
            // EXPECTED: shutdown signalled mid-await — restore interrupt and
            // force-stop; a slow drain tick must not block process shutdown.
            Thread.currentThread().interrupt();
            this.executor.shutdownNow();
        }
        EcsLogger.info("com.auto1.pantera.scheduling")
            .message("Local event drain scheduler stopped")
            .eventCategory("process")
            .eventAction("event_drain_stop")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /**
     * Drain everything currently in {@code queue} through {@code action},
     * retrying each item up to {@link #MAX_RETRY} times on
     * {@link EventProcessingError} before dropping it (logged). Never lets an
     * exception escape: {@link ScheduledExecutorService#scheduleAtFixedRate}
     * permanently cancels future executions of a task whose Runnable throws.
     * @param queue Queue to drain
     * @param action Consumer to forward each item to
     * @param <T> Element type
     */
    private static <T> void drain(final Queue<T> queue, final Consumer<T> action) {
        MDC.put(EcsMdc.TRACE_ID, SpanContext.generateHex16());
        MDC.put(EcsMdc.SPAN_ID, SpanContext.generateHex16());
        try {
            int processed = 0;
            T item = queue.poll();
            while (item != null) {
                if (LocalEventDrainScheduler.processWithRetry(action, item)) {
                    processed += 1;
                }
                item = queue.poll();
            }
            if (processed > 0) {
                EcsLogger.debug("com.auto1.pantera.scheduling")
                    .message("Processed " + processed + " elements from queue")
                    .eventCategory("process")
                    .eventAction("event_process")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
        } catch (final RuntimeException ex) {
            // EXPECTED guard: a Runnable that escapes with an exception
            // silently cancels all future fixed-rate executions on this
            // task — never let one bad tick permanently stop the drain.
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Unexpected error draining event queue; drain continues on next tick")
                .eventCategory("process")
                .eventAction("event_process")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        } finally {
            MDC.remove(EcsMdc.TRACE_ID);
            MDC.remove(EcsMdc.SPAN_ID);
        }
    }

    /**
     * Process a single item with up to {@link #MAX_RETRY} attempts.
     * @param action Consumer to invoke
     * @param item Element to process
     * @param <T> Element type
     * @return True if the item was processed successfully
     */
    private static <T> boolean processWithRetry(final Consumer<T> action, final T item) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                action.accept(item);
                return true;
            } catch (final EventProcessingError ex) {
                EcsLogger.error("com.auto1.pantera.scheduling")
                    .message("Event processing failed (attempt " + (attempt + 1)
                        + "/" + MAX_RETRY + ")")
                    .eventCategory("process")
                    .eventAction("event_process")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
        EcsLogger.error("com.auto1.pantera.scheduling")
            .message("Dropping event after " + MAX_RETRY + " failed attempts")
            .eventCategory("process")
            .eventAction("event_drop")
            .eventOutcome("failure")
            .field("log.source", "application")
            .log();
        return false;
    }

    /**
     * Named daemon thread factory so the pool never blocks JVM exit.
     * @param poolId Distinguishes this scheduler's threads from others in the same JVM
     * @return Thread factory
     */
    private static ThreadFactory threadFactory(final int poolId) {
        final AtomicInteger threadIndex = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(
                runnable, "pantera-event-drain-" + poolId + "-" + threadIndex.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }
}
