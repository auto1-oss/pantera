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

import java.time.Duration;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * What the management API needs to rename, delete and restore repositories
 * and to follow the jobs that purge them. Present only with a database.
 *
 * @since 2.2.14
 */
public final class RepoOps {

    /**
     * Longest restore window a delete may ask for.
     */
    public static final int MAX_RETENTION_HOURS = 24 * 365;

    /**
     * Longest time a former name may keep serving after a rename.
     */
    public static final int MAX_ALIAS_DAYS = 365;

    /**
     * Rename, delete, restore.
     */
    private final RepoLifecycleDao life;

    /**
     * Jobs.
     */
    private final RepoJobDao queue;

    /**
     * Runner to wake when a job is queued, nullable.
     */
    private final RepoJobRunner runner;

    /**
     * Restore window of a delete that does not ask for one.
     */
    private final Duration window;

    /**
     * Drops a role from the policy cache.
     */
    private final Consumer<String> roles;

    /**
     * Ctor.
     * @param source Database
     * @param runner Runner to wake when a job is queued, nullable
     * @param window Default restore window of a delete
     * @param roles Drops a role from the policy cache after its grants changed
     */
    public RepoOps(
        final DataSource source, final RepoJobRunner runner, final Duration window,
        final Consumer<String> roles
    ) {
        this.life = new RepoLifecycleDao(source);
        this.queue = new RepoJobDao(source);
        this.runner = runner;
        this.window = window;
        this.roles = roles;
    }

    /**
     * Rename, delete, restore.
     * @return Lifecycle operations
     */
    public RepoLifecycleDao lifecycle() {
        return this.life;
    }

    /**
     * Jobs.
     * @return Job access
     */
    public RepoJobDao jobs() {
        return this.queue;
    }

    /**
     * Default restore window of a delete.
     * @return Window
     */
    public Duration retention() {
        return this.window;
    }

    /**
     * A job was queued: let this node look for it now.
     */
    public void wake() {
        if (this.runner != null) {
            this.runner.wake();
        }
    }

    /**
     * Role grants changed: drop the roles from the policy cache.
     * @param names Role names
     */
    public void rolesChanged(final Iterable<String> names) {
        names.forEach(this.roles);
    }

    /**
     * Default restore window from {@code PANTERA_REPO_DELETE_RETENTION_HOURS}
     * (24 hours when unset or invalid; 0 purges at once).
     * @param value Value of the variable, nullable
     * @return Window
     */
    public static Duration retentionOf(final String value) {
        long hours = 24L;
        if (value != null && !value.isBlank()) {
            try {
                final long parsed = Long.parseLong(value.trim());
                if (parsed >= 0 && parsed <= RepoOps.MAX_RETENTION_HOURS) {
                    hours = parsed;
                }
            } catch (final NumberFormatException ignored) {
                hours = 24L;
            }
        }
        return Duration.ofHours(hours);
    }
}
