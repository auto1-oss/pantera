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
package com.auto1.pantera.index.reindex;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counters of the current (or last) rebuild run. Written by the single
 * rebuild thread, read by status requests on any thread.
 *
 * @since 2.2.9
 */
final class ReindexProgress {

    /**
     * Run start.
     */
    private final AtomicReference<Instant> started;

    /**
     * Run end.
     */
    private final AtomicReference<Instant> finished;

    /**
     * Repositories in the run.
     */
    private final AtomicInteger total;

    /**
     * Repositories finished.
     */
    private final AtomicInteger done;

    /**
     * Repositories skipped.
     */
    private final AtomicInteger skipped;

    /**
     * Repositories failed.
     */
    private final AtomicInteger failed;

    /**
     * Rows pruned.
     */
    private final AtomicLong pruned;

    /**
     * Rows upserted.
     */
    private final AtomicLong upserted;

    /**
     * Rows removed.
     */
    private final AtomicLong removed;

    /**
     * Last error.
     */
    private final AtomicReference<String> error;

    /**
     * Ctor.
     */
    ReindexProgress() {
        this.started = new AtomicReference<>();
        this.finished = new AtomicReference<>();
        this.total = new AtomicInteger();
        this.done = new AtomicInteger();
        this.skipped = new AtomicInteger();
        this.failed = new AtomicInteger();
        this.pruned = new AtomicLong();
        this.upserted = new AtomicLong();
        this.removed = new AtomicLong();
        this.error = new AtomicReference<>();
    }

    /**
     * Reset for a new run.
     * @param now Run start
     */
    void reset(final Instant now) {
        this.started.set(now);
        this.finished.set(null);
        this.total.set(0);
        this.done.set(0);
        this.skipped.set(0);
        this.failed.set(0);
        this.pruned.set(0L);
        this.upserted.set(0L);
        this.removed.set(0L);
        this.error.set(null);
    }

    /**
     * Set the repository count.
     * @param count Repositories in the run
     */
    void total(final int count) {
        this.total.set(count);
    }

    /**
     * A repository was reindexed.
     */
    void repoDone() {
        this.done.incrementAndGet();
    }

    /**
     * A repository was skipped.
     */
    void repoSkipped() {
        this.skipped.incrementAndGet();
        this.done.incrementAndGet();
    }

    /**
     * A repository failed.
     * @param message Error message
     */
    void repoFailed(final String message) {
        this.failed.incrementAndGet();
        this.done.incrementAndGet();
        this.error.set(message);
    }

    /**
     * Rows pruned.
     * @param rows Count
     */
    void pruned(final long rows) {
        this.pruned.addAndGet(rows);
    }

    /**
     * Rows upserted.
     * @param rows Count
     */
    void upserted(final long rows) {
        this.upserted.addAndGet(rows);
    }

    /**
     * Rows removed.
     * @param rows Count
     */
    void removed(final long rows) {
        this.removed.addAndGet(rows);
    }

    /**
     * Mark the run finished.
     * @param now Run end
     * @param message Fatal error message, null on completion
     */
    void finish(final Instant now, final String message) {
        if (message != null) {
            this.error.set(message);
        }
        this.finished.set(now);
    }

    /**
     * Snapshot.
     * @param running Whether a run is in progress
     * @return Status
     */
    ReindexStatus snapshot(final boolean running) {
        return new ReindexStatus(
            running, this.started.get(), running ? null : this.finished.get(),
            this.total.get(), this.done.get(), this.skipped.get(), this.failed.get(),
            this.pruned.get(), this.upserted.get(), this.removed.get(), this.error.get()
        );
    }

    /**
     * Repositories failed so far.
     * @return Count
     */
    int failures() {
        return this.failed.get();
    }
}
