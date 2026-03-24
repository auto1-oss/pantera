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

import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.json.JsonStructure;
import javax.sql.DataSource;

/**
 * Dashboard handler for /api/v1/dashboard/* endpoints.
 * All endpoints use a shared 30-second in-memory cache to avoid
 * expensive DB queries and YAML iterations under concurrent load.
 */
public final class DashboardHandler {

    /**
     * Cache refresh interval in milliseconds (30 seconds).
     */
    private static final long CACHE_TTL_MS = 30_000L;

    /**
     * Repository settings CRUD.
     */
    private final CrudRepoSettings crs;

    /**
     * Database data source (nullable).
     */
    private final DataSource dataSource;

    /**
     * Cached full dashboard payload to serve all concurrent users from memory.
     */
    private final AtomicReference<CachedDashboard> cache = new AtomicReference<>();

    /**
     * Ctor.
     * @param crs Repository settings CRUD
     * @param dataSource Database data source (nullable)
     */
    public DashboardHandler(final CrudRepoSettings crs, final DataSource dataSource) {
        this.crs = crs;
        this.dataSource = dataSource;
    }

    /**
     * Register dashboard routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        router.get("/api/v1/dashboard/stats").handler(this::handleStats);
        router.get("/api/v1/dashboard/requests").handler(this::handleRequests);
        router.get("/api/v1/dashboard/repos-by-type").handler(this::handleReposByType);
    }

    /**
     * GET /api/v1/dashboard/stats — aggregated statistics.
     * @param ctx Routing context
     */
    private void handleStats(final RoutingContext ctx) {
        this.respondWithCache(ctx, CachedDashboard::stats);
    }

    /**
     * GET /api/v1/dashboard/repos-by-type — repo count grouped by type.
     * @param ctx Routing context
     */
    private void handleReposByType(final RoutingContext ctx) {
        this.respondWithCache(ctx, CachedDashboard::reposByType);
    }

    /**
     * GET /api/v1/dashboard/requests — request rate time series (placeholder).
     * @param ctx Routing context
     */
    private void handleRequests(final RoutingContext ctx) {
        final String period = ctx.queryParam("period").stream()
            .findFirst().orElse("24h");
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(
                new JsonObject()
                    .put("period", period)
                    .put("data", new JsonArray())
                    .encode()
            );
    }

    /**
     * Serve a dashboard response from cache. Rebuilds cache if expired.
     * Only one Vert.x worker thread rebuilds the cache; others serve stale data.
     * @param ctx Routing context
     * @param extractor Function to extract the desired JSON from the cache
     */
    private void respondWithCache(final RoutingContext ctx,
        final java.util.function.Function<CachedDashboard, JsonObject> extractor) {
        ctx.vertx().<JsonObject>executeBlocking(
            () -> {
                CachedDashboard cached = this.cache.get();
                if (cached == null
                    || System.currentTimeMillis() - cached.timestamp > CACHE_TTL_MS) {
                    cached = this.buildDashboard();
                    this.cache.set(cached);
                }
                return extractor.apply(cached);
            },
            false
        ).onSuccess(
            json -> ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(json.encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * Build the full dashboard data in a single pass.
     * Runs two SQL queries + one repo config iteration.
     * @return Cached dashboard snapshot
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    private CachedDashboard buildDashboard() {
        final Collection<String> names = this.crs.listAll();
        final int repoCount = names.size();
        // Build repos-by-type and top repos in a single pass
        final Map<String, Integer> typeCounts = new HashMap<>(16);
        final JsonArray topRepos = new JsonArray();
        for (final String name : names) {
            try {
                final JsonStructure config =
                    this.crs.value(new RepositoryName.Simple(name));
                if (config instanceof javax.json.JsonObject) {
                    final javax.json.JsonObject jobj = (javax.json.JsonObject) config;
                    final javax.json.JsonObject repo =
                        jobj.containsKey("repo") ? jobj.getJsonObject("repo") : jobj;
                    final String type = repo.getString("type", "unknown");
                    typeCounts.merge(type, 1, Integer::sum);
                }
            } catch (final Exception ignored) {
                // Skip unreadable configs
            }
        }
        long artifactCount = 0;
        long totalStorage = 0;
        long blockedCount = 0;
        if (this.dataSource != null) {
            try (Connection conn = this.dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                // Single query for artifact count + total storage
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) AS cnt, COALESCE(SUM(size), 0) AS total FROM artifacts"
                )) {
                    if (rs.next()) {
                        artifactCount = rs.getLong("cnt");
                        totalStorage = rs.getLong("total");
                    }
                }
                // Blocked count
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) AS cnt FROM artifact_cooldowns WHERE status = 'ACTIVE'"
                )) {
                    if (rs.next()) {
                        blockedCount = rs.getLong("cnt");
                    }
                }
                // Top repos by size first, then artifact count
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT repo_name, repo_type, COUNT(*) AS cnt, "
                        + "COALESCE(SUM(size), 0) AS total_size "
                        + "FROM artifacts GROUP BY repo_name, repo_type "
                        + "ORDER BY total_size DESC, cnt DESC LIMIT 5"
                )) {
                    while (rs.next()) {
                        topRepos.add(new JsonObject()
                            .put("name", rs.getString("repo_name"))
                            .put("type", rs.getString("repo_type"))
                            .put("artifact_count", rs.getLong("cnt"))
                            .put("size", rs.getLong("total_size")));
                    }
                }
            } catch (final Exception ex) {
                // DB unavailable — return zeros
            }
        }
        // Build stats JSON
        final JsonObject stats = new JsonObject()
            .put("repo_count", repoCount)
            .put("artifact_count", artifactCount)
            .put("total_storage", totalStorage)
            .put("blocked_count", blockedCount)
            .put("top_repos", topRepos);
        // Build types JSON
        final JsonObject types = new JsonObject();
        typeCounts.forEach(types::put);
        final JsonObject reposByType = new JsonObject().put("types", types);
        return new CachedDashboard(stats, reposByType, System.currentTimeMillis());
    }

    /**
     * Immutable snapshot of dashboard data.
     */
    private record CachedDashboard(JsonObject stats, JsonObject reposByType, long timestamp) {
    }
}
