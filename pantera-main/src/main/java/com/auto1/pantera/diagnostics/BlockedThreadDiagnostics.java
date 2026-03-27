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
package com.auto1.pantera.diagnostics;

import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.VertxOptions;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostics for identifying blocked thread root causes.
 * <p>Captures periodic snapshots of thread states and GC activity
 * to help diagnose Vert.x blocked thread warnings after the fact.</p>
 * 
 * <p><b>Performance overhead:</b></p>
 * <ul>
 *   <li>GC check: ~0.01ms every 1s (JMX counter read)</li>
 *   <li>Thread state check: ~1-5ms every 5s (scales with thread count)</li>
 *   <li>Full dump: ~10-50ms only when issues detected</li>
 *   <li>Total: &lt;0.1% CPU overhead, never blocks Vert.x event loops</li>
 * </ul>
 * 
 * <p>Disable with environment variable: PANTERA_DIAGNOSTICS_DISABLED=true</p>
 *
 * @since 1.20.10
 */
public final class BlockedThreadDiagnostics {

    /**
     * Environment variable to disable diagnostics.
     */
    private static final String DISABLE_ENV = "PANTERA_DIAGNOSTICS_DISABLED";

    /**
     * Singleton instance.
     */
    private static volatile BlockedThreadDiagnostics instance;

    /**
     * Scheduler for periodic diagnostics.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Thread MXBean for thread info.
     */
    private final ThreadMXBean threadBean;

    /**
     * Last GC time tracking.
     */
    private final AtomicLong lastGcTime = new AtomicLong(0);

    /**
     * Last GC count tracking.
     */
    private final AtomicLong lastGcCount = new AtomicLong(0);

    /**
     * Threshold for logging long GC pauses (ms).
     */
    private static final long GC_PAUSE_THRESHOLD_MS = 500;

    /**
     * Private constructor.
     */
    private BlockedThreadDiagnostics() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "blocked-thread-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        this.threadBean = ManagementFactory.getThreadMXBean();
    }

    /**
     * Initialize diagnostics.
     * @return The diagnostics instance (null if disabled)
     */
    public static synchronized BlockedThreadDiagnostics initialize() {
        // Check if disabled via environment variable
        final String disabled = System.getenv(DISABLE_ENV);
        if ("true".equalsIgnoreCase(disabled)) {
            EcsLogger.info("com.auto1.pantera.diagnostics")
                .message("Blocked thread diagnostics disabled via environment variable")
                .eventCategory("system")
                .eventAction("diagnostics_disabled")
                .log();
            return null;
        }
        
        if (instance == null) {
            instance = new BlockedThreadDiagnostics();
            instance.start();
            EcsLogger.info("com.auto1.pantera.diagnostics")
                .message(String.format(
                    "Blocked thread diagnostics initialized: GC check interval 1s, thread check interval 5s, GC pause threshold %dms",
                    GC_PAUSE_THRESHOLD_MS))
                .eventCategory("system")
                .eventAction("diagnostics_init")
                .log();
        }
        return instance;
    }

    /**
     * Start periodic diagnostics collection.
     */
    private void start() {
        // Check GC activity every second
        this.scheduler.scheduleAtFixedRate(
            this::checkGcActivity,
            1, 1, TimeUnit.SECONDS
        );

        // Log event loop thread states every 5 seconds (debug level)
        this.scheduler.scheduleAtFixedRate(
            this::logEventLoopThreadStates,
            5, 5, TimeUnit.SECONDS
        );
    }

    /**
     * Check GC activity and log if there were long pauses.
     */
    private void checkGcActivity() {
        try {
            long totalGcTime = 0;
            long totalGcCount = 0;

            for (final GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gcBean.getCollectionTime() >= 0) {
                    totalGcTime += gcBean.getCollectionTime();
                }
                if (gcBean.getCollectionCount() >= 0) {
                    totalGcCount += gcBean.getCollectionCount();
                }
            }

            final long prevTime = this.lastGcTime.getAndSet(totalGcTime);
            final long prevCount = this.lastGcCount.getAndSet(totalGcCount);

            final long gcTimeDelta = totalGcTime - prevTime;
            final long gcCountDelta = totalGcCount - prevCount;

            // Log if GC took more than threshold in the last second
            if (gcTimeDelta > GC_PAUSE_THRESHOLD_MS && gcCountDelta > 0) {
                final long avgPauseMs = gcTimeDelta / gcCountDelta;
                EcsLogger.warn("com.auto1.pantera.diagnostics")
                    .message(String.format(
                        "Long GC pause detected - may cause blocked thread warnings: time delta %dms, %d collections, avg pause %dms, total GC time %dms",
                        gcTimeDelta, gcCountDelta, avgPauseMs, totalGcTime))
                    .eventCategory("system")
                    .eventAction("gc_pause")
                    .log();

                // Also log thread states during long GC
                this.logAllBlockedThreads();
            }
        } catch (final Exception ex) {
            // Ignore diagnostics errors
        }
    }

    /**
     * Log event loop thread states for debugging.
     */
    private void logEventLoopThreadStates() {
        try {
            final ThreadInfo[] threads = this.threadBean.dumpAllThreads(false, false);
            int blockedCount = 0;
            int waitingCount = 0;
            int runnableCount = 0;

            for (final ThreadInfo info : threads) {
                if (info.getThreadName().contains("vert.x-eventloop")) {
                    switch (info.getThreadState()) {
                        case BLOCKED:
                            blockedCount++;
                            break;
                        case WAITING:
                        case TIMED_WAITING:
                            waitingCount++;
                            break;
                        case RUNNABLE:
                            runnableCount++;
                            break;
                        default:
                            break;
                    }
                }
            }

            // Only log if there are blocked event loop threads
            if (blockedCount > 0) {
                EcsLogger.warn("com.auto1.pantera.diagnostics")
                    .message(String.format(
                        "Event loop threads in BLOCKED state: %d blocked, %d waiting, %d runnable",
                        blockedCount, waitingCount, runnableCount))
                    .eventCategory("system")
                    .eventAction("thread_state")
                    .log();
                this.logAllBlockedThreads();
            }
        } catch (final Exception ex) {
            // Ignore diagnostics errors
        }
    }

    /**
     * Log all blocked threads with their stack traces.
     */
    private void logAllBlockedThreads() {
        try {
            final ThreadInfo[] threads = this.threadBean.dumpAllThreads(true, true);
            for (final ThreadInfo info : threads) {
                if (info.getThreadState() == Thread.State.BLOCKED
                    && info.getThreadName().contains("vert.x-eventloop")) {
                    
                    final StringBuilder sb = new StringBuilder();
                    sb.append("Thread ").append(info.getThreadName())
                        .append(" BLOCKED on ").append(info.getLockName())
                        .append(" owned by ").append(info.getLockOwnerName())
                        .append("\n");
                    
                    for (final StackTraceElement element : info.getStackTrace()) {
                        sb.append("\tat ").append(element).append("\n");
                    }

                    EcsLogger.error("com.auto1.pantera.diagnostics")
                        .message(String.format(
                            "Blocked event loop thread details: lock=%s, lock owner=%s",
                            info.getLockName(), info.getLockOwnerName()))
                        .eventCategory("system")
                        .eventAction("blocked_thread")
                        .field("process.thread.name", info.getThreadName())
                        .field("error.stack_trace", sb.toString())
                        .log();
                }
            }
        } catch (final Exception ex) {
            // Ignore diagnostics errors
        }
    }

    /**
     * Get recommended Vert.x options with optimized blocked thread checking.
     * @param cpuCores Number of CPU cores
     * @return Configured VertxOptions
     */
    public static VertxOptions getOptimizedVertxOptions(final int cpuCores) {
        return new VertxOptions()
            // Event loop pool size: 2x CPU cores for optimal throughput
            .setEventLoopPoolSize(cpuCores * 2)
            // Worker pool size: for blocking operations
            .setWorkerPoolSize(Math.max(20, cpuCores * 4))
            // Increase blocked thread check interval to 10s to reduce false positives
            // The default 1s is too aggressive and catches GC pauses
            .setBlockedThreadCheckInterval(10000)
            // Warn if event loop blocked for more than 5 seconds (increased from 2s)
            // This accounts for GC pauses and reduces false positives
            .setMaxEventLoopExecuteTime(5000L * 1000000L)
            // Warn if worker thread blocked for more than 120 seconds
            .setMaxWorkerExecuteTime(120000L * 1000000L);
    }

    /**
     * Shutdown diagnostics instance.
     */
    public void shutdown() {
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                this.scheduler.shutdownNow();
            }
        } catch (final InterruptedException e) {
            this.scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shutdown the singleton diagnostics instance (if initialized).
     * Safe to call even if never initialized.
     */
    public static synchronized void shutdownInstance() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
}
