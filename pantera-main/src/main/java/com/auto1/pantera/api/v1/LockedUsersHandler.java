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
import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.LockedName;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Admin endpoints of the account lock: who is locked and why, and the
 * unlock of one user or of all.
 *
 * <ul>
 *   <li>{@code GET /api/v1/locked-users} - the locked names, oldest lock
 *   first, each with its count and where its last failed login came from.
 *   A locked name need not be a Pantera user.</li>
 *   <li>{@code GET /api/v1/users/:name/failed-logins} - count, lock and
 *   last failure of one name.</li>
 *   <li>{@code POST /api/v1/users/:name/unlock} - remove the lock and the
 *   count of one name.</li>
 *   <li>{@code POST /api/v1/users/unlock-all} - remove every lock.</li>
 * </ul>
 *
 * <p>Reading needs the permission to read users, unlocking the one to
 * enable or disable a user.</p>
 *
 * @since 2.2.15
 */
public final class LockedUsersHandler {

    /**
     * Path parameter of the user name.
     */
    private static final String NAME = "name";

    /**
     * Failed logins.
     */
    private final FailedLogins logins;

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     *
     * @param logins Failed logins
     * @param policy Security policy
     */
    public LockedUsersHandler(final FailedLogins logins, final Policy<?> policy) {
        this.logins = logins;
        this.policy = policy;
    }

    /**
     * Register the routes.
     *
     * @param router Router
     */
    public void register(final Router router) {
        final AuthzHandler read = new AuthzHandler(
            this.policy, new ApiUserPermission(ApiUserPermission.UserAction.READ)
        );
        final AuthzHandler unlock = new AuthzHandler(
            this.policy, new ApiUserPermission(ApiUserPermission.UserAction.ENABLE)
        );
        router.get("/api/v1/locked-users").handler(read).handler(this::list);
        router.get("/api/v1/users/:name/failed-logins").handler(read).handler(this::details);
        router.post("/api/v1/users/unlock-all").handler(unlock).handler(this::unlockAll);
        router.post("/api/v1/users/:name/unlock").handler(unlock).handler(this::unlock);
    }

    private void list(final RoutingContext ctx) {
        LockedUsersHandler.answer(
            ctx,
            () -> {
                final List<LockedName> names = this.logins.lockedNames();
                final JsonArray items = new JsonArray();
                for (final LockedName one : names) {
                    items.add(LockedUsersHandler.json(one).put("locked", true));
                }
                return new JsonObject().put("items", items).put("total", names.size());
            }
        );
    }

    private void details(final RoutingContext ctx) {
        final String name = ctx.pathParam(LockedUsersHandler.NAME);
        LockedUsersHandler.answer(
            ctx,
            () -> {
                final Optional<LockedName> entry = this.logins.details(name);
                final JsonObject body;
                if (entry.isPresent()) {
                    body = LockedUsersHandler.json(entry.get());
                } else {
                    body = new JsonObject().put(LockedUsersHandler.NAME, name).put("failures", 0);
                }
                // In force for this policy: a lock that expired, or that the
                // policy no longer keeps, is not reported as a lock.
                return body.put("locked", this.logins.passwordLocked(name));
            }
        );
    }

    private void unlock(final RoutingContext ctx) {
        final String name = ctx.pathParam(LockedUsersHandler.NAME);
        final String actor = LockedUsersHandler.actor(ctx);
        LockedUsersHandler.answer(
            ctx,
            () -> new JsonObject()
                .put(LockedUsersHandler.NAME, name)
                .put("unlocked", this.logins.unlock(name, actor))
        );
    }

    private void unlockAll(final RoutingContext ctx) {
        final String actor = LockedUsersHandler.actor(ctx);
        LockedUsersHandler.answer(
            ctx,
            () -> {
                final List<String> names = this.logins.unlockAll(actor);
                return new JsonObject()
                    .put("unlocked", new JsonArray(names))
                    .put("count", names.size());
            }
        );
    }

    /**
     * Run a blocking call off the event loop and send its JSON.
     *
     * @param ctx Routing context
     * @param call Call that reads or changes the failed logins
     */
    private static void answer(final RoutingContext ctx, final Supplier<JsonObject> call) {
        CompletableFuture.supplyAsync(call, HandlerExecutor.get()).whenComplete(
            (body, err) -> {
                if (err == null) {
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(body.encode());
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err);
                }
            }
        );
    }

    private static JsonObject json(final LockedName one) {
        return new JsonObject()
            .put(LockedUsersHandler.NAME, one.name())
            .put("failures", one.failures())
            .put("locked_at", LockedUsersHandler.iso(one.lockedAt()))
            .put("last_failure_at", LockedUsersHandler.iso(one.lastFailure()))
            .put("client_ip", one.origin().clientIp())
            .put("user_agent", one.origin().userAgent())
            .put("path", one.origin().path())
            .put("credential", one.origin().credential());
    }

    private static String iso(final Instant time) {
        final String res;
        if (time == null) {
            res = null;
        } else {
            res = time.toString();
        }
        return res;
    }

    private static String actor(final RoutingContext ctx) {
        String name = "an administrator";
        if (ctx.user() != null && ctx.user().principal() != null) {
            final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
            if (sub != null && !sub.isEmpty()) {
                name = sub;
            }
        }
        return name;
    }
}
