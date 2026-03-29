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
import com.auto1.pantera.api.ManageRepoSettings;
import com.auto1.pantera.api.perms.ApiRolePermission;
import com.auto1.pantera.cooldown.CooldownSettings;
import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.misc.PanteraProperties;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.JwtSettings;
import com.auto1.pantera.settings.MetricsContext;
import com.auto1.pantera.settings.PrefixesPersistence;
import com.auto1.pantera.settings.Settings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.json.Json;
import javax.sql.DataSource;
import org.eclipse.jetty.http.HttpStatus;

/**
 * Settings handler for /api/v1/settings/* endpoints.
 * Exposes all pantera.yml configuration sections with resolved environment variables.
 * @since 1.21
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle ExecutableStatementCountCheck (500 lines)
 */
public final class SettingsHandler {

    /**
     * Pantera port.
     */
    private final int port;

    /**
     * Pantera settings.
     */
    private final Settings settings;

    /**
     * Repository settings manager.
     */
    private final ManageRepoSettings manageRepo;

    /**
     * Settings DAO for database persistence (nullable).
     */
    private final SettingsDao settingsDao;

    /**
     * Auth provider DAO (nullable).
     */
    private final AuthProviderDao authProviderDao;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param port Pantera port
     * @param settings Pantera settings
     * @param manageRepo Repository settings manager
     * @param dataSource Database data source (nullable)
     * @param policy Security policy
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public SettingsHandler(final int port, final Settings settings,
        final ManageRepoSettings manageRepo, final DataSource dataSource,
        final Policy<?> policy) {
        this.port = port;
        this.settings = settings;
        this.manageRepo = manageRepo;
        this.settingsDao = dataSource != null ? new SettingsDao(dataSource) : null;
        this.authProviderDao = dataSource != null ? new AuthProviderDao(dataSource) : null;
        this.policy = policy;
    }

    /**
     * Register settings routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final ApiRolePermission read =
            new ApiRolePermission(ApiRolePermission.RoleAction.READ);
        final ApiRolePermission update =
            new ApiRolePermission(ApiRolePermission.RoleAction.UPDATE);
        router.get("/api/v1/settings")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::getSettings);
        router.put("/api/v1/settings/prefixes")
            .handler(new AuthzHandler(this.policy, update))
            .handler(this::updatePrefixes);
        router.put("/api/v1/settings/:section")
            .handler(new AuthzHandler(this.policy, update))
            .handler(this::updateSection);
        // Auth provider management
        router.put("/api/v1/auth-providers/:id/toggle")
            .handler(new AuthzHandler(this.policy, update))
            .handler(this::toggleAuthProvider);
        router.put("/api/v1/auth-providers/:id/config")
            .handler(new AuthzHandler(this.policy, update))
            .handler(this::updateAuthProviderConfig);
    }

    /**
     * GET /api/v1/settings — full settings with all sections.
     * @param ctx Routing context
     */
    private void getSettings(final RoutingContext ctx) {
        ctx.vertx().<JsonObject>executeBlocking(
            () -> this.buildFullSettings(),
            false
        ).onSuccess(
            result -> ctx.response()
                .setStatusCode(HttpStatus.OK_200)
                .putHeader("Content-Type", "application/json")
                .end(result.encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * Build the full settings JSON from all sources.
     * @return Complete settings JSON
     */
    private JsonObject buildFullSettings() {
        final JsonObject response = new JsonObject()
            .put("port", this.port)
            .put("version", new PanteraProperties().version());
        // Prefixes
        try {
            response.put("prefixes", new JsonArray(this.settings.prefixes().prefixes()));
        } catch (final Exception ex) {
            response.put("prefixes", new JsonArray());
        }
        // JWT
        final JwtSettings jwt = this.settings.jwtSettings();
        response.put("jwt", new JsonObject()
            .put("expires", jwt.expires())
            .put("expiry_seconds", jwt.expirySeconds())
        );
        // HTTP Client
        final HttpClientSettings hc = this.settings.httpClientSettings();
        response.put("http_client", new JsonObject()
            .put("proxy_timeout", hc.proxyTimeout())
            .put("connection_timeout", hc.connectTimeout())
            .put("idle_timeout", hc.idleTimeout())
            .put("follow_redirects", hc.followRedirects())
            .put("connection_acquire_timeout", hc.connectionAcquireTimeout())
            .put("max_connections_per_destination", hc.maxConnectionsPerDestination())
            .put("max_requests_queued_per_destination", hc.maxRequestsQueuedPerDestination())
        );
        // HTTP Server
        final Duration reqTimeout = this.settings.httpServerRequestTimeout();
        response.put("http_server", new JsonObject()
            .put("request_timeout", reqTimeout.toString())
        );
        // Metrics
        final MetricsContext metrics = this.settings.metrics();
        final JsonObject metricsJson = new JsonObject()
            .put("enabled", metrics.enabled())
            .put("jvm", metrics.jvm())
            .put("http", metrics.http())
            .put("storage", metrics.storage());
        metrics.endpointAndPort().ifPresent(pair -> {
            metricsJson.put("endpoint", pair.getLeft());
            metricsJson.put("port", pair.getRight());
        });
        response.put("metrics", metricsJson);
        // Cooldown
        final CooldownSettings cd = this.settings.cooldown();
        final JsonObject cooldownJson = new JsonObject()
            .put("enabled", cd.enabled())
            .put("minimum_allowed_age", cd.minimumAllowedAge().toString());
        response.put("cooldown", cooldownJson);
        // Credentials / auth providers
        if (this.authProviderDao != null) {
            final List<javax.json.JsonObject> providers = this.authProviderDao.list();
            final JsonArray providersArr = new JsonArray();
            for (final javax.json.JsonObject prov : providers) {
                final JsonObject entry = new JsonObject()
                    .put("id", prov.getInt("id"))
                    .put("type", prov.getString("type"))
                    .put("priority", prov.getInt("priority"))
                    .put("enabled", prov.getBoolean("enabled"));
                // Include safe config (strip secrets, handle nested values)
                final javax.json.JsonObject cfg = prov.getJsonObject("config");
                if (cfg != null) {
                    final JsonObject safeConfig = new JsonObject();
                    for (final String key : cfg.keySet()) {
                        final javax.json.JsonValue jval = cfg.get(key);
                        if (jval.getValueType() == javax.json.JsonValue.ValueType.STRING) {
                            final String val = cfg.getString(key);
                            if (isSecret(key)) {
                                safeConfig.put(key, maskValue(val));
                            } else {
                                safeConfig.put(key, val);
                            }
                        } else if (jval.getValueType() == javax.json.JsonValue.ValueType.OBJECT
                            || jval.getValueType() == javax.json.JsonValue.ValueType.ARRAY) {
                            if (isSecret(key)) {
                                safeConfig.put(key, "***");
                            } else {
                                safeConfig.put(key, jval.toString());
                            }
                        } else {
                            safeConfig.put(key, jval.toString());
                        }
                    }
                    entry.put("config", safeConfig);
                }
                providersArr.add(entry);
            }
            response.put("credentials", providersArr);
        }
        // Database info (connection status, not secrets)
        response.put("database", new JsonObject()
            .put("configured", this.settings.artifactsDatabase().isPresent())
        );
        // Valkey/cache info
        response.put("caches", new JsonObject()
            .put("valkey_configured", this.settings.valkeyConnection().isPresent())
        );
        // UI overrides (persisted via /settings/ui)
        if (this.settingsDao != null) {
            this.settingsDao.get("ui").ifPresent(uiSettings -> {
                final JsonObject uiJson = new JsonObject();
                if (uiSettings.containsKey("grafana_url")) {
                    uiJson.put("grafana_url", uiSettings.getString("grafana_url"));
                }
                if (!uiJson.isEmpty()) {
                    response.put("ui", uiJson);
                }
            });
        }
        return response;
    }

    /**
     * PUT /api/v1/settings/prefixes — update global prefixes.
     * @param ctx Routing context
     */
    private void updatePrefixes(final RoutingContext ctx) {
        try {
            final JsonObject body = ctx.body().asJsonObject();
            if (body == null || !body.containsKey("prefixes")) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Missing 'prefixes' field");
                return;
            }
            final JsonArray prefixesArray = body.getJsonArray("prefixes");
            final List<String> prefixes = new ArrayList<>(prefixesArray.size());
            for (int idx = 0; idx < prefixesArray.size(); idx++) {
                prefixes.add(prefixesArray.getString(idx));
            }
            this.settings.prefixes().update(prefixes);
            new PrefixesPersistence(this.settings.configPath()).save(prefixes);
            // Also persist to database if available
            if (this.settingsDao != null) {
                final String actor = ctx.user() != null
                    ? ctx.user().principal().getString("sub", "system") : "system";
                this.settingsDao.put("prefixes",
                    Json.createObjectBuilder()
                        .add("prefixes", Json.createArrayBuilder(prefixes))
                        .build(),
                    actor
                );
            }
            ctx.response().setStatusCode(HttpStatus.OK_200).end();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", ex.getMessage());
        }
    }

    /**
     * PUT /api/v1/settings/:section — update a specific settings section.
     * Persists to database via SettingsDao.
     * @param ctx Routing context
     */
    private void updateSection(final RoutingContext ctx) {
        final String section = ctx.pathParam("section");
        if ("prefixes".equals(section)) {
            this.updatePrefixes(ctx);
            return;
        }
        if (this.settingsDao == null) {
            ApiResponse.sendError(ctx, 503, "UNAVAILABLE",
                "Database not configured; settings updates require database");
            return;
        }
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final String actor = ctx.user() != null
            ? ctx.user().principal().getString("sub", "system") : "system";
        ctx.vertx().<Void>executeBlocking(
            () -> {
                // Convert vertx JsonObject to javax.json.JsonObject
                final javax.json.JsonObject jobj = Json.createReader(
                    new java.io.StringReader(body.encode())
                ).readObject();
                this.settingsDao.put(section, jobj, actor);
                return null;
            },
            false
        ).onSuccess(
            ignored -> ctx.response().setStatusCode(HttpStatus.OK_200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("status", "saved").encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * PUT /api/v1/auth-providers/:id/toggle — enable or disable an auth provider.
     * @param ctx Routing context
     */
    private void toggleAuthProvider(final RoutingContext ctx) {
        if (this.authProviderDao == null) {
            ApiResponse.sendError(ctx, 503, "UNAVAILABLE",
                "Database not configured");
            return;
        }
        final int providerId;
        try {
            providerId = Integer.parseInt(ctx.pathParam("id"));
        } catch (final NumberFormatException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid provider ID");
            return;
        }
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null || !body.containsKey("enabled")) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Missing 'enabled' field");
            return;
        }
        final boolean enabled = body.getBoolean("enabled");
        ctx.vertx().<Void>executeBlocking(
            () -> {
                if (enabled) {
                    this.authProviderDao.enable(providerId);
                } else {
                    this.authProviderDao.disable(providerId);
                }
                return null;
            },
            false
        ).onSuccess(
            ignored -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("status", "saved").encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * PUT /api/v1/auth-providers/:id/config — update an auth provider's config.
     * @param ctx Routing context
     */
    private void updateAuthProviderConfig(final RoutingContext ctx) {
        if (this.authProviderDao == null) {
            ApiResponse.sendError(ctx, 503, "UNAVAILABLE",
                "Database not configured");
            return;
        }
        final int providerId;
        try {
            providerId = Integer.parseInt(ctx.pathParam("id"));
        } catch (final NumberFormatException ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid provider ID");
            return;
        }
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        ctx.vertx().<Void>executeBlocking(
            () -> {
                final javax.json.JsonObject jobj = Json.createReader(
                    new java.io.StringReader(body.encode())
                ).readObject();
                this.authProviderDao.updateConfig(providerId, jobj);
                return null;
            },
            false
        ).onSuccess(
            ignored -> ctx.response().setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("status", "saved").encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * Check if a config key likely contains a secret.
     * @param key Config key name
     * @return True if secret
     */
    private static boolean isSecret(final String key) {
        final String lower = key.toLowerCase();
        return lower.contains("secret") || lower.contains("password")
            || lower.contains("token") || lower.contains("key");
    }

    /**
     * Mask a secret value, showing only first/last 2 chars if long enough.
     * @param value Original value
     * @return Masked string
     */
    private static String maskValue(final String value) {
        if (value == null || value.isEmpty()) {
            return "***";
        }
        if (value.startsWith("${") && value.endsWith("}")) {
            return value;
        }
        if (value.length() <= 6) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
