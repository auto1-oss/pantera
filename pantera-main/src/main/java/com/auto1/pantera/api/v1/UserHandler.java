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
import com.auto1.pantera.api.perms.ApiUserPermission;
import com.auto1.pantera.api.perms.ApiUserPermission.UserAction;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.auth.RevocationBlocklist;
import com.auto1.pantera.db.dao.PagedResult;
import com.auto1.pantera.db.dao.UserDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.cache.PanteraCaches;
import com.auto1.pantera.settings.users.CrudUsers;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
     * Pantera authenticated users cache.
     */
    private final Cleanable<String> ucache;

    /**
     * Pantera policy cache.
     */
    private final Cleanable<String> pcache;

    /**
     * Pantera authentication.
     */
    private final Authentication auth;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Token blocklist. Used to immediately revoke access tokens when
     * an administrator disables a user. May be {@code null} in no-DB
     * test deployments.
     */
    private final RevocationBlocklist blocklist;

    /**
     * Token DAO. Used to revoke long-lived refresh and API tokens when
     * an administrator disables a user. May be {@code null} in no-DB
     * test deployments.
     */
    private final UserTokenDao tokenDao;

    /**
     * Cached filter for the local-enabled flag check, if wired. When
     * an admin toggles enabled state (update / enable / disable / delete)
     * we must drop the per-user L1/L2 cache entry so the next
     * authentication reflects the new state cluster-wide.
     * May be {@code null} when the auth chain was built without a DB.
     * @since 2.2.0
     */
    private final com.auto1.pantera.auth.CachedLocalEnabledFilter enabledFilter;

    /**
     * Ctor.
     * @param users Crud users object
     * @param caches Pantera caches
     * @param security Pantera security
     */
    public UserHandler(final CrudUsers users, final PanteraCaches caches,
        final PanteraSecurity security) {
        this(users, caches, security, null, null, null);
    }

    /**
     * Ctor with token revocation wiring (no enabled-filter invalidation).
     * Kept for callers that don't have the filter reference.
     * @param users Crud users object
     * @param caches Pantera caches
     * @param security Pantera security
     * @param blocklist Revocation blocklist; may be {@code null}
     * @param tokenDao Token DAO; may be {@code null}
     */
    public UserHandler(final CrudUsers users, final PanteraCaches caches,
        final PanteraSecurity security, final RevocationBlocklist blocklist,
        final UserTokenDao tokenDao) {
        this(users, caches, security, blocklist, tokenDao, null);
    }

    /**
     * Full ctor.
     * @param users Crud users object
     * @param caches Pantera caches
     * @param security Pantera security
     * @param blocklist Revocation blocklist for access-token revocation
     *     on user disable; may be {@code null}
     * @param tokenDao Token DAO for refresh / API token revocation on
     *     user disable; may be {@code null}
     * @param enabledFilter Cached local-enabled filter whose per-user
     *     entry is invalidated on enable / disable / update / delete;
     *     may be {@code null} when not wired
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public UserHandler(final CrudUsers users, final PanteraCaches caches,
        final PanteraSecurity security, final RevocationBlocklist blocklist,
        final UserTokenDao tokenDao,
        final com.auto1.pantera.auth.CachedLocalEnabledFilter enabledFilter) {
        this.users = users;
        this.ucache = caches.usersCache();
        this.pcache = caches.policyCache();
        this.auth = security.authentication();
        this.policy = security.policy();
        this.blocklist = blocklist;
        this.tokenDao = tokenDao;
        this.enabledFilter = enabledFilter;
    }

    /**
     * Invalidate the cached enabled-flag entry for the given username,
     * if the filter is wired. Broadcasts to peer nodes via pub/sub
     * inside {@link com.auto1.pantera.auth.CachedLocalEnabledFilter#invalidate(String)}.
     *
     * @param uname Username
     */
    private void invalidateEnabled(final String uname) {
        if (this.enabledFilter != null) {
            this.enabledFilter.invalidate(uname);
        }
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
     * Supports query params: page, size, q (search), sort (field), sort_dir (asc|desc).
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
        final String query = ctx.queryParam("q").stream().findFirst().orElse(null);
        final Set<String> userSortFields = Set.of("username", "email", "enabled", "auth_provider");
        final String rawSort = ctx.queryParam("sort").stream().findFirst().orElse("username");
        final String sortField = userSortFields.contains(rawSort) ? rawSort : "username";
        final String sortDir = ctx.queryParam("sort_dir").stream().findFirst().orElse("asc");
        final boolean ascending = !"desc".equalsIgnoreCase(sortDir);
        if (!(this.users instanceof UserDao)) {
            ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", "Paged listing not supported");
            return;
        }
        final UserDao dao = (UserDao) this.users;
        CompletableFuture.supplyAsync(
            (java.util.function.Supplier<PagedResult<JsonObject>>)
                () -> dao.listPaged(query, sortField, ascending, size, page * size),
            HandlerExecutor.get()
        ).whenComplete((result, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                final JsonArray items = new JsonArray();
                for (final JsonObject obj : result.items()) {
                    items.add(new io.vertx.core.json.JsonObject(obj.toString()));
                }
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(ApiResponse.paginated(items, page, size, result.total()).encode());
            }
        });
    }

    /**
     * GET /api/v1/users/:name — get single user info.
     * @param ctx Routing context
     */
    private void getUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        CompletableFuture.supplyAsync(
            (java.util.function.Supplier<Optional<JsonObject>>) () -> this.users.get(uname),
            HandlerExecutor.get()
        ).whenComplete((opt, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (opt.isPresent()) {
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
        });
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
            CompletableFuture.runAsync(
                () -> this.users.addOrUpdate(body, uname),
                HandlerExecutor.get()
            ).whenComplete((ignored, err) -> {
                if (err != null) {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                } else {
                    this.ucache.invalidate(uname);
                    this.pcache.invalidate(uname);
                    this.invalidateEnabled(uname);
                    ctx.response().setStatusCode(201).end();
                }
            });
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
        CompletableFuture.runAsync(
            () -> this.users.remove(uname),
            HandlerExecutor.get()
        ).whenComplete((ignored, err) -> {
            if (err == null) {
                this.ucache.invalidate(uname);
                this.pcache.invalidate(uname);
                this.invalidateEnabled(uname);
                ctx.response().setStatusCode(200).end();
            } else {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (cause instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        });
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
        // Distinguish self-service vs admin-reset.
        //   - Self-service: caller is changing THEIR OWN password. They
        //     must prove knowledge of the current password.
        //   - Admin-reset: caller is an administrator with the
        //     api_user_permissions change_password permission (enforced
        //     by the AuthzHandler on this route) changing someone ELSE'S
        //     password. They do not — and cannot — know the target
        //     user's current password. The permission check on the route
        //     is authorization enough.
        final String caller = ctx.user() != null && ctx.user().principal() != null
            ? ctx.user().principal().getString(AuthTokenRest.SUB, "") : "";
        final boolean selfService = uname.equals(caller);
        if (selfService) {
            final String oldPass = body.getString("old_pass", "");
            final Optional<AuthUser> verified = this.auth.user(uname, oldPass);
            if (verified.isEmpty()) {
                // 403 instead of 401: the caller already has a valid
                // session (the JWT filter let them in). "Invalid old
                // password" is an application-level authorization
                // failure for the mutation, not a session-expiry signal
                // — the axios interceptor must not try to silently
                // refresh the session and retry the request, which
                // would loop forever because the old password is still
                // wrong after any number of refreshes.
                ApiResponse.sendError(
                    ctx, 403, "FORBIDDEN", "Current password is incorrect."
                );
                return;
            }
        }
        CompletableFuture.runAsync(
            () -> this.users.alterPassword(uname, body),
            HandlerExecutor.get()
        ).whenComplete((ignored, err) -> {
            if (err == null) {
                // ucache is a PublishingCleanable wrapping CachedUsers, so
                // an instanceof check on CachedUsers is always false here.
                // Cleanable.invalidate(key) delegates to CachedUsers.invalidate
                // which now does a full L1+L2 flush — the only safe option
                // because the cache is keyed by SHA-256(username:password)
                // and we don't have the hash. The pub/sub layer broadcasts
                // the flush to other Pantera instances in the cluster.
                this.ucache.invalidate(uname);
                // Policy cache may contain stale role/enabled state for this
                // user; invalidate that too so subsequent requests see fresh data.
                this.pcache.invalidate(uname);
                // Enabled-flag cache is keyed by username — password
                // change doesn't flip enabled, but keeping it in sync
                // here is defensive and cheap.
                this.invalidateEnabled(uname);
                ctx.response().setStatusCode(200).end();
            } else {
                // CompletableFuture wraps the underlying exception in
                // CompletionException; unwrap to get the original from
                // UserDao.alterPassword.
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (cause instanceof IllegalArgumentException) {
                    // PasswordPolicy validation failure -> 400 with the message
                    ApiResponse.sendError(
                        ctx, 400, "WEAK_PASSWORD", cause.getMessage()
                    );
                } else if (cause instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        });
    }

    /**
     * POST /api/v1/users/:name/enable — enable user.
     * @param ctx Routing context
     */
    private void enableUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        CompletableFuture.runAsync(
            () -> this.users.enable(uname),
            HandlerExecutor.get()
        ).whenComplete((ignored, err) -> {
            if (err == null) {
                this.ucache.invalidate(uname);
                this.pcache.invalidate(uname);
                this.invalidateEnabled(uname);
                ctx.response().setStatusCode(200).end();
            } else {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (cause instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        });
    }

    /**
     * POST /api/v1/users/:name/disable — disable user.
     * @param ctx Routing context
     */
    private void disableUser(final RoutingContext ctx) {
        final String uname = ctx.pathParam(UserHandler.NAME);
        CompletableFuture.runAsync(() -> {
            this.users.disable(uname);
            // Immediate token revocation — without this, the
            // user's existing access tokens, refresh tokens, and
            // API tokens would keep working until expiry. The
            // per-request isEnabled check in UnifiedJwtAuthHandler
            // is the safety net (fires on the next request), but
            // explicit revocation is cheaper, synchronous, and
            // cluster-wide via the blocklist pub/sub.
            if (this.blocklist != null) {
                // 7 days covers the default refresh-token TTL; any
                // access token older than that is already expired
                // by the JWT's own exp claim.
                this.blocklist.revokeUser(uname, 7 * 24 * 3600);
            }
            if (this.tokenDao != null) {
                final int revoked = this.tokenDao.revokeAllForUser(uname);
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("User disabled: revoked " + revoked + " tokens")
                    .eventCategory("iam")
                    .eventAction("user_disable")
                    .eventOutcome("success")
                    .field("user.name", uname)
                    .log();
            }
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err == null) {
                this.ucache.invalidate(uname);
                this.pcache.invalidate(uname);
                this.invalidateEnabled(uname);
                ctx.response().setStatusCode(200).end();
            } else {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (cause instanceof IllegalStateException) {
                    ApiResponse.sendError(
                        ctx, 404, "NOT_FOUND",
                        String.format("User '%s' not found", uname)
                    );
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        });
    }
}
