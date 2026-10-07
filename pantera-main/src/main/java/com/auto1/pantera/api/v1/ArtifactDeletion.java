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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.settings.RepoPathRemoval;
import java.util.concurrent.CompletableFuture;

/**
 * The one artifact delete of Pantera, shared by the management API
 * ({@code DELETE /api/v1/repositories/:name/artifacts|packages}) and the
 * repository-path {@code DELETE /<repo>/<path>}: the storage delete, then
 * the cascade that keeps everything derived from storage consistent -- the
 * tree-view metadata cache, the search index (matched on the storage path
 * the rows were indexed from) and the format's own metadata of a hosted
 * repository ({@link FormatDeleteHooks}).
 *
 * <p>The cascade also runs when storage no longer holds the path, so stale
 * index rows and metadata are cleared; a path is "not found" only when it
 * was neither stored nor indexed. The cascade is best-effort: a failed step
 * is logged and the delete still counts.</p>
 *
 * <p>Authorization and auditing are the caller's: each entry point knows
 * its principal and request context. A caller serving an HTTP request
 * passes the request's headers: the storage and index continuations run on
 * pooled threads, and every application log of the delete binds the
 * request's {@code X-Pantera-Ctx-*} fields to the MDC first.</p>
 *
 * @since 2.2.10
 */
public final class ArtifactDeletion {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.api.v1";

    /**
     * Search index.
     */
    private final ArtifactIndex index;

    /**
     * Tree-view storage metadata cache.
     */
    private final StorageMetaCache meta;

    /**
     * Format metadata upkeep.
     */
    private final FormatDeleteHooks hooks;

    /**
     * Ctor.
     * @param index Search index; {@code null} means none
     * @param meta Tree-view storage metadata cache, shared with the
     *  management API's tree listing
     */
    public ArtifactDeletion(final ArtifactIndex index, final StorageMetaCache meta) {
        this.index = index == null ? ArtifactIndex.NOP : index;
        this.meta = meta;
        this.hooks = new FormatDeleteHooks();
    }

    /**
     * Delete a path of a repository and cascade.
     * @param repo Repository name
     * @param type Repository type (format hooks run for hosted types only)
     * @param storage Repository storage, repository-relative keys
     * @param path Repository-relative storage path
     * @param mode File, folder or either
     * @return True when the path was stored or indexed
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public CompletableFuture<Boolean> delete(
        final String repo, final String type, final Storage storage,
        final String path, final RepoPathRemoval.Mode mode
    ) {
        return this.delete(repo, type, storage, path, mode, Headers.EMPTY);
    }

    /**
     * Delete a path of a repository and cascade, logging under the
     * request's context.
     * @param repo Repository name
     * @param type Repository type (format hooks run for hosted types only)
     * @param storage Repository storage, repository-relative keys
     * @param path Repository-relative storage path
     * @param mode File, folder or either
     * @param context Request headers carrying the {@code X-Pantera-Ctx-*}
     *  request-context fields
     * @return True when the path was stored or indexed
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public CompletableFuture<Boolean> delete(
        final String repo, final String type, final Storage storage,
        final String path, final RepoPathRemoval.Mode mode, final Headers context
    ) {
        return new RepoPathRemoval(storage, context).remove(repo, path, mode).thenCompose(
            outcome -> this.cascade(
                repo, type, storage, path,
                mode == RepoPathRemoval.Mode.FOLDER || outcome == RepoPathRemoval.Outcome.TREE,
                context
            ).thenApply(indexed -> outcome.found() || indexed > 0)
        );
    }

    /**
     * The search-index and tree-view half of a delete an adapter performed
     * natively (pypi, debian and rpm keep their own {@code DELETE}): removes
     * the index rows of the deleted file's storage path and drops the tree
     * view's cached metadata of it. Never fails: a failed index step is
     * logged and counts as no rows.
     * @param repo Repository name
     * @param path Repository-relative storage path the adapter deleted
     * @param context Request headers carrying the {@code X-Pantera-Ctx-*}
     *  request-context fields
     * @return Number of search index rows removed
     */
    public CompletableFuture<Integer> afterNativeDelete(
        final String repo, final String path, final Headers context
    ) {
        this.invalidateTreeView(repo, path, false);
        return this.unindex(repo, path, context);
    }

    /**
     * Whether a client-supplied path escapes its repository namespace
     * ({@code .}/{@code ..} segments, NUL or backslash) and must be refused
     * -- the check every management-API artifact route applies.
     * @param path Client-supplied path
     * @return True when the path is unsafe
     */
    public boolean unsafe(final String path) {
        return ArtifactHandler.traversedPath(path);
    }

    /**
     * Keep everything derived from storage consistent with a delete.
     * Never fails: each step logs its own failure.
     * @param repo Repository name
     * @param type Repository type
     * @param storage Repository storage
     * @param path Deleted path
     * @param folder Whether a folder was deleted
     * @param context Request headers carrying the request-context fields
     * @return Number of search index rows removed (0 when that step failed)
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletableFuture<Integer> cascade(
        final String repo, final String type, final Storage storage,
        final String path, final boolean folder, final Headers context
    ) {
        this.invalidateTreeView(repo, path, folder);
        final CompletableFuture<Integer> rows = this.unindex(repo, path, context);
        // Started inside a stage so a synchronous throw is handled like a
        // failed future.
        final CompletableFuture<Void> format = CompletableFuture.<Void>completedFuture(null)
            .thenCompose(nothing -> this.hooks.afterDelete(type, storage, repo, path))
            .<Void>handle((nothing, err) -> {
                if (err != null) {
                    RequestContextHeaders.bindToMdc(context);
                    ArtifactDeletion.cascadeFailed(
                        "Deleted from storage but the " + type
                            + " metadata could not be updated",
                        repo, path, err
                    );
                }
                return null;
            });
        return rows.thenCombine(format, (count, nothing) -> count);
    }

    /**
     * Remove the search index rows of a storage path (the path itself and
     * its subtree, on {@code name} or {@code path_prefix}). Never fails: a
     * failure is logged and counts as no rows.
     * @param repo Repository name
     * @param path Repository-relative storage path
     * @param context Request headers carrying the {@code X-Pantera-Ctx-*}
     *  request-context fields, bound to the MDC before a failure is logged
     * @return Number of rows removed
     */
    public CompletableFuture<Integer> unindex(
        final String repo, final String path, final Headers context
    ) {
        return CompletableFuture.<Void>completedFuture(null)
            .thenCompose(nothing -> this.index.removeByPath(repo, path))
            .handle((count, err) -> {
                if (err != null) {
                    RequestContextHeaders.bindToMdc(context);
                    ArtifactDeletion.cascadeFailed(
                        "Deleted from storage but the search index cascade failed",
                        repo, path, err
                    );
                    return 0;
                }
                return count == null ? 0 : count;
            });
    }

    /**
     * Drop the tree view's cached storage metadata of a deleted path.
     * @param repo Repository name
     * @param path Deleted path
     * @param folder Whether a folder was deleted
     */
    private void invalidateTreeView(final String repo, final String path, final boolean folder) {
        if (folder) {
            this.meta.invalidatePrefix(repo, path);
        } else {
            this.meta.invalidate(repo, path);
        }
    }

    /**
     * Log a failed cascade step.
     * @param message Message
     * @param repo Repository name
     * @param path Deleted path
     * @param err Failure
     */
    private static void cascadeFailed(
        final String message, final String repo, final String path, final Throwable err
    ) {
        EcsLogger.warn(ArtifactDeletion.LOGGER)
            .message(message)
            .eventCategory("database")
            .eventAction("delete_cascade_failed")
            .eventOutcome("failure")
            .field("repository.name", repo)
            .field("file.path", path)
            .error(err)
            .field("log.source", "application")
            .log();
    }
}
