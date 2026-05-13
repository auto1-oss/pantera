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

import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * Dashboard handler for /api/v1/dashboard/* endpoints.
 *
 * <p>All endpoints serve a shared 5-minute in-memory cache.  A background daemon
 * thread proactively re-reads the materialized views every {@value #BACKGROUND_REFRESH_INTERVAL_S}
 * seconds (30 s before TTL), so virtually every request is answered from memory without
 * touching the database.  Stampede protection via {@link AtomicBoolean} ensures only one
 * thread rebuilds the cache at a time even if the background refresh is delayed.
 *
 * <p><strong>Note:</strong> the underlying PostgreSQL materialized views
 * ({@code mv_artifact_totals}, {@code mv_artifact_per_repo}) must be refreshed on a
 * schedule by {@code pg_cron} — this class does <em>not</em> issue {@code REFRESH}
 * statements.  See {@code docs/admin-guide/installation.md} § "Database Setup" for
 * pg_cron setup instructions.
 */
public final class DashboardHandler {

    /**
     * Cache TTL in milliseconds (5 minutes).
     * Dashboard stats are aggregate views — 5-minute staleness is acceptable and
     * reduces the DB scan frequency by 10x compared to the previous 30-second TTL.
     */
    private static final long CACHE_TTL_MS = 300_000L;

    /**
     * Background refresh interval — 30 s before TTL to keep the cache always warm.
     */
    private static final int BACKGROUND_REFRESH_INTERVAL_S = 270;

    /**
     * Initial delay before the first background refresh (gives the JVM time to warm up).
     */
    private static final int BACKGROUND_INITIAL_DELAY_S = 10;

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
     * Stampede guard: only one thread rebuilds the cache at a time.
     * All other threads serve the stale cache during the rebuild.
     */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /**
     * Background daemon that proactively refreshes the cache before TTL expires.
     * Daemon thread — does not prevent JVM shutdown.
     */
    private final ScheduledExecutorService refresher;

    /**
     * Ctor.
     * @param crs Repository settings CRUD
     * @param dataSource Database data source (nullable)
     */
    public DashboardHandler(final CrudRepoSettings crs, final DataSource dataSource) {
        this.crs = crs;
        this.dataSource = dataSource;
        this.refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "dashboard-cache-refresher");
            t.setDaemon(true);
            return t;
        });
        this.refresher.scheduleAtFixedRate(
            this::backgroundRefresh,
            BACKGROUND_INITIAL_DELAY_S,
            BACKGROUND_REFRESH_INTERVAL_S,
            TimeUnit.SECONDS
        );
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
     * Background refresh: proactively rebuilds the cache before TTL expires.
     * Runs on the dedicated daemon thread every {@value #BACKGROUND_REFRESH_INTERVAL_S} s.
     * Uses the same {@link #rebuilding} CAS guard so it never races with on-demand rebuilds.
     */
    private void backgroundRefresh() {
        if (this.rebuilding.compareAndSet(false, true)) {
            try {
                final CachedDashboard fresh = this.buildDashboard();
                this.cache.set(fresh);
            } catch (final Exception ex) {
                EcsLogger.warn("com.auto1.pantera.api.v1")
                    .message("Background dashboard cache refresh failed")
                    .eventCategory("database")
                    .eventAction("dashboard_cache_refresh")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
            } finally {
                this.rebuilding.set(false);
            }
        }
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
     * Serve a dashboard response from cache.
     *
     * <p>Stampede protection: only one thread rebuilds at a time via {@link #rebuilding}.
     * All concurrent callers receive the stale cache during the rebuild window.
     * If the cache is null (first request ever), all callers wait for the rebuild to finish.
     *
     * @param ctx Routing context
     * @param extractor Function to extract the desired JSON from the cache
     */
    private void respondWithCache(final RoutingContext ctx,
        final java.util.function.Function<CachedDashboard, JsonObject> extractor) {
        CompletableFuture.supplyAsync(() -> {
            final CachedDashboard current = this.cache.get();
            final boolean expired = current == null
                || System.currentTimeMillis() - current.timestamp > CACHE_TTL_MS;
            if (expired && this.rebuilding.compareAndSet(false, true)) {
                // This thread won the rebuild race
                try {
                    final CachedDashboard fresh = this.buildDashboard();
                    this.cache.set(fresh);
                    return extractor.apply(fresh);
                } finally {
                    this.rebuilding.set(false);
                }
            }
            // Serve current cache — either still valid or another thread is rebuilding
            final CachedDashboard cached = this.cache.get();
            if (cached != null) {
                return extractor.apply(cached);
            }
            // First request race: no cache yet and we lost the rebuild CAS —
            // wait briefly for the winner to populate it
            for (int i = 0; i < 50 && this.cache.get() == null; i++) {
                try { Thread.sleep(20); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            final CachedDashboard ready = this.cache.get();
            if (ready != null) {
                return extractor.apply(ready);
            }
            // Fallback: serve empty stats rather than error
            return extractor.apply(emptyDashboard());
        }, HandlerExecutor.get()).whenComplete((json, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(json.encode());
            }
        });
    }

    /**
     * Returns an empty dashboard snapshot for the first-request fallback.
     */
    private static CachedDashboard emptyDashboard() {
        return new CachedDashboard(
            new JsonObject()
                .put("repo_count", 0)
                .put("artifact_count", 0)
                .put("total_storage", 0)
                .put("blocked_count", 0)
                .put("top_repos", new JsonArray()),
            new JsonObject().put("types", new JsonObject()),
            0L
        );
    }

    /**
     * Build the full dashboard data.
     *
     * <p>Reads from {@code mv_artifact_totals} and {@code mv_artifact_per_repo} materialized
     * views — queries are sub-millisecond regardless of table size.  The views are kept
     * current by {@code pg_cron} (see {@code docs/admin-guide/installation.md}).
     *
     * <p>Fallback: if the materialized views do not exist yet (first deployment before DDL is
     * applied), the catch block returns an empty dashboard rather than crashing.
     *
     * @return Cached dashboard snapshot
     */
    private CachedDashboard buildDashboard() {
        final int repoCount = this.crs.listAll().size();
        final JsonObject types = new JsonObject();
        final JsonArray topRepos = new JsonArray();
        long artifactCount = 0;
        long totalStorage = 0;
        long blockedCount = 0;
        if (this.dataSource != null) {
            try (Connection conn = this.dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                // MVs are refreshed externally by pg_cron on a schedule.
                // See docs/admin-guide/performance-tuning.md § "Dashboard Materialized Views".
                // Query 1: global totals (sub-millisecond from MV)
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT artifact_count, total_size FROM mv_artifact_totals"
                )) {
                    if (rs.next()) {
                        artifactCount = rs.getLong("artifact_count");
                        totalStorage = rs.getLong("total_size");
                    }
                }
                // Query 2: blocked count (artifact_cooldowns is small, always direct)
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) AS cnt FROM artifact_cooldowns WHERE status = 'ACTIVE'"
                )) {
                    if (rs.next()) {
                        blockedCount = rs.getLong("cnt");
                    }
                }
                // Query 3: repos by type (sub-millisecond from MV)
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT repo_type, COUNT(DISTINCT repo_name) AS repo_count "
                        + "FROM mv_artifact_per_repo GROUP BY repo_type"
                )) {
                    while (rs.next()) {
                        types.put(rs.getString("repo_type"), rs.getInt("repo_count"));
                    }
                }
                // Query 4: top repos by storage (sub-millisecond from MV)
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT repo_name, repo_type, artifact_count AS cnt, total_size "
                        + "FROM mv_artifact_per_repo "
                        + "ORDER BY total_size DESC, artifact_count DESC LIMIT 5"
                )) {
                    while (rs.next()) {
                        topRepos.add(new JsonObject()
                            .put("name", rs.getString("repo_name"))
                            .put("type", rs.getString("repo_type"))
                            .put("artifact_count", rs.getLong("cnt"))
                            .put("size", rs.getLong("total_size")));
                    }
                }
            } catch (final Exception ex) { // NOPMD EmptyCatchBlock - dashboard is best-effort: DB unavailable or materialized views missing falls through to zeroed counters
                // DB unavailable or MVs not yet created — return zeros
            }
        }
        final JsonObject stats = new JsonObject()
            .put("repo_count", repoCount)
            .put("artifact_count", artifactCount)
            .put("total_storage", totalStorage)
            .put("blocked_count", blockedCount)
            .put("top_repos", topRepos);
        final JsonObject reposByType = new JsonObject().put("types", types);
        return new CachedDashboard(stats, reposByType, System.currentTimeMillis());
    }

    /**
     * Immutable snapshot of dashboard data.
     */
    private record CachedDashboard(JsonObject stats, JsonObject reposByType, long timestamp) {
    }
}
