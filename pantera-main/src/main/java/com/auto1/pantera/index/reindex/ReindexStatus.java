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

/**
 * Point-in-time view of the index rebuild job.
 *
 * @param running Whether a run is in progress
 * @param startedAt Start of the current or last run, null before the first
 * @param finishedAt End of the last run, null while running or before the first
 * @param reposTotal Repositories the run walks
 * @param reposDone Repositories finished (reindexed, skipped or failed)
 * @param reposSkipped Repositories skipped (no scanner, no local storage, ...)
 * @param reposFailed Repositories whose rebuild failed
 * @param rowsPruned Rows deleted because their repository no longer exists
 * @param rowsUpserted Rows inserted or updated from storage
 * @param rowsRemoved Rows deleted because their artifact is gone from storage
 * @param lastError Last error of the current or last run, null if none
 * @since 2.2.9
 */
public record ReindexStatus(
    boolean running,
    Instant startedAt,
    Instant finishedAt,
    int reposTotal,
    int reposDone,
    int reposSkipped,
    int reposFailed,
    long rowsPruned,
    long rowsUpserted,
    long rowsRemoved,
    String lastError
) {

    /**
     * Wire state.
     * @return {@code running} or {@code idle}
     */
    public String state() {
        return this.running ? "running" : "idle";
    }
}
