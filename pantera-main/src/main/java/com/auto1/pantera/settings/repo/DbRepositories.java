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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import javax.sql.DataSource;

/**
 * Database-backed {@link Repositories} implementation.
 * Reads repository configurations from PostgreSQL instead of YAML files.
 * Storage aliases are resolved from the storage_aliases table.
 *
 * @since 1.21
 */
public final class DbRepositories implements Repositories {

    private final RepositoryDao dao;
    private final StorageAliasDao aliasDao;
    private final StoragesCache storagesCache;
    private final boolean metrics;
    private final Json2Yaml json2yaml;
    private final AtomicReference<Map<String, RepoConfig>> cache;
    private final ExecutorService loader;

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
        this.cache = new AtomicReference<>(Collections.emptyMap());
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
        return Optional.ofNullable(this.cache.get().get(name));
    }

    @Override
    public Collection<RepoConfig> configs() {
        return this.cache.get().values();
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
            final Collection<String> names = this.dao.listAll();
            // Load global aliases once for all repos
            final List<JsonObject> globalAliases = this.aliasDao.listGlobal();
            final Map<String, RepoConfig> loaded = names.stream()
                .map(name -> this.loadOne(name, globalAliases))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                    RepoConfig::name,
                    cfg -> cfg,
                    (a, b) -> b
                ));
            this.cache.set(Collections.unmodifiableMap(loaded));
            EcsLogger.info("com.auto1.pantera.settings")
                .message(String.format(
                    "Loaded %d repository configurations from database", loaded.size()
                ))
                .eventCategory("configuration")
                .eventAction("config_load")
                .eventOutcome("success")
                .log();
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.settings")
                .message("Failed to load repository configurations from database")
                .eventCategory("configuration")
                .eventAction("config_load")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
    }

    /**
     * Load a single repo config from DB and convert to RepoConfig.
     * @param name Repository name
     * @param globalAliases Pre-loaded global storage aliases
     * @return RepoConfig or null on error
     */
    private RepoConfig loadOne(final String name, final List<JsonObject> globalAliases) {
        try {
            final JsonStructure value = this.dao.value(
                new com.auto1.pantera.api.RepositoryName.Simple(name)
            );
            final YamlMapping yaml = this.json2yaml.apply(value.toString());
            // Merge global + per-repo aliases
            final List<JsonObject> repoAliases = this.aliasDao.listForRepo(name);
            final List<JsonObject> merged = new java.util.ArrayList<>(globalAliases);
            merged.addAll(repoAliases);
            final StorageByAlias aliases = DbStorageByAlias.from(merged);
            return RepoConfig.from(
                yaml, aliases,
                new Key.From(name),
                this.storagesCache,
                this.metrics
            );
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.settings")
                .message("Failed to load repository config from database")
                .eventCategory("configuration")
                .eventAction("config_parse")
                .eventOutcome("failure")
                .field("repository.name", name)
                .error(ex)
                .log();
            return null;
        }
    }
}
