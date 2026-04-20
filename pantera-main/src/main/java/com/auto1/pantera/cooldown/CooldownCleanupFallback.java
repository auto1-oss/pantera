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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.Vertx;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vertx-periodic fallback that runs cooldown cleanup + history purge when
 * pg_cron is unavailable or the scheduled job is missing.
 *
 * <p>Cleanup tick fires every 10 minutes. History purge is gated on a
 * 24-hour interval (checked hourly). Both paths read live tunables
 * ({@link CooldownSettings#cleanupBatchLimit()},
 * {@link CooldownSettings#historyRetentionDays()}) from the shared
 * {@link CooldownSettings} on every invocation — admin UI changes take
 * effect without restart.
 *
 * <p>Blocking JDBC work runs on {@link HandlerExecutor#get()} (the 2.2.0
 * shared worker pool), NOT on the Vertx event loop.
 *
 * @since 2.2.1
 */
public final class CooldownCleanupFallback {

    /** Cleanup cadence — 10 minutes between archive sweeps. */
    private static final long TEN_MINUTES_MS = Duration.ofMinutes(10).toMillis();

    /** Purge probe cadence — check hourly whether the 24h gate has elapsed. */
    private static final long ONE_HOUR_MS = Duration.ofHours(1).toMillis();

    /** Minimum spacing between actual purge runs. */
    private static final long ONE_DAY_MS = Duration.ofHours(24).toMillis();

    /**
     * Hard cap on per-tick batch iterations so a pathological backlog can
     * never monopolise the worker thread. At the default batch limit of
     * 10 000, this caps a single tick at 1 million rows.
     */
    private static final int MAX_BATCH_ITERATIONS = 100;

    private final CooldownRepository repo;
    private final CooldownSettings settings;
    private final AtomicLong lastPurgeAt = new AtomicLong(0L);

    private Long cleanupTimerId;
    private Long purgeTimerId;

    /**
     * Ctor.
     *
     * @param repo Cooldown repository used for archive + purge SQL
     * @param settings Live-tunable settings (batch limit, retention days)
     */
    public CooldownCleanupFallback(final CooldownRepository repo,
                                   final CooldownSettings settings) {
        this.repo = repo;
        this.settings = settings;
    }

    /**
     * Start the two periodic timers. Called from startup wiring (Task 9)
     * only when {@code pg_cron} is unavailable or the scheduled job is
     * missing.
     *
     * @param vertx Vertx instance used to schedule the periodic timers
     */
    public void start(final Vertx vertx) {
        EcsLogger.info("com.auto1.pantera.cooldown.cleanup")
            .message("pg_cron cleanup job not scheduled; starting Vertx fallback")
            .field("interval_min", 10)
            .field("batch_limit", this.settings.cleanupBatchLimit())
            .field("retention_days", this.settings.historyRetentionDays())
            .log();
        this.cleanupTimerId = vertx.setPeriodic(TEN_MINUTES_MS,
            id -> this.dispatchCleanup());
        this.purgeTimerId = vertx.setPeriodic(ONE_HOUR_MS,
            id -> this.dispatchPurgeCheck());
    }

    /**
     * Cancel both periodic timers. Idempotent — safe to call if
     * {@link #start(Vertx)} was never invoked.
     *
     * @param vertx Vertx instance that owns the timers
     */
    public void stop(final Vertx vertx) {
        if (this.cleanupTimerId != null) {
            vertx.cancelTimer(this.cleanupTimerId);
            this.cleanupTimerId = null;
        }
        if (this.purgeTimerId != null) {
            vertx.cancelTimer(this.purgeTimerId);
            this.purgeTimerId = null;
        }
    }

    private void dispatchCleanup() {
        CompletableFuture
            .runAsync(this::runCleanupOnce, HandlerExecutor.get())
            .exceptionally(this::logCleanupError);
    }

    private void dispatchPurgeCheck() {
        CompletableFuture
            .runAsync(this::maybePurgeHistory, HandlerExecutor.get())
            .exceptionally(this::logPurgeError);
    }

    /**
     * Archive expired live-table rows in repeated batches until either the
     * table is drained or {@link #MAX_BATCH_ITERATIONS} is reached. Visible
     * for tests; runs blocking JDBC.
     */
    void runCleanupOnce() {
        final int batchLimit = this.settings.cleanupBatchLimit();
        long totalArchived = 0L;
        for (int i = 0; i < MAX_BATCH_ITERATIONS; i++) {
            final int archived;
            try {
                archived = this.repo.archiveExpiredBatch(batchLimit);
            } catch (final RuntimeException err) {
                EcsLogger.error("com.auto1.pantera.cooldown.cleanup")
                    .message("fallback cleanup iteration failed")
                    .field("iteration", i)
                    .field("total_archived_this_run", totalArchived)
                    .error(err)
                    .log();
                return;
            }
            totalArchived += archived;
            if (archived < batchLimit) {
                break;
            }
        }
        if (totalArchived > 0L) {
            EcsLogger.info("com.auto1.pantera.cooldown.cleanup")
                .message("fallback cleanup completed")
                .field("archived", totalArchived)
                .log();
        }
    }

    /**
     * Run the history purge iff at least 24h have elapsed since the last
     * successful run. Visible for tests; runs blocking JDBC.
     */
    void maybePurgeHistory() {
        final long now = Instant.now().toEpochMilli();
        if (now - this.lastPurgeAt.get() < ONE_DAY_MS) {
            return;
        }
        final int retentionDays = this.settings.historyRetentionDays();
        final int batchLimit = this.settings.cleanupBatchLimit();
        final long cutoff = Instant.now()
            .minus(retentionDays, ChronoUnit.DAYS).toEpochMilli();
        long totalPurged = 0L;
        for (int i = 0; i < MAX_BATCH_ITERATIONS; i++) {
            final int purged;
            try {
                purged = this.repo.purgeHistoryOlderThan(cutoff, batchLimit);
            } catch (final RuntimeException err) {
                EcsLogger.error("com.auto1.pantera.cooldown.history")
                    .message("fallback purge iteration failed")
                    .field("iteration", i)
                    .field("total_purged_this_run", totalPurged)
                    .error(err)
                    .log();
                return;
            }
            totalPurged += purged;
            if (purged < batchLimit) {
                break;
            }
        }
        this.lastPurgeAt.set(now);
        if (totalPurged > 0L) {
            EcsLogger.info("com.auto1.pantera.cooldown.history")
                .message("fallback history purge completed")
                .field("purged", totalPurged)
                .field("retention_days", retentionDays)
                .log();
        }
    }

    private Void logCleanupError(final Throwable err) {
        EcsLogger.error("com.auto1.pantera.cooldown.cleanup")
            .message("fallback cleanup tick failed")
            .error(unwrap(err))
            .log();
        return null;
    }

    private Void logPurgeError(final Throwable err) {
        EcsLogger.error("com.auto1.pantera.cooldown.history")
            .message("fallback purge tick failed")
            .error(unwrap(err))
            .log();
        return null;
    }

    private static Throwable unwrap(final Throwable err) {
        if (err.getCause() != null
            && err instanceof java.util.concurrent.CompletionException) {
            return err.getCause();
        }
        return err;
    }
}
