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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.json.JsonObject;
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
     * Keys tried for a new repository before giving up on finding an empty
     * directory.
     */
    private static final int MAX_KEY_TRIES = 20;

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
     * Storage of a storage config.
     */
    private final Function<JsonObject, Storage> storages;

    /**
     * Orphan scan.
     */
    private final OrphanScan scan;

    /**
     * Ctor.
     * @param source Database
     * @param runner Runner to wake when a job is queued, nullable
     * @param window Default restore window of a delete
     * @param roles Drops a role from the policy cache after its grants changed
     * @param storages Storage of a storage config
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public RepoOps(
        final DataSource source, final RepoJobRunner runner, final Duration window,
        final Consumer<String> roles, final Function<JsonObject, Storage> storages
    ) {
        this.storages = storages;
        this.scan = new OrphanScan(source, storages);
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
     * Orphan scan.
     * @return Scan of the storages for directories no repository owns
     */
    public OrphanScan orphans() {
        return this.scan;
    }

    /**
     * Key for a repository about to be created, when it cannot be its name:
     * the directory named after it already holds files (left by a delete
     * made before 2.2.14), so the new repository must not adopt them.
     * @param name Repository name
     * @param config Repository config about to be saved
     * @return Explicit key, empty when the database may assign the default
     */
    public Optional<String> keyAvoidingLeftovers(final String name, final JsonObject config) {
        Optional<String> res = Optional.empty();
        try {
            final Optional<JsonObject> storage = this.life.storageConfigFor(name, config);
            if (storage.isPresent()) {
                final Storage raw = this.storages.apply(storage.get());
                final Set<String> taken = new HashSet<>();
                String candidate = this.life.freeKey(name, taken);
                int tries = 0;
                while (tries < RepoOps.MAX_KEY_TRIES && RepoOps.holdsFiles(raw, candidate)) {
                    taken.add(candidate);
                    candidate = this.life.freeKey(name, taken);
                    tries += 1;
                }
                if (!taken.isEmpty()) {
                    res = Optional.of(candidate);
                }
            }
        } catch (final RuntimeException ex) {
            // The storage cannot be inspected (unreachable, or a kind that
            // cannot be listed): the default key is used, as before 2.2.14.
            EcsLogger.warn("com.auto1.pantera.repojob")
                .message("Could not check the storage for files left under the new repository's name")
                .eventCategory("configuration")
                .eventAction("repository_key_check")
                .eventOutcome("failure")
                .field("repository.name", name)
                .error(ex)
                .field("log.source", "application")
                .log();
        }
        return res;
    }

    /**
     * Whether a top-level directory of a storage holds at least one file.
     * @param raw Storage
     * @param dir Directory
     * @return True if it does
     */
    private static boolean holdsFiles(final Storage raw, final String dir) {
        try {
            return !raw.listPage(new Key.From(dir), Optional.empty(), 1)
                .get(30, TimeUnit.SECONDS).keys().isEmpty();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (final java.util.concurrent.ExecutionException
            | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
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
