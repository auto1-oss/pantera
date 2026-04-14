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

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.trace.MdcPropagation;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import com.auto1.pantera.settings.RepoData;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * PyPI yank/unyank API handler.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>POST /api/v1/pypi/:repo/:package/:version/yank   — body: {@code {"reason":"optional"}}</li>
 *   <li>POST /api/v1/pypi/:repo/:package/:version/unyank — no body</li>
 * </ul>
 *
 * <p>Both endpoints iterate over all distribution files ({@code .whl}, {@code .tar.gz},
 * {@code .zip}, {@code .egg}) stored under {@code {package}/{version}/} in the
 * repository and apply the corresponding {@link PypiSidecar} operation.</p>
 *
 * @since 2.1.0
 */
public final class PypiHandler {

    /**
     * Distribution file suffixes that carry PyPI sidecar metadata.
     */
    private static final List<String> DIST_SUFFIXES =
        List.of(".whl", ".tar.gz", ".zip", ".egg");

    /**
     * Repository settings CRUD.
     */
    private final CrudRepoSettings crs;

    /**
     * Repository data (storage resolution).
     */
    private final RepoData repoData;

    /**
     * Ctor.
     * @param crs  Repository settings CRUD
     * @param repoData Repository data management
     */
    public PypiHandler(final CrudRepoSettings crs, final RepoData repoData) {
        this.crs = crs;
        this.repoData = repoData;
    }

    /**
     * Register yank/unyank routes on the router.
     * Both routes are placed after the JWT filter and therefore protected.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        router.post("/api/v1/pypi/:repo/:package/:version/yank")
            .handler(this::yankHandler);
        router.post("/api/v1/pypi/:repo/:package/:version/unyank")
            .handler(this::unyankHandler);
    }

    /**
     * POST /api/v1/pypi/:repo/:package/:version/yank.
     * @param ctx Routing context
     */
    private void yankHandler(final RoutingContext ctx) {
        final String repo = ctx.pathParam("repo");
        final String pkg = ctx.pathParam("package");
        final String version = ctx.pathParam("version");
        final String reason = extractReason(ctx);
        ctx.vertx().<Void>executeBlocking(
            MdcPropagation.withMdc(() -> {
                this.applyYank(repo, pkg, version, reason);
                return null;
            }),
            false
        ).onSuccess(
            ignored -> {
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("PyPI yank applied")
                    .eventCategory("web")
                    .eventAction("yank")
                    .eventOutcome("success")
                    .field("repository.name", repo)
                    .field("package.name", pkg)
                    .field("package.version", version)
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        ).onFailure(
            err -> {
                EcsLogger.error("com.auto1.pantera.api.v1")
                    .message("PyPI yank failed")
                    .eventCategory("web")
                    .eventAction("yank")
                    .eventOutcome("failure")
                    .field("repository.name", repo)
                    .field("package.name", pkg)
                    .field("package.version", version)
                    .error(err)
                    .log();
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            }
        );
    }

    /**
     * POST /api/v1/pypi/:repo/:package/:version/unyank.
     * @param ctx Routing context
     */
    private void unyankHandler(final RoutingContext ctx) {
        final String repo = ctx.pathParam("repo");
        final String pkg = ctx.pathParam("package");
        final String version = ctx.pathParam("version");
        ctx.vertx().<Void>executeBlocking(
            MdcPropagation.withMdc(() -> {
                this.applyUnyank(repo, pkg, version);
                return null;
            }),
            false
        ).onSuccess(
            ignored -> {
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("PyPI unyank applied")
                    .eventCategory("web")
                    .eventAction("unyank")
                    .eventOutcome("success")
                    .field("repository.name", repo)
                    .field("package.name", pkg)
                    .field("package.version", version)
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        ).onFailure(
            err -> {
                EcsLogger.error("com.auto1.pantera.api.v1")
                    .message("PyPI unyank failed")
                    .eventCategory("web")
                    .eventAction("unyank")
                    .eventOutcome("failure")
                    .field("repository.name", repo)
                    .field("package.name", pkg)
                    .field("package.version", version)
                    .error(err)
                    .log();
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            }
        );
    }

    /**
     * Resolve repo-scoped storage and apply yank to all distribution files.
     * This method is called from a blocking executor thread.
     * @param repo Repository name
     * @param pkg Package name
     * @param version Version string
     * @param reason Yank reason, may be null
     */
    private void applyYank(final String repo, final String pkg, final String version,
        final String reason) {
        final Storage scoped = this.resolveScoped(repo);
        final Collection<Key> files = distFiles(scoped, pkg, version);
        final List<CompletableFuture<Void>> futures = new ArrayList<>(files.size());
        for (final Key file : files) {
            futures.add(PypiSidecar.yank(scoped, file, reason));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Resolve repo-scoped storage and apply unyank to all distribution files.
     * This method is called from a blocking executor thread.
     * @param repo Repository name
     * @param pkg Package name
     * @param version Version string
     */
    private void applyUnyank(final String repo, final String pkg, final String version) {
        final Storage scoped = this.resolveScoped(repo);
        final Collection<Key> files = distFiles(scoped, pkg, version);
        final List<CompletableFuture<Void>> futures = new ArrayList<>(files.size());
        for (final Key file : files) {
            futures.add(PypiSidecar.unyank(scoped, file));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Resolve the repo-scoped storage (equivalent to what PySlice receives).
     * {@link RepoData#repoStorage} returns the raw underlying storage; wrapping it in
     * a {@link SubStorage} with the repo name as prefix gives the same view that
     * the PyPI adapter uses, so sidecar keys are relative to the repo root.
     * @param repo Repository name
     * @return Repo-scoped storage
     */
    private Storage resolveScoped(final String repo) {
        final RepositoryName rname = new RepositoryName.Simple(repo);
        final Storage raw = this.repoData.repoStorage(rname, this.crs)
            .toCompletableFuture().join();
        return new SubStorage(new Key.From(repo), raw);
    }

    /**
     * List distribution files under {@code {pkg}/{version}/} in the scoped storage.
     * Only files whose names end with a recognised distribution suffix are returned.
     * @param scoped Repo-scoped storage
     * @param pkg Package name
     * @param version Version string
     * @return Relative keys for matching distribution files
     */
    private static Collection<Key> distFiles(final Storage scoped, final String pkg,
        final String version) {
        final Key prefix = new Key.From(pkg, version);
        final Collection<Key> all = scoped.list(prefix).join();
        final List<Key> result = new ArrayList<>();
        for (final Key key : all) {
            final String name = key.string();
            for (final String suffix : DIST_SUFFIXES) {
                if (name.endsWith(suffix)) {
                    result.add(key);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Extract the optional {@code reason} field from the JSON request body.
     * Returns {@code null} if the body is absent, blank, or the field is missing.
     * @param ctx Routing context
     * @return Reason string or null
     */
    private static String extractReason(final RoutingContext ctx) {
        final String body = ctx.body().asString();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            final JsonObject json = new JsonObject(body);
            final String reason = json.getString("reason");
            return reason == null || reason.isBlank() ? null : reason;
        } catch (final Exception ex) {
            return null;
        }
    }
}
