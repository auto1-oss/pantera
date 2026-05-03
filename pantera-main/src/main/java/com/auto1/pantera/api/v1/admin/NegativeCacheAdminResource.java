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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.perms.ApiAdminPermission;
import com.auto1.pantera.api.v1.ApiResponse;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.cache.NegativeCacheRegistry;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.security.policy.Policy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admin REST resource for negative cache inspection and invalidation.
 *
 * <p>Provides five endpoints under {@code /api/v1/admin/neg-cache/} for
 * platform engineers to investigate 404-shadow reports without SSH access:
 * <ul>
 *   <li>GET  /api/v1/admin/neg-cache — paginated L1 entry listing</li>
 *   <li>GET  /api/v1/admin/neg-cache/probe — single-key presence check</li>
 *   <li>POST /api/v1/admin/neg-cache/invalidate — single-key invalidation</li>
 *   <li>POST /api/v1/admin/neg-cache/invalidate-pattern — pattern invalidation (rate-limited)</li>
 *   <li>GET  /api/v1/admin/neg-cache/stats — per-scope hit/miss/size counters</li>
 * </ul>
 *
 * <p>All endpoints require the {@code admin} role via {@link ApiAdminPermission#ADMIN}.
 *
 * @since 2.2.0
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports"})
public final class NegativeCacheAdminResource {

    /**
     * Logger name for this resource.
     */
    private static final String LOGGER =
        "com.auto1.pantera.api.v1.admin";

    /**
     * Maximum pattern invalidations per admin user per minute.
     */
    private static final int RATE_LIMIT_PER_MINUTE = 10;

    /**
     * Rate-limit window in milliseconds (1 minute).
     */
    private static final long RATE_WINDOW_MS = 60_000L;

    /**
     * Security policy for authorization.
     */
    private final Policy<?> policy;

    /**
     * Shared negative cache instance.
     */
    private final NegativeCache cache;

    /**
     * Rate-limit tracker: username -> list of timestamps.
     */
    private final ConcurrentHashMap<String, List<Long>> rateLimits;

    /**
     * Ctor.
     * @param policy Security policy
     */
    public NegativeCacheAdminResource(final Policy<?> policy) {
        this.policy = policy;
        this.cache = NegativeCacheRegistry.instance().sharedCache();
        this.rateLimits = new ConcurrentHashMap<>();
    }

    /**
     * Register neg-cache admin routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final AuthzHandler adminAuthz = new AuthzHandler(
            this.policy, ApiAdminPermission.ADMIN
        );
        router.get("/api/v1/admin/neg-cache")
            .handler(adminAuthz).handler(this::listEntries);
        router.get("/api/v1/admin/neg-cache/probe")
            .handler(adminAuthz).handler(this::probe);
        router.post("/api/v1/admin/neg-cache/invalidate")
            .handler(adminAuthz).handler(this::invalidateSingle);
        router.post("/api/v1/admin/neg-cache/invalidate-pattern")
            .handler(adminAuthz).handler(this::invalidatePattern);
        router.get("/api/v1/admin/neg-cache/stats")
            .handler(adminAuthz).handler(this::stats);
    }

    /**
     * GET /api/v1/admin/neg-cache — paginated list of L1 entries.
     * Query params: scope, repoType, artifactName, version, page, pageSize.
     * @param ctx Routing context
     */
    private void listEntries(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(() -> {
            final String filterScope = ctx.queryParams().get("scope");
            final String filterType = ctx.queryParams().get("repoType");
            final String filterName = ctx.queryParams().get("artifactName");
            final String filterVersion = ctx.queryParams().get("version");
            final int page = ApiResponse.intParam(
                ctx.queryParams().get("page"), 0
            );
            final int pageSize = ApiResponse.clampSize(
                ApiResponse.intParam(ctx.queryParams().get("pageSize"), 20)
            );
            final Cache<String, Boolean> l1Cache = extractL1Cache(this.cache);
            final List<JsonObject> entries = new ArrayList<>();
            if (l1Cache != null) {
                for (final String flat : l1Cache.asMap().keySet()) {
                    final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
                    if (nck == null) {
                        continue;
                    }
                    if (!matchesFilter(nck.scope(), filterScope)
                        || !matchesFilter(nck.repoType(), filterType)
                        || !matchesFilter(nck.artifactName(), filterName)
                        || !matchesFilter(nck.artifactVersion(), filterVersion)) {
                        continue;
                    }
                    entries.add(new JsonObject()
                        .put("key", new JsonObject()
                            .put("scope", nck.scope())
                            .put("repoType", nck.repoType())
                            .put("artifactName", nck.artifactName())
                            .put("artifactVersion", nck.artifactVersion()))
                        .put("tier", "L1")
                        .put("ttlRemainingMs", -1L)
                    );
                }
            }
            final int total = entries.size();
            final JsonArray page1 = ApiResponse.sliceToArray(entries, page, pageSize);
            return ApiResponse.paginated(page1, page, pageSize, total);
        }, HandlerExecutor.get()).whenComplete((result, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        });
    }

    /**
     * GET /api/v1/admin/neg-cache/probe?key=scope:type:name:version
     * Returns presence check across tiers.
     * @param ctx Routing context
     */
    private void probe(final RoutingContext ctx) {
        final String keyParam = ctx.queryParams().get("key");
        if (keyParam == null || keyParam.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Query param 'key' is required (format: scope:type:name:version)");
            return;
        }
        final NegativeCacheKey nck = NegativeCacheKey.parse(keyParam);
        if (nck == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Key must have format scope:repoType:artifactName:version (URL-encoded)");
            return;
        }
        this.cache.isKnown404Async(nck).whenComplete((found, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    err.getMessage());
                return;
            }
            final JsonObject response = new JsonObject()
                .put("present", found);
            if (found) {
                final JsonArray tiers = new JsonArray();
                // Check L1 synchronously
                if (this.cache.isKnown404(nck)) {
                    tiers.add("L1");
                }
                // isKnown404Async already checked L1+L2; if found but not
                // in L1 alone, it was promoted from L2
                if (tiers.isEmpty()) {
                    tiers.add("L2");
                }
                response.put("tiers", tiers);
            }
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        });
    }

    /**
     * POST /api/v1/admin/neg-cache/invalidate
     * Body: {scope, repoType, artifactName, version}
     * @param ctx Routing context
     */
    private void invalidateSingle(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "JSON body is required");
            return;
        }
        final String scope = body.getString("scope");
        final String repoType = body.getString("repoType");
        final String artifactName = body.getString("artifactName");
        final String version = body.getString("version", "");
        if (scope == null || repoType == null || artifactName == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Fields scope, repoType, artifactName are required");
            return;
        }
        final NegativeCacheKey nck = new NegativeCacheKey(
            scope, repoType, artifactName, version
        );
        final boolean wasInL1 = this.cache.isKnown404(nck);
        this.cache.invalidate(nck);
        final String user = extractUsername(ctx);
        EcsLogger.warn(LOGGER)
            .message("Manual neg-cache invalidation: single key"
                + " (scope=" + scope
                + ", repo_type=" + repoType
                + ", artifact=" + artifactName
                + ", version=" + version
                + ", l1_invalidated=" + (wasInL1 ? 1 : 0) + ")")
            .eventCategory("configuration")
            .eventAction("neg_cache_invalidate")
            .eventOutcome("success")
            .field("user.name", user)
            .log();
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("invalidated", new JsonObject()
                    .put("l1", wasInL1 ? 1 : 0)
                    .put("l2", wasInL1 ? 1 : 0))
                .encode());
    }

    /**
     * POST /api/v1/admin/neg-cache/invalidate-pattern
     * Body: {scope?, repoType?, artifactName?, version?}
     * Rate-limited: 10 per minute per admin user.
     * @param ctx Routing context
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    private void invalidatePattern(final RoutingContext ctx) {
        final String user = extractUsername(ctx);
        if (!checkRateLimit(user)) {
            ApiResponse.sendError(ctx, 429, "RATE_LIMITED",
                "Pattern invalidation is limited to "
                    + RATE_LIMIT_PER_MINUTE + " requests per minute");
            return;
        }
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "JSON body is required");
            return;
        }
        final String filterScope = body.getString("scope");
        final String filterType = body.getString("repoType");
        final String filterName = body.getString("artifactName");
        final String filterVersion = body.getString("version");
        CompletableFuture.supplyAsync(() -> {
            final Cache<String, Boolean> l1Cache = extractL1Cache(this.cache);
            final AtomicInteger l1Count = new AtomicInteger(0);
            final List<NegativeCacheKey> keysToInvalidate = new ArrayList<>();
            if (l1Cache != null) {
                for (final String flat : new ArrayList<>(l1Cache.asMap().keySet())) {
                    final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
                    if (nck == null) {
                        continue;
                    }
                    if (matchesFilter(nck.scope(), filterScope)
                        && matchesFilter(nck.repoType(), filterType)
                        && matchesFilter(nck.artifactName(), filterName)
                        && matchesFilter(nck.artifactVersion(), filterVersion)) {
                        keysToInvalidate.add(nck);
                        l1Count.incrementAndGet();
                    }
                }
            }
            if (!keysToInvalidate.isEmpty()) {
                this.cache.invalidateBatch(keysToInvalidate).join();
            }
            return new int[]{l1Count.get(), l1Count.get()};
        }, HandlerExecutor.get()).whenComplete((counts, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    err.getMessage());
                return;
            }
            EcsLogger.warn(LOGGER)
                .message("Manual neg-cache invalidation: pattern"
                    + " (filter_scope=" + filterScope
                    + ", filter_type=" + filterType
                    + ", filter_artifact=" + filterName
                    + ", filter_version=" + filterVersion
                    + ", l1_invalidated=" + counts[0]
                    + ", l2_invalidated=" + counts[1] + ")")
                .eventCategory("configuration")
                .eventAction("neg_cache_invalidate")
                .eventOutcome("success")
                .field("user.name", user)
                .log();
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("invalidated", new JsonObject()
                        .put("l1", counts[0])
                        .put("l2", counts[1]))
                    .encode());
        });
    }

    /**
     * GET /api/v1/admin/neg-cache/stats — cache statistics.
     * @param ctx Routing context
     */
    private void stats(final RoutingContext ctx) {
        CompletableFuture.supplyAsync(() -> {
            final CacheStats cstats = this.cache.stats();
            return new JsonObject()
                .put("enabled", this.cache.isEnabled())
                .put("l1Size", this.cache.size())
                .put("hitCount", cstats.hitCount())
                .put("missCount", cstats.missCount())
                .put("hitRate", cstats.hitRate())
                .put("evictionCount", cstats.evictionCount())
                .put("requestCount", cstats.requestCount());
        }, HandlerExecutor.get()).whenComplete((result, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR",
                    err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        });
    }

    /**
     * Check and record rate limit for pattern invalidation.
     * @param user Username
     * @return true if within limit, false if exceeded
     */
    private boolean checkRateLimit(final String user) {
        final long now = System.currentTimeMillis();
        final List<Long> timestamps = this.rateLimits.computeIfAbsent(
            user, k -> new ArrayList<>()
        );
        synchronized (timestamps) {
            timestamps.removeIf(ts -> now - ts > RATE_WINDOW_MS);
            if (timestamps.size() >= RATE_LIMIT_PER_MINUTE) {
                return false;
            }
            timestamps.add(now);
            return true;
        }
    }

    /**
     * Extract the L1 Caffeine cache from NegativeCache via reflection.
     * This is an admin-only diagnostic operation; reflection is acceptable.
     * @param negCache NegativeCache instance
     * @return The underlying Caffeine cache, or null if inaccessible
     */
    @SuppressWarnings("unchecked")
    private static Cache<String, Boolean> extractL1Cache(
        final NegativeCache negCache
    ) {
        try {
            final Field field = NegativeCache.class.getDeclaredField(
                "notFoundCache"
            );
            field.setAccessible(true);
            return (Cache<String, Boolean>) field.get(negCache);
        } catch (final NoSuchFieldException | IllegalAccessException ex) {
            EcsLogger.warn(LOGGER)
                .message("Cannot access L1 cache for admin listing")
                .error(ex)
                .log();
            return null;
        }
    }

    /**
     * Check if a value matches a filter. Filter semantics:
     * <ul>
     *   <li>{@code null} or empty → match all</li>
     *   <li>contains {@code *} → glob (e.g. {@code "github.com/*"} matches prefixes)</li>
     *   <li>otherwise → exact match</li>
     * </ul>
     * No more {@code String.contains} — that produced runaway matches across
     * unrelated entries when operators typed substrings.
     * @param value Value to check
     * @param filter Filter string (null/empty match all)
     * @return true if matches
     */
    private static boolean matchesFilter(
        final String value, final String filter
    ) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (filter.indexOf('*') < 0) {
            return value.equals(filter);
        }
        // Treat * as the only wildcard; escape regex metacharacters in the rest.
        final StringBuilder rx = new StringBuilder(filter.length() + 8);
        for (int i = 0; i < filter.length(); i++) {
            final char c = filter.charAt(i);
            if (c == '*') {
                rx.append(".*");
            } else if ("\\.^$|?+()[]{}".indexOf(c) >= 0) {
                rx.append('\\').append(c);
            } else {
                rx.append(c);
            }
        }
        return value.matches(rx.toString());
    }

    /**
     * Extract username from routing context.
     * @param ctx Routing context
     * @return Username or "unknown"
     */
    private static String extractUsername(final RoutingContext ctx) {
        if (ctx.user() != null && ctx.user().principal() != null) {
            return ctx.user().principal().getString("sub", "unknown");
        }
        return "unknown";
    }
}
