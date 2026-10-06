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

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Runs the items of {@code POST /api/v1/cooldown/unblock} one after another:
 * per-repository write check, repository type lookup, artifact name
 * normalisation, then the shared single-unblock path. Every outcome is
 * audited; no failure of one item ever stops the next or fails the request.
 * Pure against its collaborators, so it is unit-tested without Vert.x.
 * @since 2.2.10
 */
final class BulkUnblocker {

    /**
     * Resolves a repository name to its type; throws
     * {@link IllegalArgumentException} when unknown.
     */
    private final Function<String, String> types;

    /**
     * Whether the caller may write the named repository.
     */
    private final Predicate<String> writable;

    /**
     * The single-unblock path (DB write + cache invalidations).
     */
    private final Release release;

    /**
     * Audit sink.
     */
    private final Audit audit;

    /**
     * Executor for the blocking lookups.
     */
    private final Executor executor;

    /**
     * Ctor.
     * @param types Repository type lookup
     * @param writable Per-repository write grant of the caller
     * @param release Single-unblock path
     * @param audit Audit sink
     * @param executor Executor for blocking work
     * @checkstyle ParameterNumberCheck (3 lines)
     */
    BulkUnblocker(final Function<String, String> types, final Predicate<String> writable,
        final Release release, final Audit audit, final Executor executor) {
        this.types = types;
        this.writable = writable;
        this.release = release;
        this.audit = audit;
        this.executor = executor;
    }

    /**
     * Process the items in order.
     * @param items De-duplicated request items
     * @param actor Authenticated principal
     * @return Outcome, never exceptional
     */
    CompletableFuture<Outcome> run(final List<BulkUnblockRequest.Item> items, final String actor) {
        final Outcome out = new Outcome(new JsonArray(), new JsonArray());
        final Set<String> done = new HashSet<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final BulkUnblockRequest.Item item : items) {
            chain = chain.thenCompose(ignored -> this.step(item, actor, out, done));
        }
        return chain.thenApply(ignored -> out);
    }

    private CompletableFuture<Void> step(final BulkUnblockRequest.Item item, final String actor,
        final Outcome out, final Set<String> done) {
        CompletableFuture<Void> res;
        try {
            res = CompletableFuture
                .supplyAsync(() -> this.process(item, actor, done), this.executor)
                .thenCompose(Function.identity());
        } catch (final RuntimeException ex) {
            // The executor refused the task (bounded queue, AbortPolicy):
            // report this item rather than lose it.
            res = CompletableFuture.failedFuture(ex);
        }
        return res.handle((ok, err) -> {
            if (err == null) {
                if (ok == null) {
                    out.unblocked().add(BulkUnblocker.row(item));
                }
            } else {
                out.failed().add(BulkUnblocker.row(item).put("reason", BulkUnblocker.reason(err)));
            }
            return null;
        });
    }

    /**
     * One item on the executor.
     * @return Completed with null when released, completed with a marker when
     *  skipped as a duplicate, failed with the reason otherwise
     */
    private CompletableFuture<Void> process(final BulkUnblockRequest.Item item,
        final String actor, final Set<String> done) {
        final String name = item.repo();
        final Map<String, Object> details = new HashMap<>();
        details.put("package.name", item.artifact());
        details.put("package.version", item.version());
        details.put("bulk", true);
        if (!this.writable.test(name)) {
            details.put("error", "forbidden");
            this.audit.record(name, details, false);
            return CompletableFuture.failedFuture(new IllegalArgumentException("forbidden"));
        }
        final String type;
        try {
            type = this.types.apply(name);
        } catch (final RuntimeException ex) {
            details.put("error", String.valueOf(ex.getMessage()));
            this.audit.record(name, details, false);
            return CompletableFuture.failedFuture(ex);
        }
        if (type == null || type.isEmpty()) {
            details.put("error", "Repository type is required");
            this.audit.record(name, details, false);
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository type is required")
            );
        }
        final String artifact = new UnblockArtifactName(type, item.artifact()).value();
        if (!done.add(String.join("\n", name, artifact, item.version()))) {
            return CompletableFuture.completedFuture(null).thenApply(ignored -> {
                throw new Duplicate();
            });
        }
        details.put("repository.type", type);
        details.put("package.name", artifact);
        return this.release.apply(name, type, artifact, item.version())
            .whenComplete((ignored, err) -> {
                if (err != null) {
                    details.put("error", BulkUnblocker.reason(err));
                }
                this.audit.record(name, details, err == null);
            });
    }

    private static JsonObject row(final BulkUnblockRequest.Item item) {
        return new JsonObject()
            .put("repo", item.repo())
            .put("artifact", item.artifact())
            .put("version", item.version());
    }

    private static String reason(final Throwable err) {
        final Throwable cause = err instanceof CompletionException && err.getCause() != null
            ? err.getCause() : err;
        return String.valueOf(cause.getMessage());
    }

    /**
     * Marker: the same (repo, normalised artifact, version) was already handled.
     */
    private static final class Duplicate extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * The single-unblock path.
     */
    @FunctionalInterface
    interface Release {
        /**
         * Release one version and invalidate the caches.
         * @param repo Repository name
         * @param type Repository type
         * @param artifact Normalised artifact name
         * @param version Version
         * @return Completes when released
         */
        CompletableFuture<Void> apply(String repo, String type, String artifact, String version);
    }

    /**
     * Audit sink for one item outcome.
     */
    @FunctionalInterface
    interface Audit {
        /**
         * Record one outcome.
         * @param repo Repository name
         * @param details Structured details (package, version, type, bulk, error)
         * @param success Whether the item was released
         */
        void record(String repo, Map<String, Object> details, boolean success);
    }

    /**
     * Result arrays, in request order.
     * @param unblocked Released items
     * @param failed Failed items with a reason
     */
    record Outcome(JsonArray unblocked, JsonArray failed) {
    }
}
