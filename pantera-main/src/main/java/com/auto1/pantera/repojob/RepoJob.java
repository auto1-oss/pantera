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
package com.auto1.pantera.repojob;

import java.time.Instant;
import java.util.Locale;
import javax.json.JsonObject;

/**
 * A row of {@code repo_jobs}.
 *
 * @param id Job id
 * @param type Kind of work
 * @param key Repository key (for an orphan purge: the storage directory)
 * @param name Repository name when the job was created
 * @param state Lifecycle state: pending, running, failed, done, cancelled
 * @param phase Step the job is in
 * @param params Job parameters
 * @param checkpoint Resume position inside the phase, nullable
 * @param objects Storage objects removed so far
 * @param rows Database rows removed or rewritten so far
 * @param attempts Failed attempts so far
 * @param error Last failure, nullable
 * @param notBefore Earliest start
 * @param owner Node holding the lease, nullable
 * @param createdBy Who started it, nullable
 * @param trace Trace id of the request that started it, nullable
 * @param created When it was created
 * @param updated When it last made progress
 * @param finished When it ended, nullable
 * @param age Time since it was created, by the database clock
 * @since 2.2.14
 */
public record RepoJob(
    long id, Type type, String key, String name, String state, String phase,
    JsonObject params, String checkpoint, long objects, long rows, int attempts,
    String error, Instant notBefore, String owner, String createdBy, String trace,
    Instant created, Instant updated, Instant finished, java.time.Duration age
) {

    /**
     * Phase of a job that has not started: a delete can still be undone.
     */
    public static final String PHASE_WAITING = "waiting";

    /**
     * Phase: removing storage objects.
     */
    public static final String PHASE_OBJECTS = "objects";

    /**
     * Phase: checking that no object was written while the purge ran.
     */
    public static final String PHASE_VERIFY = "verify";

    /**
     * Phase: removing or rewriting database rows.
     */
    public static final String PHASE_ROWS = "rows";

    /**
     * State: waiting for a node.
     */
    public static final String PENDING = "pending";

    /**
     * State: a node works on it.
     */
    public static final String RUNNING = "running";

    /**
     * State: gave up after repeated failures; an admin retries it.
     */
    public static final String FAILED = "failed";

    /**
     * State: finished.
     */
    public static final String DONE = "done";

    /**
     * State: cancelled (a restored delete).
     */
    public static final String CANCELLED = "cancelled";

    /**
     * Kind of work.
     */
    public enum Type {
        /**
         * Purge of a deleted repository's objects and rows.
         */
        PURGE,
        /**
         * Purge of a storage directory no repository owns.
         */
        ORPHAN_PURGE,
        /**
         * Rewrite of the name-keyed history rows after a rename.
         */
        RENAME_FOLLOWUP;

        /**
         * Value stored in the database.
         * @return Lower-case name
         */
        public String db() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        /**
         * Type of a database value.
         * @param value Stored value
         * @return Type
         */
        public static Type of(final String value) {
            return Type.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
}
