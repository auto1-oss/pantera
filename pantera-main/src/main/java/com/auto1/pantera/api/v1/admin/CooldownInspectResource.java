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
import com.auto1.pantera.security.policy.Policy;
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
 *   <li>POST /api/v1/cooldown/refresh-package {repoType, package, repo?} —
 *       clear every layer for the package cluster-wide, return
 *       {@code {before, after}}</li>
 * </ul>
 *
 * @since 2.2.9
 */
public final class CooldownInspectResource {

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
     * Ctor.
     *
     * @param policy Security policy
     * @param inspector Inspector
     * @param refresher Refresher
     */
    public CooldownInspectResource(
        final Policy<?> policy, final PackageInspector inspector,
        final PackageRefresher refresher
    ) {
        this.policy = policy;
        this.inspector = inspector;
        this.refresher = refresher;
    }

    /**
     * Register routes.
     *
     * @param router Router
     */
    public void register(final Router router) {
        final AuthzHandler admin = new AuthzHandler(this.policy, ApiAdminPermission.ADMIN);
        router.get("/api/v1/cooldown/inspect").handler(admin).handler(this::inspect);
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
        CompletableFuture.supplyAsync(() -> repo, com.auto1.pantera.http.context.HandlerExecutor.get())
            .thenCompose(ignored -> this.inspector.inspect(type.trim(), pkg.trim(), repo, auth))
            .whenComplete((json, err) -> CooldownInspectResource.send(ctx, json, err));
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
        CompletableFuture.supplyAsync(() -> repo, com.auto1.pantera.http.context.HandlerExecutor.get())
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
     * Blank check.
     *
     * @param value Value
     * @return True when null or blank
     */
    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }
}
