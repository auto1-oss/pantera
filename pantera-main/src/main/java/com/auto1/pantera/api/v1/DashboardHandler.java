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
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.perms.FreePermissions;
import com.auto1.pantera.security.policy.Policy;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Number of repositories listed in {@code top_repos}.
     */
    private static final int TOP_REPOS = 5;

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
     * Pantera security policy. SECURITY (2.2.9): the snapshot is global and
     * authorization-insensitive; every response is PROJECTED through the
     * caller's per-repository read scope before it leaves this handler.
     */
    private final Policy<?> policy;

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
     * @param policy Pantera security policy
     */
    public DashboardHandler(
        final CrudRepoSettings crs, final DataSource dataSource, final Policy<?> policy
    ) {
        this.crs = crs;
        this.dataSource = dataSource;
        this.policy = policy;
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
                    .field("log.source", "application")
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
        this.respondWithCache(ctx, DashboardHandler::stats);
    }

    /**
     * GET /api/v1/dashboard/repos-by-type — repo count grouped by type.
     * @param ctx Routing context
     */
    private void handleReposByType(final RoutingContext ctx) {
        this.respondWithCache(ctx, DashboardHandler::reposByType);
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
        final java.util.function.BiFunction<CachedDashboard, Set<String>, JsonObject> projector) {
        final io.vertx.ext.auth.User usr = ctx.user();
        if (usr == null) {
            ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Authentication required");
            return;
        }
        final AuthUser caller = new AuthUser(
            usr.principal().getString(AuthTokenRest.SUB),
            usr.principal().getString(AuthTokenRest.CONTEXT)
        );
        final java.util.function.Function<CachedDashboard, JsonObject> extractor = snapshot ->
            projector.apply(snapshot, this.readableRepositories(caller));
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
                    // EXPECTED: shutdown signalled — restore interrupt
                    // and exit the spin-wait. Cache may still be empty;
                    // the caller falls back to zeros.
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
        return new CachedDashboard(List.of(), Map.of(), List.of(), 0L);
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
        final List<RepoRow> rows = new ArrayList<>();
        final Map<String, Long> blocked = new HashMap<>();
        if (this.dataSource != null) {
            try (Connection conn = this.dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                // MVs are refreshed externally by pg_cron on a schedule.
                // See docs/admin-guide/performance-tuning.md § "Dashboard Materialized Views".
                // Every per-repo row is cached (not a pre-aggregated global)
                // so each response can be projected through the caller's
                // repository scope — SECURITY (2.2.9): the old global top-5
                // / totals leaked names, types and sizes of repositories the
                // caller could not read.
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT repo_name, repo_type, artifact_count AS cnt, total_size "
                        + "FROM mv_artifact_per_repo"
                )) {
                    while (rs.next()) {
                        rows.add(new RepoRow(
                            rs.getString("repo_name"), rs.getString("repo_type"),
                            rs.getLong("cnt"), rs.getLong("total_size")
                        ));
                    }
                }
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT repo_name, COUNT(*) AS cnt FROM artifact_cooldowns "
                        + "WHERE status = 'ACTIVE' GROUP BY repo_name"
                )) {
                    while (rs.next()) {
                        blocked.put(rs.getString("repo_name"), rs.getLong("cnt"));
                    }
                }
            } catch (final Exception ex) { // NOPMD EmptyCatchBlock - dashboard is best-effort: DB unavailable or materialized views missing falls through to zeroed counters
                // EXPECTED: DB unavailable or MVs not yet created —
                // return zeros (documented in CLAUDE.md as a known
                // deployment prerequisite, not a bug).
            }
        }
        return new CachedDashboard(
            List.copyOf(rows), Map.copyOf(blocked), List.copyOf(this.crs.listAll()),
            System.currentTimeMillis()
        );
    }

    /**
     * Repositories the caller may read: {@code null} = unrestricted
     * (FreePermissions or a genuine wildcard read), else the readable subset
     * of the configured repositories (possibly empty).
     * @param caller Authenticated principal
     * @return Readable repository names, or {@code null} for unrestricted
     */
    private Set<String> readableRepositories(final AuthUser caller) {
        final PermissionCollection perms = this.policy.getPermissions(caller);
        if (perms instanceof FreePermissions
            || perms.implies(new AdapterBasicPermission("*", "read"))) {
            return null; // NOPMD ReturnEmptyCollectionRatherThanNull - null is the "no restriction" scope; an empty set is a genuine deny-all
        }
        final Set<String> readable = new HashSet<>();
        for (final String name : this.crs.listAll()) {
            if (perms.implies(new AdapterBasicPermission(name, "read"))) {
                readable.add(name);
            }
        }
        return readable;
    }

    /**
     * Project the snapshot's stats through a scope.
     * @param snapshot Cached rows
     * @param scope Readable repositories ({@code null} = all)
     * @return Scoped stats payload
     */
    private static JsonObject stats(final CachedDashboard snapshot, final Set<String> scope) {
        long artifacts = 0;
        long storage = 0;
        long blockedCount = 0;
        final List<RepoRow> visible = new ArrayList<>();
        for (final RepoRow row : snapshot.rows()) {
            if (scope == null || scope.contains(row.name())) {
                visible.add(row);
                artifacts += row.count();
                storage += row.size();
            }
        }
        for (final Map.Entry<String, Long> entry : snapshot.blocked().entrySet()) {
            if (scope == null || scope.contains(entry.getKey())) {
                blockedCount += entry.getValue();
            }
        }
        visible.sort(Comparator.comparingLong(RepoRow::size).reversed()
            .thenComparing(Comparator.comparingLong(RepoRow::count).reversed()));
        final JsonArray top = new JsonArray();
        for (final RepoRow row : visible.subList(0, Math.min(TOP_REPOS, visible.size()))) {
            top.add(new JsonObject()
                .put("name", row.name())
                .put("type", row.type())
                .put("artifact_count", row.count())
                .put("size", row.size()));
        }
        long repoCount = 0;
        for (final String name : snapshot.configured()) {
            if (scope == null || scope.contains(name)) {
                repoCount += 1;
            }
        }
        return new JsonObject()
            .put("repo_count", repoCount)
            .put("artifact_count", artifacts)
            .put("total_storage", storage)
            .put("blocked_count", blockedCount)
            .put("top_repos", top);
    }

    /**
     * Project the repos-by-type payload through a scope.
     * @param snapshot Cached rows
     * @param scope Readable repositories ({@code null} = all)
     * @return Scoped repos-by-type payload
     */
    private static JsonObject reposByType(final CachedDashboard snapshot, final Set<String> scope) {
        final Map<String, Set<String>> byType = new HashMap<>();
        for (final RepoRow row : snapshot.rows()) {
            if (scope == null || scope.contains(row.name())) {
                byType.computeIfAbsent(row.type(), k -> new HashSet<>()).add(row.name());
            }
        }
        final JsonObject types = new JsonObject();
        for (final Map.Entry<String, Set<String>> entry : byType.entrySet()) {
            types.put(entry.getKey(), entry.getValue().size());
        }
        return new JsonObject().put("types", types);
    }

    /**
     * Immutable snapshot of dashboard data.
     */
    private record CachedDashboard(
        List<RepoRow> rows, Map<String, Long> blocked, List<String> configured, long timestamp
    ) {
    }

    /**
     * One repository's materialised-view row.
     * @param name Repository name
     * @param type Repository type
     * @param count Artifact count
     * @param size Total size in bytes
     */
    private record RepoRow(String name, String type, long count, long size) {
    }
}
