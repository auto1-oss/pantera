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
import com.auto1.pantera.security.policy.Policy;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.CompletableFuture;

/**
 * GET /api/v1/admin/troubleshoot?url= — explain why a repository request
 * fails or serves stale content (admin only). See {@link Troubleshooter}.
 *
 * @since 2.2.9
 */
public final class TroubleshootResource {

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Troubleshooter.
     */
    private final Troubleshooter troubleshooter;

    /**
     * Ctor.
     *
     * @param policy Security policy
     * @param troubleshooter Troubleshooter
     */
    public TroubleshootResource(final Policy<?> policy, final Troubleshooter troubleshooter) {
        this.policy = policy;
        this.troubleshooter = troubleshooter;
    }

    /**
     * Register routes.
     *
     * @param router Router
     */
    public void register(final Router router) {
        router.get("/api/v1/admin/troubleshoot")
            .handler(new AuthzHandler(this.policy, ApiAdminPermission.ADMIN))
            .handler(this::troubleshoot);
    }

    /**
     * Handler.
     *
     * @param ctx Routing context
     */
    private void troubleshoot(final RoutingContext ctx) {
        final String url = ctx.queryParams().get("url");
        if (url == null || url.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Query param 'url' is required");
            return;
        }
        final String auth = ctx.request().getHeader("Authorization");
        CompletableFuture.supplyAsync(() -> url, HandlerExecutor.get())
            .thenCompose(ignored -> this.troubleshooter.explain(url, auth))
            .whenComplete((json, err) -> {
                if (err == null) {
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(json.encode());
                } else {
                    final Throwable cause = PackageInspector.cause(err);
                    if (cause instanceof IllegalArgumentException) {
                        ApiResponse.sendError(ctx, 400, "BAD_REQUEST", cause.getMessage());
                    } else {
                        ApiResponse.sendError(
                            ctx, 500, "INTERNAL_ERROR", String.valueOf(cause.getMessage())
                        );
                    }
                }
            });
    }
}
