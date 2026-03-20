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

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.perms.ApiRolePermission;
import com.auto1.pantera.api.perms.ApiRolePermission.RoleAction;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.users.CrudRoles;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Role handler for /api/v1/roles/* endpoints.
 * @since 1.21
 */
public final class RoleHandler {

    /**
     * Role name path parameter.
     */
    private static final String NAME = "name";

    /**
     * Update role permission constant.
     */
    private static final ApiRolePermission UPDATE =
        new ApiRolePermission(RoleAction.UPDATE);

    /**
     * Create role permission constant.
     */
    private static final ApiRolePermission CREATE =
        new ApiRolePermission(RoleAction.CREATE);

    /**
     * Crud roles object.
     */
    private final CrudRoles roles;

    /**
     * Pantera policy cache.
     */
    private final Cleanable<String> policyCache;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param roles Crud roles object
     * @param policyCache Pantera policy cache
     * @param policy Pantera security policy
     */
    public RoleHandler(final CrudRoles roles, final Cleanable<String> policyCache,
        final Policy<?> policy) {
        this.roles = roles;
        this.policyCache = policyCache;
        this.policy = policy;
    }

    /**
     * Register role routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final ApiRolePermission read = new ApiRolePermission(RoleAction.READ);
        final ApiRolePermission delete = new ApiRolePermission(RoleAction.DELETE);
        final ApiRolePermission enable = new ApiRolePermission(RoleAction.ENABLE);
        // GET /api/v1/roles — paginated list
        router.get("/api/v1/roles")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::listRoles);
        // GET /api/v1/roles/:name — get single role
        router.get("/api/v1/roles/:name")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::getRole);
        // PUT /api/v1/roles/:name — create or update role
        router.put("/api/v1/roles/:name")
            .handler(this::putRole);
        // DELETE /api/v1/roles/:name — delete role
        router.delete("/api/v1/roles/:name")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleteRole);
        // POST /api/v1/roles/:name/enable — enable role
        router.post("/api/v1/roles/:name/enable")
            .handler(new AuthzHandler(this.policy, enable))
            .handler(this::enableRole);
        // POST /api/v1/roles/:name/disable — disable role
        router.post("/api/v1/roles/:name/disable")
            .handler(new AuthzHandler(this.policy, enable))
            .handler(this::disableRole);
    }

    /**
     * GET /api/v1/roles — paginated list of roles.
     * @param ctx Routing context
     */
    private void listRoles(final RoutingContext ctx) {
        final int page = ApiResponse.intParam(
            ctx.queryParam("page").stream().findFirst().orElse(null), 0
        );
        final int size = ApiResponse.clampSize(
            ApiResponse.intParam(
                ctx.queryParam("size").stream().findFirst().orElse(null), 20
            )
        );
        ctx.vertx().<javax.json.JsonArray>executeBlocking(
            this.roles::list,
            false
        ).onSuccess(
            all -> {
                final List<io.vertx.core.json.JsonObject> flat =
                    new java.util.ArrayList<>(all.size());
                for (int i = 0; i < all.size(); i++) {
                    flat.add(
                        new io.vertx.core.json.JsonObject(
                            all.getJsonObject(i).toString()
                        )
                    );
                }
                final JsonArray items = ApiResponse.sliceToArray(flat, page, size);
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(ApiResponse.paginated(items, page, size, flat.size()).encode());
            }
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * GET /api/v1/roles/:name — get single role info.
     * @param ctx Routing context
     */
    private void getRole(final RoutingContext ctx) {
        final String rname = ctx.pathParam(RoleHandler.NAME);
        ctx.vertx().<Optional<JsonObject>>executeBlocking(
            () -> this.roles.get(rname),
            false
        ).onSuccess(
            opt -> {
                if (opt.isPresent()) {
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(opt.get().toString());
                } else {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("Role '%s' not found", rname)
                    );
                }
            }
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * PUT /api/v1/roles/:name — create or update role.
     * @param ctx Routing context
     */
    private void putRole(final RoutingContext ctx) {
        final String rname = ctx.pathParam(RoleHandler.NAME);
        final String bodyStr = ctx.body().asString();
        if (bodyStr == null || bodyStr.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final JsonObject body;
        try {
            body = Json.createReader(new StringReader(bodyStr)).readObject();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final Optional<JsonObject> existing = this.roles.get(rname);
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        if (existing.isPresent() && perms.implies(RoleHandler.UPDATE)
            || existing.isEmpty() && perms.implies(RoleHandler.CREATE)) {
            ctx.vertx().executeBlocking(
                () -> {
                    this.roles.addOrUpdate(body, rname);
                    return null;
                },
                false
            ).onSuccess(
                ignored -> {
                    this.policyCache.invalidate(rname);
                    ctx.response().setStatusCode(201).end();
                }
            ).onFailure(
                err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
            );
        } else {
            ApiResponse.sendError(ctx, 403, "FORBIDDEN", "Insufficient permissions");
        }
    }

    /**
     * DELETE /api/v1/roles/:name — delete role.
     * @param ctx Routing context
     */
    private void deleteRole(final RoutingContext ctx) {
        final String rname = ctx.pathParam(RoleHandler.NAME);
        ctx.vertx().executeBlocking(
            () -> {
                this.roles.remove(rname);
                return null;
            },
            false
        ).onSuccess(
            ignored -> {
                this.policyCache.invalidate(rname);
                ctx.response().setStatusCode(200).end();
            }
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("Role '%s' not found", rname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }

    /**
     * POST /api/v1/roles/:name/enable — enable role.
     * @param ctx Routing context
     */
    private void enableRole(final RoutingContext ctx) {
        final String rname = ctx.pathParam(RoleHandler.NAME);
        ctx.vertx().executeBlocking(
            () -> {
                this.roles.enable(rname);
                return null;
            },
            false
        ).onSuccess(
            ignored -> {
                this.policyCache.invalidate(rname);
                ctx.response().setStatusCode(200).end();
            }
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("Role '%s' not found", rname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }

    /**
     * POST /api/v1/roles/:name/disable — disable role.
     * @param ctx Routing context
     */
    private void disableRole(final RoutingContext ctx) {
        final String rname = ctx.pathParam(RoleHandler.NAME);
        ctx.vertx().executeBlocking(
            () -> {
                this.roles.disable(rname);
                return null;
            },
            false
        ).onSuccess(
            ignored -> {
                this.policyCache.invalidate(rname);
                ctx.response().setStatusCode(200).end();
            }
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("Role '%s' not found", rname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }
}
