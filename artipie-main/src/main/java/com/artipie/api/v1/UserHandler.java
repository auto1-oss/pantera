/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import com.artipie.api.AuthTokenRest;
import com.artipie.api.AuthzHandler;
import com.artipie.api.perms.ApiUserPermission;
import com.artipie.api.perms.ApiUserPermission.UserAction;
import com.artipie.asto.misc.Cleanable;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.Authentication;
import com.artipie.security.policy.Policy;
import com.artipie.settings.ArtipieSecurity;
import com.artipie.settings.cache.ArtipieCaches;
import com.artipie.settings.users.CrudUsers;
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
 * User handler for /api/v1/users/* endpoints.
 * @since 1.21
 */
public final class UserHandler {

    /**
     * User name path parameter.
     */
    private static final String NAME = "name";

    /**
     * Update user permission constant.
     */
    private static final ApiUserPermission UPDATE =
        new ApiUserPermission(UserAction.UPDATE);

    /**
     * Create user permission constant.
     */
    private static final ApiUserPermission CREATE =
        new ApiUserPermission(UserAction.CREATE);

    /**
     * Crud users object.
     */
    private final CrudUsers users;

    /**
     * Artipie authenticated users cache.
     */
    private final Cleanable<String> ucache;

    /**
     * Artipie policy cache.
     */
    private final Cleanable<String> pcache;

    /**
     * Artipie authentication.
     */
    private final Authentication auth;

    /**
     * Artipie security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param users Crud users object
     * @param caches Artipie caches
     * @param security Artipie security
     */
    public UserHandler(final CrudUsers users, final ArtipieCaches caches,
        final ArtipieSecurity security) {
        this.users = users;
        this.ucache = caches.usersCache();
        this.pcache = caches.policyCache();
        this.auth = security.authentication();
        this.policy = security.policy();
    }

    /**
     * Register user routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final ApiUserPermission read = new ApiUserPermission(UserAction.READ);
        final ApiUserPermission delete = new ApiUserPermission(UserAction.DELETE);
        final ApiUserPermission chpass = new ApiUserPermission(UserAction.CHANGE_PASSWORD);
        final ApiUserPermission enable = new ApiUserPermission(UserAction.ENABLE);
        // GET /api/v1/users — paginated list
        router.get("/api/v1/users")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::listUsers);
        // GET /api/v1/users/:name — get single user
        router.get("/api/v1/users/:name")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::getUser);
        // PUT /api/v1/users/:name — create or update user
        router.put("/api/v1/users/:name")
            .handler(this::putUser);
        // DELETE /api/v1/users/:name — delete user
        router.delete("/api/v1/users/:name")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleteUser);
        // POST /api/v1/users/:name/password — change password
        router.post("/api/v1/users/:name/password")
            .handler(new AuthzHandler(this.policy, chpass))
            .handler(this::alterPassword);
        // POST /api/v1/users/:name/enable — enable user
        router.post("/api/v1/users/:name/enable")
            .handler(new AuthzHandler(this.policy, enable))
            .handler(this::enableUser);
        // POST /api/v1/users/:name/disable — disable user
        router.post("/api/v1/users/:name/disable")
            .handler(new AuthzHandler(this.policy, enable))
            .handler(this::disableUser);
    }

    /**
     * GET /api/v1/users — paginated list of users.
     * @param ctx Routing context
     */
    private void listUsers(final RoutingContext ctx) {
        final int page = ApiResponse.intParam(
            ctx.queryParam("page").stream().findFirst().orElse(null), 0
        );
        final int size = ApiResponse.clampSize(
            ApiResponse.intParam(
                ctx.queryParam("size").stream().findFirst().orElse(null), 20
            )
        );
        ctx.vertx().<javax.json.JsonArray>executeBlocking(
            this.users::list,
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
     * GET /api/v1/users/:name — get single user info.
     * @param ctx Routing context
     */
    private void getUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        ctx.vertx().<Optional<JsonObject>>executeBlocking(
            () -> this.users.get(uname),
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
                        String.format("User '%s' not found", uname)
                    );
                }
            }
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * PUT /api/v1/users/:name — create or update user.
     * @param ctx Routing context
     */
    private void putUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        final String bodyStr = ctx.body().asString();
        if (bodyStr == null || bodyStr.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final JsonObject rawBody;
        try {
            rawBody = Json.createReader(new StringReader(bodyStr)).readObject();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        // Normalize: UI sends "password", backend expects "pass" + "type"
        final JsonObject body;
        if (rawBody.containsKey("password") && !rawBody.containsKey("pass")) {
            final javax.json.JsonObjectBuilder nb = Json.createObjectBuilder(rawBody);
            nb.add("pass", rawBody.getString("password"));
            nb.remove("password");
            if (!rawBody.containsKey("type")) {
                nb.add("type", "plain");
            }
            body = nb.build();
        } else {
            body = rawBody;
        }
        final Optional<JsonObject> existing = this.users.get(uname);
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        if (existing.isPresent() && perms.implies(UserHandler.UPDATE)
            || existing.isEmpty() && perms.implies(UserHandler.CREATE)) {
            ctx.vertx().executeBlocking(
                () -> {
                    this.users.addOrUpdate(body, uname);
                    return null;
                },
                false
            ).onSuccess(
                ignored -> {
                    this.ucache.invalidate(uname);
                    this.pcache.invalidate(uname);
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
     * DELETE /api/v1/users/:name — delete user.
     * @param ctx Routing context
     */
    private void deleteUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        ctx.vertx().executeBlocking(
            () -> {
                this.users.remove(uname);
                return null;
            },
            false
        ).onSuccess(
            ignored -> {
                this.ucache.invalidate(uname);
                this.pcache.invalidate(uname);
                ctx.response().setStatusCode(200).end();
            }
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }

    /**
     * POST /api/v1/users/:name/password — change user password.
     * @param ctx Routing context
     */
    private void alterPassword(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
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
        final String oldPass = body.getString("old_pass", "");
        final Optional<AuthUser> verified = this.auth.user(uname, oldPass);
        if (verified.isEmpty()) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Invalid old password");
            return;
        }
        ctx.vertx().executeBlocking(
            () -> {
                this.users.alterPassword(uname, body);
                return null;
            },
            false
        ).onSuccess(
            ignored -> {
                this.ucache.invalidate(uname);
                ctx.response().setStatusCode(200).end();
            }
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }

    /**
     * POST /api/v1/users/:name/enable — enable user.
     * @param ctx Routing context
     */
    private void enableUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        ctx.vertx().executeBlocking(
            () -> {
                this.users.enable(uname);
                return null;
            },
            false
        ).onSuccess(
            ignored -> {
                this.ucache.invalidate(uname);
                this.pcache.invalidate(uname);
                ctx.response().setStatusCode(200).end();
            }
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }

    /**
     * POST /api/v1/users/:name/disable — disable user.
     * @param ctx Routing context
     */
    private void disableUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        ctx.vertx().executeBlocking(
            () -> {
                this.users.disable(uname);
                return null;
            },
            false
        ).onSuccess(
            ignored -> {
                this.ucache.invalidate(uname);
                this.pcache.invalidate(uname);
                ctx.response().setStatusCode(200).end();
            }
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }
}
