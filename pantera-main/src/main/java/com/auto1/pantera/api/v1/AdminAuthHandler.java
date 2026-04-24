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
import com.auto1.pantera.auth.RevocationBlocklist;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Admin-only handler for auth settings management and user token revocation.
 * Registers protected endpoints under /api/v1/admin/.
 * @since 2.1.0
 */
public final class AdminAuthHandler {

    /**
     * Minimum allowed value for access_token_ttl_seconds setting.
     */
    private static final int MIN_ACCESS_TOKEN_TTL = 60;

    /**
     * TTL in seconds for user-level revocation blocklist entries (2 hours).
     */
    private static final int REVOKE_USER_TTL_SECONDS = 7200;

    /**
     * Auth settings DAO.
     */
    private final AuthSettingsDao settingsDao;

    /**
     * User token DAO.
     */
    private final UserTokenDao tokenDao;

    /**
     * Revocation blocklist for in-memory/cache invalidation.
     */
    private final RevocationBlocklist blocklist;

    /**
     * Security policy for authorization checks.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param settingsDao Auth settings DAO
     * @param tokenDao User token DAO
     * @param blocklist Revocation blocklist
     * @param policy Security policy
     */
    public AdminAuthHandler(final AuthSettingsDao settingsDao,
        final UserTokenDao tokenDao, final RevocationBlocklist blocklist,
        final Policy<?> policy) {
        this.settingsDao = settingsDao;
        this.tokenDao = tokenDao;
        this.blocklist = blocklist;
        this.policy = policy;
    }

    /**
     * Register admin auth routes. All require JWT authentication (via global
     * filter) AND admin-level authorization (ApiUserPermission.DELETE).
     * @param router Router
     */
    public void register(final Router router) {
        final AuthzHandler adminAuthz = new AuthzHandler(
            this.policy, ApiAdminPermission.ADMIN
        );
        router.get("/api/v1/admin/auth-settings")
            .handler(adminAuthz).handler(this::getSettings);
        router.put("/api/v1/admin/auth-settings")
            .handler(adminAuthz).handler(this::updateSettings);
        router.post("/api/v1/admin/revoke-user/:username")
            .handler(adminAuthz).handler(this::revokeUser);
        router.get("/api/v1/admin/circuit-breaker-settings")
            .handler(adminAuthz).handler(this::getCircuitBreakerSettings);
        router.put("/api/v1/admin/circuit-breaker-settings")
            .handler(adminAuthz).handler(this::updateCircuitBreakerSettings);
    }

    /**
     * Whitelist of keys the circuit-breaker endpoint accepts.
     * Anything outside this set in a PUT body is rejected with 400 —
     * prevents the endpoint from becoming a generic settings-poke hole.
     */
    private static final java.util.Set<String> CB_KEYS = java.util.Set.of(
        "circuit_breaker_failure_rate_threshold",
        "circuit_breaker_minimum_number_of_calls",
        "circuit_breaker_sliding_window_seconds",
        "circuit_breaker_initial_block_seconds",
        "circuit_breaker_max_block_seconds"
    );

    /**
     * GET /api/v1/admin/circuit-breaker-settings — returns the 5 keys
     * (with DB-persisted values; absent keys fall through to the
     * hardcoded defaults on the server side). Response always includes
     * every key so the UI form can populate without extra null checks.
     */
    private void getCircuitBreakerSettings(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(() -> {
            final com.auto1.pantera.http.timeout.AutoBlockSettings current =
                com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.activeSupplier().get();
            final JsonObject result = new JsonObject()
                .put("circuit_breaker_failure_rate_threshold",
                    String.valueOf(current.failureRateThreshold()))
                .put("circuit_breaker_minimum_number_of_calls",
                    String.valueOf(current.minimumNumberOfCalls()))
                .put("circuit_breaker_sliding_window_seconds",
                    String.valueOf(current.slidingWindowSeconds()))
                .put("circuit_breaker_initial_block_seconds",
                    String.valueOf(current.initialBlockDuration().toSeconds()))
                .put("circuit_breaker_max_block_seconds",
                    String.valueOf(current.maxBlockDuration().toSeconds()));
            return result;
        }, HandlerExecutor.get()).whenComplete((settings, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(settings.encode());
            }
        });
    }

    /**
     * PUT /api/v1/admin/circuit-breaker-settings — partial updates OK;
     * only keys from the {@link #CB_KEYS} whitelist are persisted.
     * Values are validated by round-tripping through the
     * {@link com.auto1.pantera.http.timeout.AutoBlockSettings} record
     * constructor — if the proposed change would produce an invariant
     * violation (rate > 1.0, negative duration, etc.) the PUT is
     * rejected and nothing is written.
     */
    private void updateCircuitBreakerSettings(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || body.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        for (final String key : body.fieldNames()) {
            if (!CB_KEYS.contains(key)) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Unknown circuit-breaker setting: " + key);
                return;
            }
        }
        // Validate: fetch current settings, overlay the proposed changes,
        // round-trip through AutoBlockSettings constructor (which
        // enforces invariants). If that throws, reject the PUT.
        final com.auto1.pantera.http.timeout.AutoBlockSettings current =
            com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.activeSupplier().get();
        try {
            new com.auto1.pantera.http.timeout.AutoBlockSettings(
                Double.parseDouble(body.getString(
                    "circuit_breaker_failure_rate_threshold",
                    String.valueOf(current.failureRateThreshold())
                )),
                Integer.parseInt(body.getString(
                    "circuit_breaker_minimum_number_of_calls",
                    String.valueOf(current.minimumNumberOfCalls())
                )),
                Integer.parseInt(body.getString(
                    "circuit_breaker_sliding_window_seconds",
                    String.valueOf(current.slidingWindowSeconds())
                )),
                java.time.Duration.ofSeconds(Integer.parseInt(body.getString(
                    "circuit_breaker_initial_block_seconds",
                    String.valueOf(current.initialBlockDuration().toSeconds())
                ))),
                java.time.Duration.ofSeconds(Integer.parseInt(body.getString(
                    "circuit_breaker_max_block_seconds",
                    String.valueOf(current.maxBlockDuration().toSeconds())
                )))
            );
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Invalid circuit-breaker setting: " + ex.getMessage());
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            for (final String key : body.fieldNames()) {
                this.settingsDao.put(key, body.getValue(key).toString());
            }
            // Invalidate the shared loader so the next record outcome
            // across every AutoBlockRegistry picks up the new values.
            final com.auto1.pantera.circuit.CircuitBreakerSettingsLoader loader =
                com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.installed();
            if (loader != null) {
                loader.invalidate();
            }
            return null;
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin updated circuit-breaker settings")
                    .eventCategory("configuration")
                    .eventAction("circuit_breaker_settings_update")
                    .eventOutcome("success")
                    .field("pantera.settings.keys", String.join(",", body.fieldNames()))
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        });
    }

    /**
     * GET /api/v1/admin/auth-settings — returns all auth_settings as a JSON object.
     * @param ctx Routing context
     */
    private void getSettings(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(() -> {
            final Map<String, String> all = this.settingsDao.getAll();
            final JsonObject result = new JsonObject();
            for (final Map.Entry<String, String> entry : all.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        }, HandlerExecutor.get()).whenComplete((settings, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(settings.encode());
            }
        });
    }

    /**
     * PUT /api/v1/admin/auth-settings — updates settings from JSON body.
     * Validates access_token_ttl_seconds >= 60 if present.
     * @param ctx Routing context
     */
    private void updateSettings(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || body.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Request body is required");
            return;
        }
        // Validate access_token_ttl_seconds if provided
        if (body.containsKey("access_token_ttl_seconds")) {
            final Object rawTtl = body.getValue("access_token_ttl_seconds");
            final int ttl;
            try {
                ttl = Integer.parseInt(rawTtl.toString());
            } catch (final NumberFormatException ex) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "access_token_ttl_seconds must be an integer");
                return;
            }
            if (ttl < MIN_ACCESS_TOKEN_TTL) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "access_token_ttl_seconds must be >= " + MIN_ACCESS_TOKEN_TTL);
                return;
            }
        }
        CompletableFuture.supplyAsync(() -> {
            for (final String key : body.fieldNames()) {
                this.settingsDao.put(key, body.getValue(key).toString());
            }
            return null;
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin updated auth settings")
                    .eventCategory("iam")
                    .eventAction("auth_settings_update")
                    .eventOutcome("success")
                    .field("pantera.settings.keys", String.join(",", body.fieldNames()))
                    .log();
                ctx.response().setStatusCode(204).end();
            }
        });
    }

    /**
     * POST /api/v1/admin/revoke-user/:username — revokes all tokens for a user in DB
     * and adds the user to the in-memory blocklist for {@value #REVOKE_USER_TTL_SECONDS} seconds.
     * @param ctx Routing context
     */
    private void revokeUser(final RoutingContext ctx) {
        final String username = ctx.pathParam("username");
        if (username == null || username.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Username is required");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> this.tokenDao.revokeAllForUser(username),
            HandlerExecutor.get()
        ).whenComplete((count, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                if (this.blocklist != null) {
                    this.blocklist.revokeUser(username, REVOKE_USER_TTL_SECONDS);
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("Admin revoked all tokens for user")
                    .eventCategory("iam")
                    .eventAction("user_revoke")
                    .eventOutcome("success")
                    .field("user.name", username)
                    .field("revoked_count", count)
                    .log();
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("username", username)
                        .put("revoked_count", count)
                        .encode());
            }
        });
    }
}
