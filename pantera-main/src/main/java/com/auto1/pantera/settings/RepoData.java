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
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.Scalar;
import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Copy;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;

import com.auto1.pantera.misc.Json2Yaml;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import javax.json.JsonValue;

/**
 * Repository data management.
 *
 * <p>Every delete here removes a <em>path subtree</em>: the key itself and
 * the keys under {@code <key>/}. Storage listings are raw prefix scans on
 * S3 and in memory, so a plain {@code deleteAll(new Key.From("maven"))}
 * also removed {@code maven-proxy/...} of a sibling repository sharing the
 * storage root, and deleting the folder {@code com/acme/lib} removed
 * {@code com/acme/lib-extra/...}.</p>
 */
public final class RepoData {
    /**
     * Key 'storage' inside json-object.
     */
    private static final String STORAGE = "storage";

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.settings";

    /**
     * Repository settings storage.
     */
    private final Storage configStorage;

    /**
     * Storages cache.
     */
    private final StoragesCache storagesCache;

    /**
     * Database storage aliases visible to a repository (global and its
     * own, as {@code storage_aliases} records); empty without a database.
     */
    private final Function<String, List<JsonObject>> dbAliases;

    /**
     * Executor for the blocking lookups (JDBC): the settings DB fallback
     * and the database alias records. Never the common pool.
     */
    private final Executor blocking;

    /**
     * Ctor for deployments without a database: aliases come from the
     * {@code _storages.yaml} files only.
     *
     * @param configStorage Repository settings storage
     * @param storagesCache Storages cache
     */
    public RepoData(final Storage configStorage, final StoragesCache storagesCache) {
        this(configStorage, storagesCache, repo -> List.of());
    }

    /**
     * Ctor.
     *
     * @param configStorage Repository settings storage
     * @param storagesCache Storages cache
     * @param dbAliases Database storage aliases (global and per-repository)
     *  visible to a repository, resolved like the serving path does
     */
    public RepoData(
        final Storage configStorage, final StoragesCache storagesCache,
        final Function<String, List<JsonObject>> dbAliases
    ) {
        this(configStorage, storagesCache, dbAliases, HandlerExecutor.get());
    }

    /**
     * Ctor.
     *
     * @param configStorage Repository settings storage
     * @param storagesCache Storages cache
     * @param dbAliases Database storage aliases (global and per-repository)
     *  visible to a repository, resolved like the serving path does
     * @param blocking Executor for the blocking (JDBC) lookups
     */
    public RepoData(
        final Storage configStorage, final StoragesCache storagesCache,
        final Function<String, List<JsonObject>> dbAliases, final Executor blocking
    ) {
        this.configStorage = configStorage;
        this.storagesCache = storagesCache;
        this.dbAliases = dbAliases;
        this.blocking = blocking;
    }

    /**
     * Remove data from the repository (legacy YAML-only storage lookup).
     * See {@link #remove(RepositoryName, CrudRepoSettings)}.
     * @param rname Repository name
     * @return Completable action of the remove operation
     */
    public CompletionStage<Void> remove(final RepositoryName rname) {
        return this.remove(rname, null);
    }

    /**
     * Remove the repository's own data: every key under {@code <repo>/} of
     * its storage, and nothing else. The storage is resolved with the DB
     * fallback, so repositories created through the API or UI (no YAML
     * file) are removed too. A repository without storage of its own (a
     * group) has no data and completes immediately.
     *
     * @param rname Repository name
     * @param crs Repository settings CRUD for the DB fallback, nullable
     * @return Completable action of the remove operation
     */
    public CompletionStage<Void> remove(
        final RepositoryName rname, final CrudRepoSettings crs
    ) {
        final String repo = rname.toString();
        final Key root;
        try {
            root = RepoData.repoRoot(repo);
        } catch (final IllegalArgumentException bad) {
            return CompletableFuture.failedFuture(bad);
        }
        return this.repoStorage(rname, crs)
            .<Optional<Storage>>thenApply(Optional::of)
            .exceptionally(err -> {
                final Throwable cause = RepoData.unwrap(err);
                if (!(cause instanceof NoStorageConfigured)) {
                    // The config names a storage that cannot be built (a
                    // removed alias, a broken block): there is no data this
                    // node can reach. Refusing the delete would leave the
                    // repository undeletable, so it proceeds -- loudly.
                    EcsLogger.warn(RepoData.LOGGER)
                        .message("Repository storage could not be resolved, its data was left in place")
                        .eventCategory("file")
                        .eventAction("data_remove")
                        .eventOutcome("failure")
                        .field("repository.name", repo)
                        .error(cause)
                        .field("log.source", "application")
                        .log();
                }
                return Optional.empty();
            })
            .thenCompose(asto -> {
                if (asto.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                // Then the directories the files left behind: filesystem
                // storages keep (hidden) working directories that hold no
                // keys, e.g. an adapter's upload staging directory.
                return RepoData.deleteTree(asto.get(), root).thenCompose(
                    removed -> asto.get().deleteEmptyDirectories(root)
                        .thenApply(nothing -> removed)
                ).thenAccept(
                    removed -> EcsLogger.info(RepoData.LOGGER)
                        .message("Removed data from repository (" + removed + " keys)")
                        .eventCategory("file")
                        .eventAction("data_remove")
                        .eventOutcome("success")
                        .field("repository.name", repo)
                        .field("log.source", "application")
                        .log()
                );
            });
    }

    /**
     * Delete artifact from repository storage.
     * Legacy overload — uses YAML-only storage lookup and will fail with
     * {@code ValueNotFoundException: No value for key: {repo}.yml} on
     * repositories that exist only in the DB. Prefer
     * {@link #deleteArtifact(RepositoryName, String, CrudRepoSettings)}.
     * @param rname Repository name
     * @param artifactPath Path to the artifact within repository storage
     * @return Completable action of the delete operation, returns true if deleted, false if not found
     */
    public CompletionStage<Boolean> deleteArtifact(final RepositoryName rname, final String artifactPath) {
        return this.deleteArtifact(rname, artifactPath, null);
    }

    /**
     * Delete artifact with DB-fallback storage lookup. This is the path
     * the REST delete handler must use — DB-only repos (every 2.0+ repo
     * created via the management UI) have no `.yml` in configStorage and
     * would otherwise 500 on `ValueNotFoundException`.
     *
     * @param rname Repository name
     * @param artifactPath Path to the artifact within repository storage
     * @param crs Repository settings CRUD — nullable, falls back to
     *            the YAML-only lookup when null
     * @return Completable action: true if deleted, false if not found
     */
    public CompletionStage<Boolean> deleteArtifact(
        final RepositoryName rname, final String artifactPath,
        final CrudRepoSettings crs
    ) {
        final String repo = rname.toString();
        final Key artifactKey = new Key.From(repo, artifactPath);
        return this.repoStorage(rname, crs)
            .thenCompose(asto -> asto.exists(artifactKey)
                .thenCompose(exists -> {
                    if (!exists) {
                        // A directory: delete its subtree (never siblings
                        // that merely share the name as a string prefix).
                        return RepoData.deleteTree(asto, artifactKey)
                            .thenApply(removed -> {
                                if (removed == 0) {
                                    return false;
                                }
                                EcsLogger.info(RepoData.LOGGER)
                                    .message("Deleted artifact directory from repository, " + removed + " files removed")
                                    .eventCategory("file")
                                    .eventAction("artifact_delete")
                                    .eventOutcome("success")
                                    .field("repository.name", repo)
                                    .field("file.path", artifactPath)
                                    .field("log.source", "application")
                                    .log();
                                return true;
                            });
                    }
                    // Single file - delete it
                    return asto.delete(artifactKey)
                        .thenApply(nothing -> {
                            EcsLogger.info(RepoData.LOGGER)
                                .message("Deleted artifact file from repository")
                                .eventCategory("file")
                                .eventAction("artifact_delete")
                                .eventOutcome("success")
                                .field("repository.name", repo)
                                .field("file.path", artifactPath)
                                .field("log.source", "application")
                                .log();
                            return true;
                        });
                })
            );
    }

    /**
     * Delete a package folder (legacy YAML-only lookup).
     * See {@link #deletePackageFolder(RepositoryName, String, CrudRepoSettings)}.
     * @param rname Repository name
     * @param packagePath Path to the package folder within repository storage
     * @return Completable action returning true if deletion happened, false if nothing found
     */
    public CompletionStage<Boolean> deletePackageFolder(final RepositoryName rname, final String packagePath) {
        return this.deletePackageFolder(rname, packagePath, null);
    }

    /**
     * Delete a package folder with DB-fallback storage lookup.
     * @param rname Repository name
     * @param packagePath Path to the package folder within repository storage
     * @param crs Repository settings CRUD — nullable, falls back to the
     *            YAML-only lookup when null
     * @return Completable action returning true if deletion happened, false if nothing found
     */
    public CompletionStage<Boolean> deletePackageFolder(
        final RepositoryName rname, final String packagePath,
        final CrudRepoSettings crs
    ) {
        final String repo = rname.toString();
        final Key folder = new Key.From(repo, packagePath);
        return this.repoStorage(rname, crs)
            .thenCompose(
                asto -> RepoData.deleteTree(asto, folder).thenCompose(
                    removed -> asto.deleteEmptyDirectories(folder)
                        .thenApply(nothing -> removed)
                )
            )
            .thenApply(removed -> {
                if (removed == 0) {
                    return false;
                }
                this.logPackageDelete(repo, packagePath);
                return true;
            });
    }

    private void logPackageDelete(final String repo, final String packagePath) {
        EcsLogger.info(RepoData.LOGGER)
            .message("Deleted package folder from repository")
            .eventCategory("file")
            .eventAction("package_delete")
            .eventOutcome("success")
            .field("repository.name", repo)
            .field("package.path", packagePath)
            .field("log.source", "application")
            .log();
    }

    /**
     * Move data when repository is renamed (legacy YAML-only lookup).
     * See {@link #move(RepositoryName, RepositoryName, CrudRepoSettings)}.
     * @param rname Repository name
     * @param nname New repository name
     * @return Completable action of the move operation
     */
    public CompletionStage<Void> move(final RepositoryName rname, final RepositoryName nname) {
        return this.move(rname, nname, null);
    }

    /**
     * Move data when repository is renamed: from location by the old name to
     * location with new name. Resolves storage with the DB fallback.
     * @param rname Repository name
     * @param nname New repository name
     * @param crs Repository settings CRUD for the DB fallback, nullable
     * @return Completable action of the move operation
     */
    public CompletionStage<Void> move(
        final RepositoryName rname, final RepositoryName nname, final CrudRepoSettings crs
    ) {
        final Key repo = RepoData.repoRoot(rname.toString());
        final Key nrepo = RepoData.repoRoot(nname.toString());
        return this.repoStorage(rname, crs)
            .thenCompose(
                asto ->
                    RepoData.subtree(asto, repo)
                        .thenCompose(
                            list -> {
                                final List<Key> relative = new ArrayList<>(list.size());
                                final int cut = repo.string().length() + 1;
                                for (final Key key : list) {
                                    relative.add(new Key.From(key.string().substring(cut)));
                                }
                                return new Copy(new SubStorage(repo, asto), relative)
                                    .copy(new SubStorage(nrepo, asto));
                            }
                        ).thenCompose(nothing -> RepoData.deleteTree(asto, repo))
                        .thenAccept(
                            nothing ->
                                EcsLogger.info(RepoData.LOGGER)
                                    .message("Moved data from repository (" + repo.toString() + " -> " + nrepo.toString() + ")")
                                    .eventCategory("file")
                                    .eventAction("data_move")
                                    .eventOutcome("success")
                                    .field("log.source", "application")
                                    .log()
                        )
            );
    }

    /**
     * Obtain storage from repository settings (YAML config file).
     * @param rname Repository name
     * @return Abstract storage
     */
    public CompletionStage<Storage> repoStorage(final RepositoryName rname) {
        return new ConfigFile(String.format("%s.yaml", rname.toString()))
            .valueFrom(this.configStorage)
            .thenCompose(Content::asStringFuture)
            .thenCompose(val -> {
                final YamlMapping yaml;
                try {
                    yaml = Yaml.createYamlInput(val).readYamlMapping();
                } catch (IOException err) {
                    throw new PanteraIOException(err);
                }
                YamlNode node = yaml.yamlMapping("repo").value(RepoData.STORAGE);
                final CompletionStage<Storage> res;
                if (node == null) {
                    res = CompletableFuture.failedFuture(
                        new NoStorageConfigured(rname.toString())
                    );
                } else if (node instanceof Scalar) {
                    res = this.aliasStorage(rname, ((Scalar) node).value());
                } else if (node instanceof YamlMapping) {
                    res = CompletableFuture.completedStage(
                        this.storagesCache.storage((YamlMapping) node)
                    );
                } else {
                    res = CompletableFuture.failedFuture(
                        new IllegalStateException(
                            String.format("Invalid storage config: %s", node)
                        )
                    );
                }
                return res;

            });
    }

    /**
     * Obtain storage with DB fallback. Tries YAML config first,
     * then falls back to reading from CrudRepoSettings (DB). An inline
     * storage mapping and a storage alias name are both supported.
     * @param rname Repository name
     * @param crs Repository settings CRUD (DB fallback)
     * @return Abstract storage
     */
    public CompletionStage<Storage> repoStorage(
        final RepositoryName rname, final CrudRepoSettings crs
    ) {
        return this.repoStorage(rname).exceptionallyCompose(err -> {
            if (crs == null) {
                return CompletableFuture.failedFuture(err);
            }
            return CompletableFuture.supplyAsync(() -> {
                if (!crs.exists(rname)) {
                    throw new CompletionException(err);
                }
                final JsonStructure config = crs.value(rname);
                if (config == null) {
                    throw new IllegalStateException("Repository not found: " + rname);
                }
                final javax.json.JsonObject jobj = config.asJsonObject();
                final javax.json.JsonObject repo = jobj.containsKey("repo")
                    ? jobj.getJsonObject("repo") : jobj;
                if (!repo.containsKey(RepoData.STORAGE)) {
                    throw new NoStorageConfigured(rname.toString());
                }
                return repo.get(RepoData.STORAGE);
            }, this.blocking).thenCompose(storage -> {
                if (storage.getValueType() == JsonValue.ValueType.STRING) {
                    return this.aliasStorage(
                        rname, ((javax.json.JsonString) storage).getString()
                    );
                }
                final YamlMapping storageYaml =
                    new Json2Yaml().apply(storage.asJsonObject().toString());
                return CompletableFuture.completedStage(
                    this.storagesCache.storage(storageYaml)
                );
            });
        });
    }

    /**
     * Resolve a storage alias for a repository the way the serving path
     * does: the database aliases (global and the repository's own, see
     * {@code DbRepositories}) first, then the {@code _storages.yaml} files.
     * @param rname Repository name
     * @param alias Alias name
     * @return Storage
     */
    private CompletionStage<Storage> aliasStorage(
        final RepositoryName rname, final String alias
    ) {
        return CompletableFuture.supplyAsync(
            () -> this.dbAliases.apply(rname.toString()), this.blocking
        )
            .thenCompose(records -> {
                final boolean known = records.stream().anyMatch(
                    rec -> alias.equals(rec.getString("name", null))
                );
                if (known) {
                    return CompletableFuture.completedStage(
                        DbStorageByAlias.from(records).storage(this.storagesCache, alias)
                    );
                }
                return new AliasSettings(this.configStorage).find(
                    new Key.From(rname.toString())
                ).thenApply(aliases -> aliases.storage(this.storagesCache, alias));
            });
    }

    /**
     * The root key of a repository's data, refusing names that would
     * address anything other than one top-level directory.
     * @param repo Repository name
     * @return Key of the repository directory
     */
    private static Key repoRoot(final String repo) {
        if (repo == null || repo.isBlank() || ".".equals(repo) || "..".equals(repo)
            || repo.contains("/") || repo.contains("\\")) {
            throw new IllegalArgumentException("Invalid repository name: " + repo);
        }
        return new Key.From(repo);
    }

    /**
     * Keys of a path subtree: {@code root} itself and the keys under
     * {@code root/}. A listing is a raw prefix scan on some storages, so
     * keys that only share the string prefix are filtered out.
     * @param asto Storage
     * @param root Subtree root
     * @return Keys in the subtree
     */
    private static CompletableFuture<Collection<Key>> subtree(
        final Storage asto, final Key root
    ) {
        final String prefix = root.string();
        final String dir = prefix + "/";
        return asto.list(root).thenApply(keys -> {
            final List<Key> inside = new ArrayList<>(keys.size());
            for (final Key key : keys) {
                final String str = key.string();
                if (str.equals(prefix) || str.startsWith(dir)) {
                    inside.add(key);
                }
            }
            return inside;
        });
    }

    /**
     * Delete a path subtree.
     * @param asto Storage
     * @param root Subtree root
     * @return Number of keys deleted
     */
    private static CompletableFuture<Integer> deleteTree(final Storage asto, final Key root) {
        return RepoData.subtree(asto, root).thenCompose(keys -> {
            CompletableFuture<Void> res = CompletableFuture.completedFuture(null);
            for (final Key key : keys) {
                res = res.thenCompose(nothing -> asto.delete(key));
            }
            return res.thenApply(nothing -> keys.size());
        });
    }

    /**
     * Unwrap completion wrappers.
     * @param err Failure
     * @return Root cause
     */
    private static Throwable unwrap(final Throwable err) {
        Throwable cause = err;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * The repository config has no storage section (a group repository).
     */
    private static final class NoStorageConfigured extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        NoStorageConfigured(final String repo) {
            super("No storage config in repository: " + repo);
        }
    }
}
