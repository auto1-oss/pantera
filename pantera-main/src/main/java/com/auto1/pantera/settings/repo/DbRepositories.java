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
package com.auto1.pantera.settings.repo;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.db.dao.RepositoryDao;
import com.auto1.pantera.db.dao.StorageAliasDao;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.trace.TraceContextExecutor;
import com.auto1.pantera.misc.Json2Yaml;
import com.auto1.pantera.settings.DbStorageByAlias;
import com.auto1.pantera.settings.StorageByAlias;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.json.JsonObject;
import javax.sql.DataSource;

/**
 * Database-backed {@link Repositories} implementation.
 * Reads repository configurations from PostgreSQL instead of YAML files.
 * Storage aliases are resolved from the storage_aliases table.
 *
 * @since 1.21
 */
public final class DbRepositories implements Repositories, RepoKeys {

    private final RepositoryDao dao;
    private final StorageAliasDao aliasDao;
    private final StoragesCache storagesCache;
    private final boolean metrics;
    private final Json2Yaml json2yaml;
    private final AtomicReference<Snapshot> cache;
    private final ExecutorService loader;

    /**
     * Told which repository names changed (created, edited, renamed to) and
     * which went away (deleted, renamed from) between two loads.
     */
    private final AtomicReference<java.util.function.BiConsumer<java.util.Set<String>, java.util.Set<String>>> listener;

    /**
     * Ctor.
     * @param source Database data source
     * @param storagesCache Cache for storage instances
     * @param metrics Whether to enable storage metrics
     */
    public DbRepositories(
        final DataSource source,
        final StoragesCache storagesCache,
        final boolean metrics
    ) {
        this.dao = new RepositoryDao(source);
        this.aliasDao = new StorageAliasDao(source);
        this.storagesCache = storagesCache;
        this.metrics = metrics;
        this.json2yaml = new Json2Yaml();
        this.cache = new AtomicReference<>(Snapshot.EMPTY);
        this.listener = new AtomicReference<>((changed, removed) -> { });
        this.loader = TraceContextExecutor.wrap(
            Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "pantera.db.repo.loader");
                t.setDaemon(true);
                return t;
            })
        );
        // Blocking initial load
        this.loadAll();
    }

    @Override
    public Optional<RepoConfig> config(final String name) {
        final Snapshot snap = this.cache.get();
        RepoConfig cfg = snap.byName().get(name);
        if (cfg == null) {
            // A former name the rename asked to keep working.
            final String key = snap.routable().get(name);
            if (key != null) {
                cfg = snap.byKey().get(key);
            }
        }
        return Optional.ofNullable(cfg);
    }

    @Override
    public Collection<RepoConfig> configs() {
        return this.cache.get().byName().values();
    }

    @Override
    public Optional<String> key(final String name) {
        final Snapshot snap = this.cache.get();
        final RepoConfig cfg = snap.byName().get(name);
        final Optional<String> res;
        if (cfg == null) {
            res = Optional.ofNullable(snap.former().get(name));
        } else {
            res = Optional.of(cfg.repoKey());
        }
        return res;
    }

    @Override
    public Optional<String> name(final String key) {
        return Optional.ofNullable(this.cache.get().byKey().get(key)).map(RepoConfig::name);
    }

    @Override
    public Optional<List<String>> live() {
        return Optional.of(List.copyOf(this.cache.get().byKey().keySet()));
    }

    /**
     * Former names that still route to a repository.
     * @return Routable alias names of the given repository key
     */
    public Collection<String> routableAliases(final String key) {
        return this.cache.get().routable().entrySet().stream()
            .filter(entry -> entry.getValue().equals(key))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Be told what changed between two loads. Every node reloads on a timer
     * as well as on repository events, so a node that missed an event (a
     * lost cluster message) still drops its stale state at the next reload.
     * @param consumer Receives the names that changed and the names that
     *  went away; called on the loader thread
     */
    public void onChange(
        final java.util.function.BiConsumer<java.util.Set<String>, java.util.Set<String>> consumer
    ) {
        this.listener.set(consumer);
    }

    @Override
    public CompletableFuture<Void> refreshAsync() {
        return CompletableFuture.runAsync(this::loadAll, this.loader);
    }

    /**
     * Load all repository configs from DB into the in-memory cache.
     */
    private void loadAll() {
        try {
            final List<RepositoryDao.Entry> entries = this.dao.entries();
            // Load global and per-repository aliases once for all repos
            final List<JsonObject> globalAliases = this.aliasDao.listGlobal();
            final Map<String, List<JsonObject>> repoAliases = this.aliasDao.listPerRepo();
            final Map<String, RepoConfig> byName = new java.util.LinkedHashMap<>();
            final Map<String, RepoConfig> byKey = new java.util.HashMap<>();
            for (final RepositoryDao.Entry entry : entries) {
                final RepoConfig cfg = this.loadOne(
                    entry, globalAliases,
                    repoAliases.getOrDefault(entry.name(), List.of())
                );
                if (cfg != null) {
                    byName.put(cfg.name(), cfg);
                    byKey.put(cfg.repoKey(), cfg);
                }
            }
            final Map<String, String> former = new java.util.HashMap<>();
            final Map<String, String> routable = new java.util.HashMap<>();
            for (final RepositoryDao.Alias alias : this.dao.aliases()) {
                if (byName.containsKey(alias.alias()) || !byKey.containsKey(alias.key())) {
                    continue;
                }
                former.put(alias.alias(), alias.key());
                if (alias.routable()) {
                    routable.put(alias.alias(), alias.key());
                }
            }
            final Map<String, String> prints = new java.util.HashMap<>();
            for (final RepositoryDao.Entry entry : entries) {
                if (byName.containsKey(entry.name())) {
                    prints.put(entry.name(), entry.key() + '\n' + entry.config());
                }
            }
            routable.forEach((alias, key) -> prints.put(alias, "alias of " + key));
            final Snapshot before = this.cache.getAndSet(
                new Snapshot(
                    Collections.unmodifiableMap(byName), Collections.unmodifiableMap(byKey),
                    Collections.unmodifiableMap(former), Collections.unmodifiableMap(routable),
                    Collections.unmodifiableMap(prints)
                )
            );
            final java.util.Set<String> changed = new java.util.HashSet<>();
            prints.forEach((name, print) -> {
                if (!print.equals(before.prints().get(name))) {
                    changed.add(name);
                }
            });
            final java.util.Set<String> removed = new java.util.HashSet<>(before.prints().keySet());
            removed.removeAll(prints.keySet());
            if (before == Snapshot.EMPTY || !changed.isEmpty() || !removed.isEmpty()) {
                EcsLogger.info("com.auto1.pantera.settings")
                    .message(String.format(
                        "Loaded %d repository configurations from database (%d changed, %d removed)",
                        byName.size(), changed.size(), removed.size()
                    ))
                    .eventCategory("configuration")
                    .eventAction("config_load")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
            if (before != Snapshot.EMPTY && (!changed.isEmpty() || !removed.isEmpty())) {
                this.listener.get().accept(changed, removed);
            }
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.settings")
                .message("Failed to load repository configurations from database")
                .eventCategory("configuration")
                .eventAction("config_load")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Convert a repository row to RepoConfig.
     * @param entry Repository row
     * @param globalAliases Pre-loaded global storage aliases
     * @param repoAliases Pre-loaded storage aliases of this repository
     * @return RepoConfig or null on error
     */
    private RepoConfig loadOne(
        final RepositoryDao.Entry entry, final List<JsonObject> globalAliases,
        final List<JsonObject> repoAliases
    ) {
        try {
            final YamlMapping yaml = this.json2yaml.apply(entry.config());
            // Merge global + per-repo aliases
            final List<JsonObject> merged = new java.util.ArrayList<>(globalAliases);
            merged.addAll(repoAliases);
            final StorageByAlias aliases = DbStorageByAlias.from(merged);
            return RepoConfig.from(
                yaml, aliases,
                entry.name(),
                new Key.From(entry.key()),
                this.storagesCache,
                this.metrics
            );
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.settings")
                .message("Failed to load repository config from database")
                .eventCategory("configuration")
                .eventAction("config_parse")
                .eventOutcome("failure")
                .field("repository.name", entry.name())
                .error(ex)
                .field("log.source", "application")
                .log();
            return null;
        }
    }

    /**
     * One consistent view of the repositories.
     * @param byName Configs by current name
     * @param byKey Configs by immutable key
     * @param former Former names (all remembered aliases) to key
     * @param routable Former names that still serve requests, to key
     * @param prints What each served name stood for at load time (key and
     *  config text), to tell what changed at the next load
     */
    private record Snapshot(
        Map<String, RepoConfig> byName, Map<String, RepoConfig> byKey,
        Map<String, String> former, Map<String, String> routable,
        Map<String, String> prints
    ) {
        /**
         * No repositories.
         */
        static final Snapshot EMPTY =
            new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
