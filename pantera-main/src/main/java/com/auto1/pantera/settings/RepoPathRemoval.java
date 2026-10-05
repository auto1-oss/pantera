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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Storage half of a path delete: removes a file or a <em>path subtree</em>
 * (the key itself and the keys under {@code <key>/}) from one storage.
 *
 * <p>Storage listings are raw prefix scans on S3 and in memory, so the
 * subtree is filtered: deleting the folder {@code com/acme/lib} never
 * removes {@code com/acme/lib-extra/...}.</p>
 *
 * @since 2.2.10
 */
public final class RepoPathRemoval {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.settings";

    /**
     * Storage the keys are deleted from.
     */
    private final Storage storage;

    /**
     * Ctor.
     * @param storage Storage the keys are deleted from
     */
    public RepoPathRemoval(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Delete a path.
     * @param repo Repository name (for logs)
     * @param path Path of the file or directory, relative to the storage
     * @param mode What the path is expected to be
     * @return What was deleted
     */
    public CompletableFuture<Outcome> remove(
        final String repo, final String path, final Mode mode
    ) {
        final Key key = new Key.From(path);
        final CompletableFuture<Outcome> res;
        if (mode == Mode.FOLDER) {
            res = this.folder(repo, path, key);
        } else {
            res = this.storage.exists(key).thenCompose(
                exists -> {
                    final CompletableFuture<Outcome> step;
                    if (exists) {
                        step = this.file(repo, path, key);
                    } else if (mode == Mode.AUTO) {
                        step = this.folder(repo, path, key);
                    } else {
                        step = CompletableFuture.completedFuture(Outcome.NONE);
                    }
                    return step;
                }
            );
        }
        return res;
    }

    /**
     * Keys of a path subtree: {@code root} itself and the keys under
     * {@code root/}. A listing is a raw prefix scan on some storages, so
     * keys that only share the string prefix are filtered out.
     * @param root Subtree root
     * @return Keys in the subtree
     */
    public CompletableFuture<Collection<Key>> subtree(final Key root) {
        final String prefix = root.string();
        final String dir = prefix + "/";
        return this.storage.list(root).thenApply(keys -> {
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
     * @param root Subtree root
     * @return Number of keys deleted
     */
    public CompletableFuture<Integer> deleteTree(final Key root) {
        return this.subtree(root).thenCompose(keys -> {
            CompletableFuture<Void> res = CompletableFuture.completedFuture(null);
            for (final Key key : keys) {
                res = res.thenCompose(nothing -> this.storage.delete(key));
            }
            return res.thenApply(nothing -> keys.size());
        });
    }

    /**
     * Delete one file.
     * @param repo Repository name
     * @param path Path
     * @param key Key
     * @return Outcome
     */
    private CompletableFuture<Outcome> file(final String repo, final String path, final Key key) {
        return this.storage.delete(key).thenApply(
            nothing -> {
                EcsLogger.info(RepoPathRemoval.LOGGER)
                    .message("Deleted artifact file from repository")
                    .eventCategory("file")
                    .eventAction("artifact_delete")
                    .eventOutcome("success")
                    .field("repository.name", repo)
                    .field("file.path", path)
                    .field("log.source", "application")
                    .log();
                return Outcome.FILE;
            }
        );
    }

    /**
     * Delete a subtree and the empty directories it leaves behind.
     * @param repo Repository name
     * @param path Path
     * @param key Key
     * @return Outcome
     */
    private CompletableFuture<Outcome> folder(
        final String repo, final String path, final Key key
    ) {
        return this.deleteTree(key).thenCompose(
            removed -> this.storage.deleteEmptyDirectories(key).thenApply(nothing -> removed)
        ).thenApply(
            removed -> {
                final Outcome out;
                if (removed == 0) {
                    out = Outcome.NONE;
                } else {
                    EcsLogger.info(RepoPathRemoval.LOGGER)
                        .message("Deleted directory from repository, " + removed + " files removed")
                        .eventCategory("file")
                        .eventAction("package_delete")
                        .eventOutcome("success")
                        .field("repository.name", repo)
                        .field("file.path", path)
                        .field("log.source", "application")
                        .log();
                    out = Outcome.TREE;
                }
                return out;
            }
        );
    }

    /**
     * What a path is expected to be.
     */
    public enum Mode {
        /**
         * Exactly one file; nothing else is deleted.
         */
        FILE,
        /**
         * A directory: its subtree is deleted.
         */
        FOLDER,
        /**
         * The file when the key exists, otherwise the directory's subtree.
         */
        AUTO
    }

    /**
     * What a delete removed from storage.
     */
    public enum Outcome {
        /**
         * Nothing was stored at the path.
         */
        NONE,
        /**
         * One file.
         */
        FILE,
        /**
         * A subtree of at least one file.
         */
        TREE;

        /**
         * Whether anything was deleted.
         * @return True unless {@link #NONE}
         */
        public boolean found() {
            return this != NONE;
        }
    }
}
