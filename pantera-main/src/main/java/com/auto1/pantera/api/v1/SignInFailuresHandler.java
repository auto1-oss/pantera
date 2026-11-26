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
import com.auto1.pantera.auth.failedlogins.Admission;
import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.LockedName;
import com.auto1.pantera.auth.signin.SignInFailure;
import com.auto1.pantera.auth.signin.SignInFailureStore;
import com.auto1.pantera.auth.signin.SignInFailures;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The sign-in failures report: who fails to sign in, with what, and from
 * where.
 * <ul>
 *   <li>{@code GET /api/v1/sign-in-failures/api-keys} - rejected API keys</li>
 *   <li>{@code GET /api/v1/sign-in-failures/passwords} - failed password
 *   logins, with the count, delay and lock of the user</li>
 *   <li>{@code GET /api/v1/sign-in-failures/form-limit} - logins refused by
 *   the sign-in form's own limit</li>
 *   <li>{@code GET /api/v1/auth/rejected-keys} - the caller's own rejected
 *   API keys</li>
 * </ul>
 * The three lists of all users need the permission to read users and take
 * {@code user} and {@code client_ip} (a part of the value), {@code page}
 * and {@code size}. Every list is the most recent first.
 *
 * @since 2.2.15
 */
public final class SignInFailuresHandler {

    /**
     * Largest page.
     */
    private static final int MAX_SIZE = 200;

    /**
     * Page size when none is asked for.
     */
    private static final int DEFAULT_SIZE = 50;

    /**
     * The report.
     */
    private final SignInFailures report;

    /**
     * Failed logins, for the state of a user.
     */
    private final FailedLogins logins;

    /**
     * Names and dates of API keys by their ids.
     */
    private final Function<Collection<String>, Map<String, KeyName>> keys;

    /**
     * Security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     *
     * @param report The report
     * @param logins Failed logins, for the state of a user
     * @param keys Names and dates of API keys by their ids
     * @param policy Security policy
     */
    public SignInFailuresHandler(
        final SignInFailures report, final FailedLogins logins,
        final Function<Collection<String>, Map<String, KeyName>> keys, final Policy<?> policy
    ) {
        this.report = report;
        this.logins = logins;
        this.keys = keys;
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
        router.get("/api/v1/sign-in-failures/api-keys").handler(read).handler(
            ctx -> this.list(ctx, SignInFailure.API_KEY, "", false)
        );
        router.get("/api/v1/sign-in-failures/passwords").handler(read).handler(
            ctx -> this.list(ctx, SignInFailure.PASSWORD, "", false)
        );
        router.get("/api/v1/sign-in-failures/form-limit").handler(read).handler(
            ctx -> this.list(ctx, SignInFailure.FORM_LIMIT, "", false)
        );
        // Every signed-in user: the rejected keys of that user, no others.
        router.get("/api/v1/auth/rejected-keys").handler(
            ctx -> {
                final String caller = SignInFailuresHandler.caller(ctx);
                if (caller.isEmpty()) {
                    ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Not signed in");
                } else {
                    this.list(ctx, SignInFailure.API_KEY, caller, true);
                }
            }
        );
    }

    /**
     * Answer one list.
     *
     * @param ctx Routing context
     * @param kind Kind of the lines
     * @param owner User whose lines are listed, empty for the user the
     *  request asks for
     * @param exact Whether the user name must be equal
     */
    private void list(
        final RoutingContext ctx, final String kind, final String owner, final boolean exact
    ) {
        final int page = Math.max(SignInFailuresHandler.number(ctx, "page", 0), 0);
        final int size = Math.min(
            Math.max(
                SignInFailuresHandler.number(ctx, "size", SignInFailuresHandler.DEFAULT_SIZE), 1
            ),
            SignInFailuresHandler.MAX_SIZE
        );
        final String user;
        if (exact) {
            user = owner;
        } else {
            user = SignInFailuresHandler.text(ctx, "user");
        }
        final SignInFailureStore.Query query = new SignInFailureStore.Query(
            kind, user, exact, SignInFailuresHandler.text(ctx, "client_ip"), size, page * size
        );
        final Supplier<JsonObject> call = () -> {
            final SignInFailureStore.Page found = this.report.list(query);
            final JsonArray items;
            if (SignInFailure.API_KEY.equals(kind)) {
                items = this.keyItems(found.items());
            } else if (SignInFailure.PASSWORD.equals(kind)) {
                items = this.passwordItems(found.items());
            } else {
                items = SignInFailuresHandler.limitItems(found.items());
            }
            return ApiResponse.paginated(items, page, size, found.total());
        };
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

    private JsonArray keyItems(final List<SignInFailure> lines) {
        final Set<String> ids = new HashSet<>(lines.size());
        for (final SignInFailure line : lines) {
            if (!line.tokenId().isEmpty()) {
                ids.add(line.tokenId());
            }
        }
        final Map<String, KeyName> names;
        if (ids.isEmpty()) {
            names = Map.of();
        } else {
            names = this.keys.apply(ids);
        }
        final JsonArray items = new JsonArray();
        for (final SignInFailure line : lines) {
            final Optional<KeyName> name = Optional.ofNullable(names.get(line.tokenId()));
            items.add(
                SignInFailuresHandler.origin(line)
                    .put("verified", line.verified())
                    .put("key_id", line.tokenId())
                    .put("key_name", name.map(KeyName::label).orElse(null))
                    .put(
                        "key_created_at",
                        name.map(KeyName::createdAt).map(SignInFailuresHandler::iso).orElse(null)
                    )
                    .put(
                        "key_expires_at",
                        name.map(KeyName::expiresAt).map(SignInFailuresHandler::iso).orElse(null)
                    )
                    .put("reason", line.reason())
                    .put("rejected", line.hits())
                    .put("first_rejected_at", SignInFailuresHandler.iso(line.firstSeen()))
                    .put("last_rejected_at", SignInFailuresHandler.iso(line.lastSeen()))
            );
        }
        return items;
    }

    private JsonArray passwordItems(final List<SignInFailure> lines) {
        final Map<String, JsonObject> states = new HashMap<>();
        final JsonArray items = new JsonArray();
        for (final SignInFailure line : lines) {
            items.add(
                SignInFailuresHandler.origin(line)
                    .put("failed", line.hits())
                    .put("refused", line.refused())
                    .put("last_reason", line.reason())
                    .put("first_failure_at", SignInFailuresHandler.iso(line.firstSeen()))
                    .put("last_failure_at", SignInFailuresHandler.iso(line.lastSeen()))
                    .put("state", states.computeIfAbsent(line.username(), this::state))
            );
        }
        return items;
    }

    /**
     * What applies to a user now: the count, a delay, a lock, and the
     * last unlock by an administrator.
     *
     * @param name User name
     * @return State
     */
    private JsonObject state(final String name) {
        final Optional<LockedName> entry = this.logins.details(name);
        final Admission admission = this.logins.admission(name);
        final JsonObject state = new JsonObject()
            .put("failures", entry.map(LockedName::failures).orElse(0))
            .put("locked", this.logins.passwordLocked(name))
            .put(
                "locked_at",
                entry.map(LockedName::lockedAt).map(SignInFailuresHandler::iso).orElse(null)
            );
        if (admission.kind() == Admission.Kind.DELAYED) {
            state.put("delayed_seconds", admission.seconds());
        } else {
            state.put("delayed_seconds", 0L);
        }
        final List<SignInFailure> unlocks = this.report.list(
            new SignInFailureStore.Query(SignInFailure.UNLOCK, name, true, "", 1, 0)
        ).items();
        if (unlocks.isEmpty()) {
            state.put("unlocked_at", (String) null).put("unlocked_by", (String) null);
        } else {
            state.put("unlocked_at", SignInFailuresHandler.iso(unlocks.get(0).lastSeen()))
                .put("unlocked_by", unlocks.get(0).actor());
        }
        return state;
    }

    private static JsonArray limitItems(final List<SignInFailure> lines) {
        final Instant now = Instant.now();
        final JsonArray items = new JsonArray();
        for (final SignInFailure line : lines) {
            items.add(
                SignInFailuresHandler.origin(line)
                    .put("hits", line.hits())
                    .put("failures", line.failures())
                    .put("blocked_until", SignInFailuresHandler.iso(line.blockedUntil()))
                    .put(
                        "blocked",
                        line.blockedUntil() != null && line.blockedUntil().isAfter(now)
                    )
                    .put("first_hit_at", SignInFailuresHandler.iso(line.firstSeen()))
                    .put("last_hit_at", SignInFailuresHandler.iso(line.lastSeen()))
            );
        }
        return items;
    }

    private static JsonObject origin(final SignInFailure line) {
        return new JsonObject()
            .put("user", line.username())
            .put("client_ip", line.clientIp())
            .put("user_agent", line.userAgent())
            .put("path", line.path());
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

    private static String text(final RoutingContext ctx, final String name) {
        return ctx.queryParam(name).stream().findFirst().orElse("");
    }

    private static int number(final RoutingContext ctx, final String name, final int fallback) {
        int res = fallback;
        try {
            final String raw = SignInFailuresHandler.text(ctx, name);
            if (!raw.isEmpty()) {
                res = Integer.parseInt(raw.trim());
            }
        } catch (final NumberFormatException bad) {
            res = fallback;
        }
        return res;
    }

    private static String caller(final RoutingContext ctx) {
        String name = "";
        if (ctx.user() != null && ctx.user().principal() != null) {
            final String sub = ctx.user().principal().getString(AuthTokenRest.SUB);
            if (sub != null) {
                name = sub;
            }
        }
        return name;
    }

    /**
     * Name and dates of an API key.
     *
     * @param label Name the owner gave the key
     * @param createdAt When it was created
     * @param expiresAt When it expires, null for a key without an end
     * @since 2.2.15
     */
    public record KeyName(String label, Instant createdAt, Instant expiresAt) {
    }
}
