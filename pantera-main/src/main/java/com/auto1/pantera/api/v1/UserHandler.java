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
    /**
     * Change-password permission — required to reset an existing user's password.
     */
    private static final ApiUserPermission CHANGE_PASSWORD =
        new ApiUserPermission(ApiUserPermission.UserAction.CHANGE_PASSWORD);

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
     * Pantera security policy.
     */
    private final Policy<?> policy;



    /**
     * Shared "credential changed" revocation: password change, admin
     * reset and disable all evict every live token (SecOps
     * token-revocation #38).
     */
    private final com.auto1.pantera.auth.SessionRevoker revoker;

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
     * Privilege ceiling for user writes (B14/B15).
     */
    private final UserWriteGuard guard;

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
        this.policy = security.policy();
        this.enabledFilter = enabledFilter;
        this.revoker = new com.auto1.pantera.auth.SessionRevoker(blocklist, tokenDao);
        this.guard = new UserWriteGuard(users, security.policy());
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
        // POST /api/v1/users/:name/password — change password. Self-service
        // needs no grant (the current password proves it); resetting another
        // user's password needs change_password + the privilege ceiling,
        // both enforced in the handler (B14).
        router.post("/api/v1/users/:name/password")
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
        final JsonObject body;
        try {
            body = UserHandler.normalized(
                Json.createReader(new StringReader(bodyStr)).readObject()
            );
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", ex.getMessage());
            return;
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final String caller = ctx.user().principal().getString(AuthTokenRest.SUB);
        final String context = ctx.user().principal().getString(AuthTokenRest.CONTEXT);
        CompletableFuture.supplyAsync(
            () -> this.upsert(caller, context, uname, body),
            HandlerExecutor.get()
        ).whenComplete((refusal, err) -> {
            if (err != null) {
                // A failed write may still have changed state: never leave
                // a stale credential or policy cached (B48).
                this.invalidate(uname);
                UserHandler.sendFailure(ctx, uname, err);
            } else if (refusal != null) {
                ApiResponse.sendError(ctx, refusal.status(), refusal.code(), refusal.message());
            } else {
                this.invalidate(uname);
                ctx.response().setStatusCode(201).end();
            }
        });
    }

    /**
     * Authorize and apply a user upsert. Runs on the worker pool.
     * @param caller Caller's username
     * @param context Caller's auth context
     * @param uname Target username
     * @param body Normalized user document
     * @return Refusal, or {@code null} when the write was applied
     */
    private Refusal upsert(
        final String caller, final String context, final String uname, final JsonObject body
    ) {
        final Optional<JsonObject> existing = this.users.get(uname);
        final PermissionCollection perms = this.policy.getPermissions(new AuthUser(caller, context));
        if (!(existing.isPresent() && perms.implies(UserHandler.UPDATE)
            || existing.isEmpty() && perms.implies(UserHandler.CREATE))) {
            return Refusal.forbidden("Insufficient permissions");
        }
        final boolean reset = existing.isPresent() && body.containsKey("pass");
        final Refusal refusal = this.ceiling(caller, perms, uname, body, existing.isPresent(), reset);
        if (refusal != null) {
            return refusal;
        }
        if (body.containsKey("pass")) {
            // Validate before any write (B48: a weak reset answered 500
            // after a partial write).
            final String failure = com.auto1.pantera.auth.PasswordPolicy
                .validate(uname, body.getString("pass"));
            if (failure != null) {
                return new Refusal(400, "WEAK_PASSWORD", failure);
            }
        }
        if (reset) {
            // One transaction for the fields and the credential (B48).
            this.users.updateWithPassword(body, uname, body.getString("pass"));
            this.revoker.revokeAll(uname);
        } else {
            this.users.addOrUpdate(body, uname);
        }
        return null;
    }

    /**
     * Privilege ceiling for an upsert by a non-administrator (B14/B15).
     * @param caller Caller's username
     * @param perms Caller's permissions
     * @param uname Target username
     * @param body User document
     * @param exists Whether the target exists
     * @param reset Whether an existing user's password is replaced
     * @return Refusal or {@code null}
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private Refusal ceiling(
        final String caller, final PermissionCollection perms, final String uname,
        final JsonObject body, final boolean exists, final boolean reset
    ) {
        if (this.guard.administrator(perms)) {
            return null;
        }
        String reason = exists ? this.guard.refuseTarget(caller, perms, uname) : null;
        if (reason == null) {
            reason = this.guard.refuseRoles(caller, perms, body);
        }
        if (reason == null) {
            reason = this.guard.refuseProvider(perms, body);
        }
        if (reason == null && reset && !perms.implies(UserHandler.CHANGE_PASSWORD)) {
            reason = "Resetting a password requires the change-password permission";
        }
        if (reason == null && reset && uname.equals(caller)) {
            reason = "Change your own password with POST /api/v1/users/" + uname
                + "/password and your current password";
        }
        if (reason == null) {
            return null;
        }
        return Refusal.forbidden(reason);
    }

    /**
     * Normalize a submitted user document: {@code password} is an alias for
     * {@code pass} (both at once with different values is refused — B48,
     * the second one bypassed the password policy), and the SSO-owned
     * {@code sso_subject} / {@code auth_provider} fields are dropped — only
     * the SSO login flow binds an identity (B15).
     * @param raw Submitted document
     * @return Normalized document
     * @throws IllegalArgumentException On conflicting password fields
     */
    private static JsonObject normalized(final JsonObject raw) {
        final javax.json.JsonObjectBuilder out = Json.createObjectBuilder(raw);
        out.remove("sso_subject");
        out.remove("auth_provider");
        if (raw.containsKey("password")) {
            final String alias = raw.getString("password");
            if (raw.containsKey("pass") && !alias.equals(raw.getString("pass"))) {
                throw new IllegalArgumentException(
                    "Send the password once, as either 'pass' or 'password'"
                );
            }
            out.remove("password");
            out.add("pass", alias);
            if (!raw.containsKey("type")) {
                out.add("type", "plain");
            }
        }
        return out.build();
    }

    /**
     * Drop every cached view of a user after a write.
     * @param uname Username
     */
    private void invalidate(final String uname) {
        this.ucache.invalidate(uname);
        this.pcache.invalidate(uname);
        this.invalidateEnabled(uname);
    }

    /**
     * Map a failed user write to an error response.
     * @param ctx Routing context
     * @param uname Target username
     * @param err Failure
     */
    private static void sendFailure(final RoutingContext ctx, final String uname,
        final Throwable err) {
        final Throwable cause = err.getCause() != null ? err.getCause() : err;
        if (cause instanceof IllegalArgumentException) {
            ApiResponse.sendError(ctx, 400, "WEAK_PASSWORD", cause.getMessage());
        } else if (cause instanceof IllegalStateException
            && cause.getMessage() != null && cause.getMessage().startsWith("User not found")) {
            ApiResponse.sendError(
                ctx, 404, "NOT_FOUND", String.format("User '%s' not found", uname)
            );
        } else {
            ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", "Failed to update user");
        }
    }

    /**
     * A refused user write.
     * @param status HTTP status
     * @param code Error code
     * @param message Message
     */
    private record Refusal(int status, String code, String message) {

        /**
         * A 403 refusal.
         * @param message Message
         * @return Refusal
         */
        static Refusal forbidden(final String message) {
            return new Refusal(403, "FORBIDDEN", message);
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
        // Self-service vs reset of another user (B13/B14):
        //   - Self-service: the caller changes THEIR OWN password and must
        //     prove the current one — checked against the stored local
        //     hash only, never the auth chain (which accepts the caller's
        //     own JWT as a password).
        //   - Reset: another user's password needs change_password AND the
        //     privilege ceiling (no reset of an administrator or of a user
        //     holding roles the caller does not hold).
        final String caller = ctx.user() != null && ctx.user().principal() != null
            ? ctx.user().principal().getString(AuthTokenRest.SUB, "") : "";
        final String context = ctx.user() != null && ctx.user().principal() != null
            ? ctx.user().principal().getString(AuthTokenRest.CONTEXT) : null;
        final String newPass = body.getString("new_pass", null);
        if (newPass == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "new_pass is required");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> {
                final Refusal refusal = this.passwordRefusal(
                    caller, context, uname, body.getString("old_pass", "")
                );
                if (refusal == null) {
                    this.users.alterPassword(uname, body);
                    // SECURITY (2.2.9, SecOps #38): a password change or reset
                    // must evict every live session and API token — otherwise
                    // rotating a compromised password does not evict the
                    // attacker. Self-service callers re-authenticate with the
                    // new password.
                    this.revoker.revokeAll(uname);
                }
                return refusal;
            },
            HandlerExecutor.get()
        ).whenComplete((refusal, err) -> {
            if (err != null) {
                this.invalidate(uname);
                UserHandler.sendFailure(ctx, uname, err);
            } else if (refusal != null) {
                ApiResponse.sendError(ctx, refusal.status(), refusal.code(), refusal.message());
            } else {
                // ucache is a PublishingCleanable wrapping CachedUsers:
                // invalidate() does a full L1+L2 flush (the cache is keyed by
                // SHA-256(username:password)) and broadcasts it cluster-wide.
                // The policy and enabled-flag caches are refreshed too.
                this.invalidate(uname);
                ctx.response().setStatusCode(200).end();
            }
        });
    }

    /**
     * Authorize a password change. Runs on the worker pool.
     * @param caller Caller's username
     * @param context Caller's auth context
     * @param uname Target username
     * @param oldPass Submitted current password (self-service)
     * @return Refusal, or {@code null} when allowed
     */
    private Refusal passwordRefusal(
        final String caller, final String context, final String uname, final String oldPass
    ) {
        final Refusal refusal;
        if (uname.equals(caller)) {
            // 403 instead of 401: the caller already has a valid session.
            // "Invalid old password" is an application-level authorization
            // failure for the mutation, not a session-expiry signal — the
            // UI must not silently refresh the session and retry.
            refusal = this.users.passwordMatches(uname, oldPass)
                ? null : Refusal.forbidden("Current password is incorrect.");
        } else {
            final PermissionCollection perms =
                this.policy.getPermissions(new AuthUser(caller, context));
            if (!perms.implies(UserHandler.CHANGE_PASSWORD)) {
                refusal = Refusal.forbidden("Access denied: insufficient permissions");
            } else if (this.users.get(uname).isEmpty()) {
                refusal = new Refusal(404, "NOT_FOUND", String.format("User '%s' not found", uname));
            } else {
                final String reason = this.guard.refuseTarget(caller, perms, uname);
                refusal = reason == null ? null : Refusal.forbidden(reason);
            }
        }
        return refusal;
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
            final int revoked = this.revoker.revokeAll(uname);
            EcsLogger.info("com.auto1.pantera.api.v1")
                .message("User disabled: revoked " + revoked + " tokens")
                .eventCategory("iam")
                .eventAction("user_disable")
                .eventOutcome("success")
                .field("user.name", uname)
                .field("log.source", "application")
                .log();
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
