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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.perms.ApiAdminPermission;
import com.auto1.pantera.api.v1.ApiResponse;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Cooldown package inspector routes (admin only).
 * <ul>
 *   <li>GET  /api/v1/cooldown/inspect?repoType=&amp;package=[&amp;repo=] —
 *       per-version cooldown state vs. served visibility, per-repository
 *       cache layers</li>
 *   <li>GET  /api/v1/cooldown/inspect/suggest?q=[&amp;repoType=][&amp;limit=] —
 *       package names matching the typed text, in the form the inspector
 *       accepts</li>
 *   <li>POST /api/v1/cooldown/refresh-package {repoType, package, repo?} —
 *       clear every layer for the package cluster-wide, return
 *       {@code {before, after}}</li>
 * </ul>
 *
 * @since 2.2.9
 */
public final class CooldownInspectResource {

    /**
     * Default number of suggestions.
     */
    private static final int DEFAULT_LIMIT = 20;

    /**
     * Largest number of suggestions.
     */
    private static final int MAX_LIMIT = 50;

    /**
     * Did-you-mean suggestions on an inspection that found nothing.
     */
    private static final int DID_YOU_MEAN = 5;

    /**
     * Longest search text.
     */
    private static final int MAX_QUERY = 200;

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Inspector.
     */
    private final PackageInspector inspector;

    /**
     * Refresher.
     */
    private final PackageRefresher refresher;

    /**
     * Package-name suggestions.
     */
    private final PackageSuggester suggester;

    /**
     * Ctor.
     *
     * @param policy Security policy
     * @param inspector Inspector
     * @param refresher Refresher
     * @param suggester Package-name suggestions
     */
    public CooldownInspectResource(
        final Policy<?> policy, final PackageInspector inspector,
        final PackageRefresher refresher, final PackageSuggester suggester
    ) {
        this.policy = policy;
        this.inspector = inspector;
        this.refresher = refresher;
        this.suggester = suggester;
    }

    /**
     * Register routes.
     *
     * @param router Router
     */
    public void register(final Router router) {
        final AuthzHandler admin = new AuthzHandler(this.policy, ApiAdminPermission.ADMIN);
        router.get("/api/v1/cooldown/inspect").handler(admin).handler(this::inspect);
        router.get("/api/v1/cooldown/inspect/suggest").handler(admin).handler(this::suggest);
        router.post("/api/v1/cooldown/refresh-package").handler(admin).handler(this::refresh);
    }

    /**
     * GET inspect.
     *
     * @param ctx Routing context
     */
    private void inspect(final RoutingContext ctx) {
        final String type = ctx.queryParams().get("repoType");
        final String pkg = ctx.queryParams().get("package");
        final String repo = ctx.queryParams().get("repo");
        if (CooldownInspectResource.blank(type) || CooldownInspectResource.blank(pkg)) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "repoType and package are required");
            return;
        }
        if (!this.repoKnown(ctx, repo)) {
            return;
        }
        final String auth = ctx.request().getHeader("Authorization");
        CompletableFuture.supplyAsync(() -> repo, HandlerExecutor.get())
            .thenCompose(ignored -> this.inspector.inspect(type.trim(), pkg.trim(), repo, auth))
            .thenCompose(this::didYouMean)
            .whenComplete((json, err) -> CooldownInspectResource.send(ctx, json, err));
    }

    /**
     * GET suggest.
     *
     * @param ctx Routing context
     */
    private void suggest(final RoutingContext ctx) {
        final String type = ctx.queryParams().get("repoType");
        final String text = ctx.queryParams().get("q");
        if (CooldownInspectResource.blank(text) || text.length() > MAX_QUERY) {
            ApiResponse.sendError(
                ctx, 400, "BAD_REQUEST", "q is required (at most " + MAX_QUERY + " characters)"
            );
            return;
        }
        final String raw = ctx.queryParams().get("limit");
        final int limit;
        try {
            limit = CooldownInspectResource.blank(raw) ? DEFAULT_LIMIT
                : Math.min(MAX_LIMIT, Integer.parseInt(raw.trim()));
        } catch (final NumberFormatException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "limit must be a number");
            return;
        }
        if (limit < 1) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "limit must be at least 1");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> new JsonObject()
                .put("suggestions", this.suggester.suggest(type, text, limit))
                .put("node", this.inspector.node()),
            HandlerExecutor.get()
        ).whenComplete((json, err) -> CooldownInspectResource.send(ctx, json, err));
    }

    /**
     * Add {@code didYouMean} to an inspection that found nothing under the
     * exact name: no version and no repository serving metadata for it.
     * A failed lookup leaves the inspection as it is.
     *
     * @param json Inspection
     * @return Future of the inspection
     */
    private CompletableFuture<JsonObject> didYouMean(final JsonObject json) {
        if (!CooldownInspectResource.unmatched(json)) {
            return CompletableFuture.completedFuture(json);
        }
        return CompletableFuture.supplyAsync(
            () -> json.put(
                "didYouMean",
                this.suggester.didYouMean(
                    json.getString("repoType"), json.getString("package"), DID_YOU_MEAN
                )
            ),
            HandlerExecutor.get()
        ).exceptionally(err -> {
            EcsLogger.warn("com.auto1.pantera.api.v1.admin")
                .message("Cooldown inspector did-you-mean lookup failed; answering without it")
                .eventCategory("database")
                .eventAction("cooldown_inspect_suggest")
                .eventOutcome("failure")
                .field("package.name", json.getString("package"))
                .field("log.source", "application")
                .error(PackageInspector.cause(err))
                .log();
            return json;
        });
    }

    /**
     * POST refresh-package.
     *
     * @param ctx Routing context
     */
    private void refresh(final RoutingContext ctx) {
        final JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (final io.vertx.core.json.DecodeException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final String type = body == null ? null : body.getString("repoType");
        final String pkg = body == null ? null : body.getString("package");
        final String repo = body == null ? null : body.getString("repo");
        if (CooldownInspectResource.blank(type) || CooldownInspectResource.blank(pkg)) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "repoType and package are required");
            return;
        }
        if (!this.repoKnown(ctx, repo)) {
            return;
        }
        final String auth = ctx.request().getHeader("Authorization");
        final AdminMutation mutation = new AdminMutation(ctx);
        CompletableFuture.supplyAsync(() -> repo, HandlerExecutor.get())
            .thenCompose(ignored -> this.refresher.refresh(type.trim(), pkg.trim(), repo, auth))
            .whenComplete((json, err) -> {
                mutation.record(
                    "cooldown_refresh_package", "COOLDOWN_REFRESH_PACKAGE", pkg,
                    "Admin refresh-package: cleared cache layers for " + pkg
                        + " (repo_type=" + type + ", repo=" + repo + ")"
                        + (json == null ? "" : ", cleared=" + json.getJsonObject("cleared").encode()
                            + ", revalidated=" + json.getJsonArray("revalidated").encode()),
                    Map.of(
                        "repository.type", type,
                        "repository.name", repo == null ? "" : repo,
                        "package.name", pkg
                    ),
                    err == null ? null : PackageInspector.cause(err)
                );
                CooldownInspectResource.send(ctx, json, err);
            });
    }

    /**
     * 404 for a named repository that is not configured.
     *
     * @param ctx Routing context
     * @param repo Repository name, may be null
     * @return True when the request may proceed
     */
    private boolean repoKnown(final RoutingContext ctx, final String repo) {
        try {
            if (!CooldownInspectResource.blank(repo)) {
                this.inspector.scope("", repo);
            }
            return true;
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 404, "NOT_FOUND", ex.getMessage());
            return false;
        }
    }

    /**
     * Send the result or a 500.
     *
     * @param ctx Routing context
     * @param json Result
     * @param err Failure
     */
    private static void send(final RoutingContext ctx, final JsonObject json, final Throwable err) {
        if (err != null) {
            ApiResponse.sendError(
                ctx, 500, "INTERNAL_ERROR", String.valueOf(PackageInspector.cause(err).getMessage())
            );
        } else {
            ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(json.encode());
        }
    }

    /**
     * Whether an inspection found nothing under the exact name.
     *
     * @param json Inspection
     * @return True with no version and no repository answering 200
     */
    private static boolean unmatched(final JsonObject json) {
        final JsonArray versions = json.getJsonArray("versions", new JsonArray());
        final JsonArray repos = json.getJsonArray("repos", new JsonArray());
        boolean served = false;
        for (int idx = 0; idx < repos.size() && !served; idx += 1) {
            final JsonObject meta = repos.getJsonObject(idx).getJsonObject("metadata");
            served = meta != null && meta.getInteger("status", 0) == 200;
        }
        return versions.isEmpty() && !served;
    }

    /**
     * Blank check.
     *
     * @param value Value
     * @return True when null or blank
     */
    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }
}
