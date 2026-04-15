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
import com.auto1.pantera.http.log.EcsLogger;

import com.auto1.pantera.misc.Json2Yaml;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.json.JsonStructure;

/**
 * Repository data management.
 */
public final class RepoData {
    /**
     * Key 'storage' inside json-object.
     */
    private static final String STORAGE = "storage";

    /**
     * Repository settings storage.
     */
    private final Storage configStorage;

    /**
     * Storages cache.
     */
    private final StoragesCache storagesCache;

    /**
     * Ctor.
     *
     * @param configStorage Repository settings storage
     * @param storagesCache Storages cache
     */
    public RepoData(final Storage configStorage, final StoragesCache storagesCache) {
        this.configStorage = configStorage;
        this.storagesCache = storagesCache;
    }

    /**
     * Remove data from the repository.
     * @param rname Repository name
     * @return Completable action of the remove operation
     */
    public CompletionStage<Void> remove(final RepositoryName rname) {
        final String repo = rname.toString();
        return this.repoStorage(rname)
            .thenCompose(
                asto ->
                    asto
                        .deleteAll(new Key.From(repo))
                        .thenAccept(
                            nothing ->
                                EcsLogger.info("com.auto1.pantera.settings")
                                    .message("Removed data from repository")
                                    .eventCategory("web")
                                    .eventAction("data_remove")
                                    .eventOutcome("success")
                                    .field("repository.name", repo)
                                    .log()
                        )
            );
    }

    /**
     * Delete artifact from repository storage.
     * @param rname Repository name
     * @param artifactPath Path to the artifact within repository storage
     * @return Completable action of the delete operation, returns true if deleted, false if not found
     */
    public CompletionStage<Boolean> deleteArtifact(final RepositoryName rname, final String artifactPath) {
        final String repo = rname.toString();
        final Key artifactKey = new Key.From(repo, artifactPath);
        return this.repoStorage(rname)
            .thenCompose(asto -> asto.exists(artifactKey)
                .thenCompose(exists -> {
                    if (!exists) {
                        // Check if it's a directory by listing children
                        return asto.list(artifactKey)
                            .thenCompose(keys -> {
                                if (keys.isEmpty()) {
                                    return CompletableFuture.completedFuture(false);
                                }
                                // Delete all files under this path
                                return asto.deleteAll(artifactKey)
                                    .thenApply(nothing -> {
                                        EcsLogger.info("com.auto1.pantera.settings")
                                            .message("Deleted artifact directory from repository, " + keys.size() + " files removed")
                                            .eventCategory("web")
                                            .eventAction("artifact_delete")
                                            .eventOutcome("success")
                                            .field("repository.name", repo)
                                            .field("file.path", artifactPath)
                                            .log();
                                        return true;
                                    });
                            });
                    }
                    // Single file - delete it
                    return asto.delete(artifactKey)
                        .thenApply(nothing -> {
                            EcsLogger.info("com.auto1.pantera.settings")
                                .message("Deleted artifact file from repository")
                                .eventCategory("web")
                                .eventAction("artifact_delete")
                                .eventOutcome("success")
                                .field("repository.name", repo)
                                .field("file.path", artifactPath)
                                .log();
                            return true;
                        });
                })
            );
    }

    /**
     * Delete a package folder (and its contents) from repository storage.
     * @param rname Repository name
     * @param packagePath Path to the package folder within repository storage
     * @return Completable action returning true if deletion happened, false if nothing found
     */
    public CompletionStage<Boolean> deletePackageFolder(final RepositoryName rname, final String packagePath) {
        final String repo = rname.toString();
        final Key folder = new Key.From(repo, packagePath);
        return this.repoStorage(rname)
            .thenCompose(asto ->
                asto.exists(folder).thenCompose(exists -> {
                    final CompletionStage<Boolean> deletion;
                    if (exists) {
                        deletion = asto.deleteAll(folder).thenApply(
                            nothing -> {
                                this.logPackageDelete(repo, packagePath);
                                return true;
                            }
                        );
                    } else {
                        deletion = asto.list(folder)
                            .thenCompose(children -> {
                                if (children.isEmpty()) {
                                    return CompletableFuture.completedFuture(false);
                                }
                                return asto.deleteAll(folder)
                                    .thenApply(nothing -> {
                                        this.logPackageDelete(repo, packagePath);
                                        return true;
                                    });
                            });
                    }
                    return deletion;
                })
            );
    }

    private void logPackageDelete(final String repo, final String packagePath) {
        EcsLogger.info("com.auto1.pantera.settings")
            .message("Deleted package folder from repository")
            .eventCategory("web")
            .eventAction("package_delete")
            .eventOutcome("success")
            .field("repository.name", repo)
            .field("package.path", packagePath)
            .log();
    }

    /**
     * Move data when repository is renamed: from location by the old name to location with
     * new name.
     * @param rname Repository name
     * @param nname New repository name
     * @return Completable action of the remove operation
     */
    public CompletionStage<Void> move(final RepositoryName rname, final RepositoryName nname) {
        final Key repo = new Key.From(rname.toString());
        final Key nrepo = new Key.From(nname.toString());
        return this.repoStorage(rname)
            .thenCompose(
                asto ->
                    new SubStorage(repo, asto)
                        .list(Key.ROOT)
                        .thenCompose(
                            list ->
                                new Copy(new SubStorage(repo, asto), list)
                                    .copy(new SubStorage(nrepo, asto))
                        ).thenCompose(nothing -> asto.deleteAll(new Key.From(repo)))
                        .thenAccept(
                            nothing ->
                                EcsLogger.info("com.auto1.pantera.settings")
                                    .message("Moved data from repository (" + repo.toString() + " -> " + nrepo.toString() + ")")
                                    .eventCategory("web")
                                    .eventAction("data_move")
                                    .eventOutcome("success")
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
                if (node instanceof Scalar) {
                    res = new AliasSettings(this.configStorage).find(
                        new Key.From(rname.toString())
                    ).thenApply(
                        aliases -> aliases.storage(
                            this.storagesCache,
                            ((Scalar) node).value()
                        )
                    );
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
     * then falls back to reading from CrudRepoSettings (DB).
     * @param rname Repository name
     * @param crs Repository settings CRUD (DB fallback)
     * @return Abstract storage
     */
    public CompletionStage<Storage> repoStorage(
        final RepositoryName rname, final CrudRepoSettings crs
    ) {
        return this.repoStorage(rname).exceptionallyCompose(err -> {
            if (crs == null || !crs.exists(rname)) {
                return CompletableFuture.failedFuture(err);
            }
            return CompletableFuture.supplyAsync(() -> {
                final JsonStructure config = crs.value(rname);
                if (config == null) {
                    throw new IllegalStateException("Repository not found: " + rname);
                }
                final javax.json.JsonObject jobj = config.asJsonObject();
                final javax.json.JsonObject repo = jobj.containsKey("repo")
                    ? jobj.getJsonObject("repo") : jobj;
                if (!repo.containsKey(RepoData.STORAGE)) {
                    throw new IllegalStateException(
                        "No storage config in repository: " + rname
                    );
                }
                final javax.json.JsonObject storageJson =
                    repo.getJsonObject(RepoData.STORAGE);
                final YamlMapping storageYaml =
                    new Json2Yaml().apply(storageJson.toString());
                return this.storagesCache.storage(storageYaml);
            });
        });
    }
}
