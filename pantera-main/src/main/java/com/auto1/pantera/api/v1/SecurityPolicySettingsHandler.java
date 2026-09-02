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

import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.perms.ApiAdminPermission;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.policy.EgressConfig;
import com.auto1.pantera.settings.policy.EgressSettingsLoader;
import com.auto1.pantera.settings.policy.LoginThrottleConfig;
import com.auto1.pantera.settings.policy.LoginThrottleSettingsLoader;
import com.auto1.pantera.settings.policy.RequestLimitsConfig;
import com.auto1.pantera.settings.policy.RequestLimitsSettingsLoader;
import com.auto1.pantera.settings.policy.SecurityPolicySettingsSync;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Admin endpoints for the DB-backed security policy settings (2.2.9):
 * request &amp; storage limits, outbound egress policy, login throttling.
 * Same contract as the breaker/client-base-URL endpoints in {@link
 * AdminAuthHandler}: admin authz, key whitelist per endpoint, partial PUT
 * validated by round-tripping the merged values through the config
 * constructor before anything is written, loader invalidation after the
 * write (and a broadcast to peer nodes) so the change applies everywhere
 * without a restart, and an audit log line.
 *
 * @since 2.2.9
 */
public final class SecurityPolicySettingsHandler {

    /**
     * Keys accepted by the request-limits endpoint.
     */
    private static final Set<String> REQUEST_LIMITS_KEYS = Set.of(
        RequestLimitsSettingsLoader.KEY_MAX_BODY, RequestLimitsSettingsLoader.KEY_FS_ROOTS
    );

    /**
     * Keys accepted by the egress endpoint.
     */
    private static final Set<String> EGRESS_KEYS = Set.of(
        EgressSettingsLoader.KEY_BLOCK_PRIVATE,
        EgressSettingsLoader.KEY_ALLOW_HOSTS,
        EgressSettingsLoader.KEY_CREDENTIAL_HOSTS
    );

    /**
     * Keys accepted by the login-throttle endpoint.
     */
    private static final Set<String> LOGIN_THROTTLE_KEYS = Set.of(
        LoginThrottleSettingsLoader.KEY_MAX_FAILURES, LoginThrottleSettingsLoader.KEY_WINDOW_SECONDS
    );

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.api.v1";

    /**
     * Settings DAO.
     */
    private final AuthSettingsDao settingsDao;

    /**
     * Security policy for authorization checks.
     */
    private final Policy<?> policy;

    /**
     * Cross-node propagation of writes.
     */
    private final SecurityPolicySettingsSync sync;

    /**
     * Ctor.
     * @param settingsDao Auth settings DAO
     * @param policy Security policy
     * @param sync Cross-node propagation of writes
     */
    public SecurityPolicySettingsHandler(
        final AuthSettingsDao settingsDao, final Policy<?> policy, final SecurityPolicySettingsSync sync
    ) {
        this.settingsDao = settingsDao;
        this.policy = policy;
        this.sync = sync;
    }

    /**
     * Register the three GET/PUT pairs; all require admin authorization.
     * @param router Router
     */
    public void register(final Router router) {
        final AuthzHandler adminAuthz = new AuthzHandler(this.policy, ApiAdminPermission.ADMIN);
        this.route(router, adminAuthz, "/api/v1/admin/request-limits-settings", requestLimits());
        this.route(router, adminAuthz, "/api/v1/admin/egress-settings", egress());
        this.route(router, adminAuthz, "/api/v1/admin/login-throttle-settings", loginThrottle());
    }

    /**
     * Request &amp; storage limits section.
     * @return Section
     */
    static Section requestLimits() {
        return new Section(
            "request-limits", "request_limits", REQUEST_LIMITS_KEYS,
            () -> {
                final RequestLimitsConfig current = RequestLimitsSettingsLoader.activeSupplier().get();
                return new JsonObject()
                    .put(RequestLimitsSettingsLoader.KEY_MAX_BODY, String.valueOf(current.maxRequestBodyBytes()))
                    .put(RequestLimitsSettingsLoader.KEY_FS_ROOTS, current.fsStorageRoots());
            },
            merged -> new RequestLimitsConfig(
                Section.parseLong(merged, RequestLimitsSettingsLoader.KEY_MAX_BODY),
                merged.getString(RequestLimitsSettingsLoader.KEY_FS_ROOTS)
            ),
            () -> {
                final RequestLimitsSettingsLoader loader = RequestLimitsSettingsLoader.installed();
                if (loader != null) {
                    loader.invalidate();
                }
            }
        );
    }

    /**
     * Outbound egress section.
     * @return Section
     */
    static Section egress() {
        return new Section(
            "egress", "egress", EGRESS_KEYS,
            () -> {
                final EgressConfig current = EgressSettingsLoader.activeSupplier().get();
                return new JsonObject()
                    .put(EgressSettingsLoader.KEY_BLOCK_PRIVATE, String.valueOf(current.blockPrivate()))
                    .put(EgressSettingsLoader.KEY_ALLOW_HOSTS, EgressConfig.join(current.allowHosts()))
                    .put(EgressSettingsLoader.KEY_CREDENTIAL_HOSTS, EgressConfig.join(current.credentialAllowHosts()));
            },
            merged -> new EgressConfig(
                Section.parseBoolean(merged, EgressSettingsLoader.KEY_BLOCK_PRIVATE),
                merged.getString(EgressSettingsLoader.KEY_ALLOW_HOSTS),
                merged.getString(EgressSettingsLoader.KEY_CREDENTIAL_HOSTS)
            ),
            () -> {
                final EgressSettingsLoader loader = EgressSettingsLoader.installed();
                if (loader != null) {
                    loader.invalidate();
                }
            }
        );
    }

    /**
     * Login throttling section.
     * @return Section
     */
    static Section loginThrottle() {
        return new Section(
            "login-throttle", "login_throttle", LOGIN_THROTTLE_KEYS,
            () -> {
                final LoginThrottleConfig current = LoginThrottleSettingsLoader.activeSupplier().get();
                return new JsonObject()
                    .put(LoginThrottleSettingsLoader.KEY_MAX_FAILURES, String.valueOf(current.maxFailures()))
                    .put(LoginThrottleSettingsLoader.KEY_WINDOW_SECONDS, String.valueOf(current.windowSeconds()));
            },
            merged -> new LoginThrottleConfig(
                Section.parseInt(merged, LoginThrottleSettingsLoader.KEY_MAX_FAILURES),
                Section.parseInt(merged, LoginThrottleSettingsLoader.KEY_WINDOW_SECONDS)
            ),
            () -> {
                final LoginThrottleSettingsLoader loader = LoginThrottleSettingsLoader.installed();
                if (loader != null) {
                    loader.invalidate();
                }
            }
        );
    }

    private void route(
        final Router router, final AuthzHandler authz, final String path, final Section section
    ) {
        router.get(path).handler(authz).handler(ctx -> this.get(ctx, section));
        router.put(path).handler(authz).handler(ctx -> this.update(ctx, section));
    }

    /**
     * GET: every key of the section, all values as strings.
     * @param ctx Routing context
     * @param section Section
     */
    private void get(final RoutingContext ctx, final Section section) {
        CompletableFuture.supplyAsync(section::current, HandlerExecutor.get())
            .whenComplete((settings, err) -> {
                if (err != null) {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                } else {
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(settings.encode());
                }
            });
    }

    /**
     * PUT: partial update; unknown keys and values the config constructor
     * refuses are 400; the write and loader invalidation run off the event
     * loop; success is audit-logged and answers 204.
     * @param ctx Routing context
     * @param section Section
     */
    private void update(final RoutingContext ctx, final Section section) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || body.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        for (final String key : body.fieldNames()) {
            if (!section.keys().contains(key)) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Unknown " + section.name() + " setting: " + key);
                return;
            }
        }
        try {
            section.validate(body);
        } catch (final IllegalArgumentException bad) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Invalid " + section.name() + " setting: " + bad.getMessage());
            return;
        }
        CompletableFuture.runAsync(() -> {
            for (final String key : body.fieldNames()) {
                this.settingsDao.put(key, body.getValue(key).toString().trim());
            }
            section.invalidate().run();
            this.sync.broadcast(section.eventPrefix());
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                EcsLogger.info(LOGGER)
                    .message("Admin updated " + section.name() + " settings (keys="
                        + String.join(",", body.fieldNames()) + ")")
                    .eventCategory("configuration")
                    .eventAction(section.eventPrefix() + "_settings_update")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        });
    }

    /**
     * One settings section: its whitelist, current values, validation of a
     * partial body merged over the current values, and post-write reload.
     * @param name Human name for error messages
     * @param eventPrefix Snake-case audit action prefix
     * @param keys Whitelist
     * @param currentValues Current values, every key present, strings only
     * @param roundTrip Config constructor call over the merged values
     * @param invalidate Loader reload
     */
    record Section(
        String name, String eventPrefix, Set<String> keys, Supplier<JsonObject> currentValues,
        Consumer<JsonObject> roundTrip, Runnable invalidate
    ) {

        /**
         * Current values.
         * @return JSON with every key
         */
        JsonObject current() {
            return this.currentValues.get();
        }

        /**
         * Validate a partial body against the current values.
         * @param body Partial update
         */
        void validate(final JsonObject body) {
            final JsonObject merged = this.current();
            for (final String key : body.fieldNames()) {
                final Object value = body.getValue(key);
                merged.put(key, value == null ? "" : value.toString().trim());
            }
            this.roundTrip.accept(merged);
        }

        private static long parseLong(final JsonObject merged, final String key) {
            try {
                return Long.parseLong(merged.getString(key));
            } catch (final NumberFormatException bad) {
                throw new IllegalArgumentException(key + " must be an integer", bad);
            }
        }

        private static int parseInt(final JsonObject merged, final String key) {
            try {
                return Integer.parseInt(merged.getString(key));
            } catch (final NumberFormatException bad) {
                throw new IllegalArgumentException(key + " must be an integer", bad);
            }
        }

        private static boolean parseBoolean(final JsonObject merged, final String key) {
            final String value = merged.getString(key);
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException(key + " must be true or false");
        }
    }
}
