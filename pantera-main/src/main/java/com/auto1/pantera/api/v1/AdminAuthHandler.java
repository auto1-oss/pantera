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
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.trace.MdcPropagation;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;

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
    }

    /**
     * GET /api/v1/admin/auth-settings — returns all auth_settings as a JSON object.
     * @param ctx Routing context
     */
    private void getSettings(final RoutingContext ctx) {
        ctx.vertx().<JsonObject>executeBlocking(
            MdcPropagation.withMdc(() -> {
                final Map<String, String> all = this.settingsDao.getAll();
                final JsonObject result = new JsonObject();
                for (final Map.Entry<String, String> entry : all.entrySet()) {
                    result.put(entry.getKey(), entry.getValue());
                }
                return result;
            }),
            false
        ).onSuccess(
            settings -> ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(settings.encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
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
        ctx.vertx().<Void>executeBlocking(
            MdcPropagation.withMdc(() -> {
                for (final String key : body.fieldNames()) {
                    this.settingsDao.put(key, body.getValue(key).toString());
                }
                return null;
            }),
            false
        ).onSuccess(ignored -> {
            EcsLogger.info("com.auto1.pantera.api.v1")
                .message("Admin updated auth settings")
                .eventCategory("admin")
                .eventAction("auth_settings_update")
                .eventOutcome("success")
                .field("settings.keys", String.join(",", body.fieldNames()))
                .log();
            ctx.response().setStatusCode(204).end();
        }).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
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
        ctx.vertx().<Integer>executeBlocking(
            MdcPropagation.withMdc(() -> this.tokenDao.revokeAllForUser(username)),
            false
        ).onSuccess(count -> {
            if (this.blocklist != null) {
                this.blocklist.revokeUser(username, REVOKE_USER_TTL_SECONDS);
            }
            EcsLogger.info("com.auto1.pantera.api.v1")
                .message("Admin revoked all tokens for user")
                .eventCategory("admin")
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
        }).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }
}
