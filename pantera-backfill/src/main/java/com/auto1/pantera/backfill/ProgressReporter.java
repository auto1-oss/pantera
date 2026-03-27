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
package com.auto1.pantera.backfill;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe progress reporter that tracks scanned records and errors,
 * logging throughput statistics at a configurable interval.
 *
 * @since 1.20.13
 */
public final class ProgressReporter {

    /**
     * SLF4J logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(ProgressReporter.class);

    /**
     * Number of records between periodic log messages.
     */
    private final int logInterval;

    /**
     * Total records scanned / processed.
     */
    private final AtomicLong scanned;

    /**
     * Total errors encountered.
     */
    private final AtomicLong errors;

    /**
     * Timestamp (epoch millis) when this reporter was created.
     */
    private final long startTime;

    /**
     * Ctor.
     *
     * @param logInterval Log progress every N records
     */
    public ProgressReporter(final int logInterval) {
        this.logInterval = logInterval;
        this.scanned = new AtomicLong(0L);
        this.errors = new AtomicLong(0L);
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Increment the scanned counter. Every {@code logInterval} records a
     * progress line with throughput (records/sec) is logged.
     */
    public void increment() {
        final long count = this.scanned.incrementAndGet();
        if (count % this.logInterval == 0) {
            final long elapsed = System.currentTimeMillis() - this.startTime;
            final double secs = elapsed / 1_000.0;
            final double throughput = secs > 0 ? count / secs : 0;
            LOG.info("Progress: {} records scanned ({} errors) — {}/sec",
                count, this.errors.get(),
                String.format("%.1f", throughput));
        }
    }

    /**
     * Record an error.
     */
    public void recordError() {
        this.errors.incrementAndGet();
    }

    /**
     * Return the current scanned count.
     *
     * @return Number of records scanned so far
     */
    public long getScanned() {
        return this.scanned.get();
    }

    /**
     * Return the current error count.
     *
     * @return Number of errors recorded so far
     */
    public long getErrors() {
        return this.errors.get();
    }

    /**
     * Log final summary with total scanned, errors, elapsed time, and
     * overall throughput.
     */
    public void printFinalSummary() {
        final long elapsed = System.currentTimeMillis() - this.startTime;
        final double secs = elapsed / 1_000.0;
        final long total = this.scanned.get();
        final double throughput = secs > 0 ? total / secs : 0;
        LOG.info("=== Backfill Summary ===");
        LOG.info("Total scanned : {}", total);
        LOG.info("Total errors  : {}", this.errors.get());
        LOG.info("Elapsed time  : {}s", String.format("%.1f", secs));
        LOG.info("Throughput    : {}/sec",
            String.format("%.1f", throughput));
    }
}
