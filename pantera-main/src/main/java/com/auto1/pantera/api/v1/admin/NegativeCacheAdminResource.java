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
import com.auto1.pantera.security.policy.Policy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Admin REST resource for negative cache inspection and invalidation.
 *
 * <p>Cluster-wide: listings, probes and invalidations read and write the
 * Valkey L2 tier (the source of truth shared by every node) by cursor SCAN,
 * merged with this node's L1; peers drop their L1 over pub/sub. Without
 * Valkey everything is this node's L1 only, and responses say so
 * ({@code source}). Every response names the answering {@code node}.</p>
 * <ul>
 *   <li>GET  /api/v1/admin/neg-cache — paginated, searchable entry listing</li>
 *   <li>GET  /api/v1/admin/neg-cache/probe — every key a URL could be
 *       shadowed by, or a single key</li>
 *   <li>POST /api/v1/admin/neg-cache/invalidate — single-key invalidation</li>
 *   <li>POST /api/v1/admin/neg-cache/invalidate-package — every entry of a
 *       package, all scopes and tiers</li>
 *   <li>POST /api/v1/admin/neg-cache/invalidate-pattern — pattern
 *       invalidation (rate-limited)</li>
 *   <li>GET  /api/v1/admin/neg-cache/stats — counters and tier sizes</li>
 * </ul>
 *
 * <p>All endpoints require the {@code admin} role via {@link ApiAdminPermission#ADMIN}.
 *
 * @since 2.2.0
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
public final class NegativeCacheAdminResource {

    /**
     * Maximum pattern invalidations per admin user per minute.
     */
    private static final int RATE_LIMIT_PER_MINUTE = 10;

    /**
     * Rate-limit window in milliseconds (1 minute).
     */
    private static final long RATE_WINDOW_MS = 60_000L;

    /**
     * Upper bound on L2 keys a listing or a size count collects.
     */
    private static final int L2_SCAN_LIMIT = 100_000;

    /**
     * JSON field: key.
     */
    private static final String KEY = "key";

    /**
     * JSON field: node.
     */
    private static final String NODE = "node";

    /**
     * Security policy for authorization.
     */
    private final Policy<?> policy;

    /**
     * Shared negative cache instance.
     */
    private final NegativeCache cache;

    /**
     * Serving-side access (topology, node identity).
     */
    private final AdminDiagnostics diag;

    /**
     * Rate-limit tracker: username -> list of timestamps.
     */
    private final ConcurrentHashMap<String, List<Long>> rateLimits;

    /**
     * Ctor without serving-side access (URL probes find no repository).
     * @param policy Security policy
     */
    public NegativeCacheAdminResource(final Policy<?> policy) {
        this(policy, new AdminDiagnostics());
    }

    /**
     * Ctor.
     * @param policy Security policy
     * @param diag Serving-side access
     */
    public NegativeCacheAdminResource(final Policy<?> policy, final AdminDiagnostics diag) {
        this.policy = policy;
        this.cache = NegativeCacheRegistry.instance().sharedCache();
        this.diag = diag;
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
        router.post("/api/v1/admin/neg-cache/invalidate-package")
            .handler(adminAuthz).handler(this::invalidatePackage);
        router.post("/api/v1/admin/neg-cache/invalidate-pattern")
            .handler(adminAuthz).handler(this::invalidatePattern);
        router.get("/api/v1/admin/neg-cache/stats")
            .handler(adminAuthz).handler(this::stats);
    }

    /**
     * GET /api/v1/admin/neg-cache — L2 entries merged with this node's L1.
     * Query params: q (case-insensitive substring over name, version and
     * scope), scope and repoType (exact), page, pageSize.
     * @param ctx Routing context
     */
    private void listEntries(final RoutingContext ctx) {
        final String query = ctx.queryParams().get("q");
        final String scope = ctx.queryParams().get("scope");
        final String type = ctx.queryParams().get("repoType");
        final int page = ApiResponse.intParam(ctx.queryParams().get("page"), 0);
        final int size = ApiResponse.clampSize(
            ApiResponse.intParam(ctx.queryParams().get("pageSize"), 20)
        );
        final Predicate<NegativeCacheKey> filter = nck ->
            NegativeCacheAdminResource.exact(nck.scope(), scope)
                && NegativeCacheAdminResource.exact(nck.repoType(), type)
                && NegativeCacheAdminResource.containsQuery(nck, query);
        this.merged().thenCompose(merged -> {
            final List<Entry> matching = new ArrayList<>();
            for (final Entry entry : merged.entries().values()) {
                if (filter.test(entry.key())) {
                    matching.add(entry);
                }
            }
            final int from = Math.min(page * size, matching.size());
            final List<Entry> slice = matching.subList(
                from, Math.min(from + size, matching.size())
            );
            final List<String> l2flats = slice.stream()
                .filter(Entry::l2).map(entry -> entry.key().flat()).toList();
            return this.cache.l2TtlMillis(l2flats).thenApply(ttls -> {
                final JsonArray items = new JsonArray();
                for (final Entry entry : slice) {
                    items.add(this.item(entry, ttls));
                }
                return ApiResponse.paginated(items, page, size, matching.size())
                    .put("pageSize", size)
                    .put("source", this.cache.hasL2() ? "L2+L1" : "L1-only")
                    .put("truncated", merged.truncated())
                    .put(NODE, this.diag.node());
            });
        }).whenComplete((result, err) -> this.send(ctx, result, err));
    }

    /**
     * GET /api/v1/admin/neg-cache/probe — presence of every key a request
     * could be shadowed by. Forms: {@code url=<client URL or /repo/path>}
     * (every key the producers derive for it), {@code key=<flat key>}
     * (legacy), or {@code scope, repoType, artifactName[, version]}.
     * @param ctx Routing context
     */
    private void probe(final RoutingContext ctx) {
        final String url = ctx.queryParams().get("url");
        final String flat = ctx.queryParams().get(KEY);
        final String scope = ctx.queryParams().get("scope");
        final JsonObject extra = new JsonObject();
        final List<DerivedNegativeKeys.Derived> keys = new ArrayList<>();
        if (url != null && !url.isBlank()) {
            final java.util.Optional<RequestTarget> target;
            try {
                target = new RequestTarget.Parser(this.diag.topology()).parse(url);
            } catch (final IllegalArgumentException ex) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST", ex.getMessage());
                return;
            }
            if (target.isEmpty()) {
                ApiResponse.sendError(ctx, 404, "NOT_FOUND",
                    "No configured repository is addressed by this URL");
                return;
            }
            keys.addAll(new DerivedNegativeKeys(this.diag.topology())
                .keys(target.get().repo(), target.get().path()));
            extra.put("repo", target.get().repo().name())
                .put("repoType", target.get().repo().type())
                .put("path", target.get().path());
        } else if (flat != null && !flat.isBlank()) {
            final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
            if (nck == null) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Key must have format scope:repoType:artifactName:version (URL-encoded)");
                return;
            }
            keys.add(new DerivedNegativeKeys.Derived(nck, "key"));
        } else if (scope != null && !scope.isBlank()
            && ctx.queryParams().get("repoType") != null
            && ctx.queryParams().get("artifactName") != null) {
            keys.add(new DerivedNegativeKeys.Derived(
                new NegativeCacheKey(
                    scope, ctx.queryParams().get("repoType"),
                    ctx.queryParams().get("artifactName"),
                    ctx.queryParams().get("version")
                ), "key"
            ));
        } else {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "One of: url, key, or scope+repoType+artifactName is required");
            return;
        }
        this.presence(keys).thenApply(arr -> {
            boolean shadowed = false;
            for (int idx = 0; idx < arr.size(); idx = idx + 1) {
                final JsonObject item = arr.getJsonObject(idx);
                shadowed = shadowed || item.getBoolean("l1") || item.getBoolean("l2");
            }
            final JsonObject out = new JsonObject()
                .put("keys", arr)
                .put("shadowed", shadowed)
                .put("present", shadowed)
                .put(NODE, this.diag.node())
                .mergeIn(extra);
            if (arr.size() == 1) {
                final JsonArray tiers = new JsonArray();
                if (arr.getJsonObject(0).getBoolean("l1")) {
                    tiers.add("L1");
                }
                if (arr.getJsonObject(0).getBoolean("l2")) {
                    tiers.add("L2");
                }
                out.put("tiers", tiers);
            }
            return out;
        }).whenComplete((result, err) -> this.send(ctx, result, err));
    }

    /**
     * Per-tier presence of keys.
     * @param keys Keys
     * @return Future of {@code [{key, producer, l1, l2, ttlRemainingMs}]}
     */
    private CompletableFuture<JsonArray> presence(final List<DerivedNegativeKeys.Derived> keys) {
        final List<String> flats = keys.stream().map(der -> der.key().flat()).toList();
        return this.cache.l2TtlMillis(flats).thenApply(ttls -> {
            final JsonArray arr = new JsonArray();
            for (final DerivedNegativeKeys.Derived der : keys) {
                final long ttl = ttls.getOrDefault(der.key().flat(), -2L);
                arr.add(new JsonObject()
                    .put(KEY, NegativeCacheAdminResource.keyJson(der.key()))
                    .put("flat", der.key().flat())
                    .put("producer", der.producer())
                    .put("l1", this.cache.inL1(der.key()))
                    .put("l2", ttl != -2L)
                    .put("ttlRemainingMs", ttl >= 0 ? ttl : null));
            }
            return arr;
        });
    }

    /**
     * POST /api/v1/admin/neg-cache/invalidate
     * Body: {scope, repoType, artifactName, version}; version "" is the
     * metadata (version-less) key.
     * @param ctx Routing context
     */
    private void invalidateSingle(final RoutingContext ctx) {
        final JsonObject body = NegativeCacheAdminResource.body(ctx);
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
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
        final NegativeCacheKey nck = new NegativeCacheKey(scope, repoType, artifactName, version);
        final AdminMutation mutation = new AdminMutation(ctx);
        this.cache.invalidateCounted(nck).whenComplete((counts, err) -> {
            mutation.record(
                "neg_cache_invalidate", "CACHE_CLEAR", artifactName,
                "Manual neg-cache invalidation: single key " + nck.flat()
                    + NegativeCacheAdminResource.countsText(counts),
                Map.of(
                    "scope", scope, "repository.type", repoType,
                    "package.name", artifactName, "package.version", version,
                    "l1_invalidated", counts == null ? 0 : counts.l1(),
                    "l2_invalidated", counts == null ? 0 : counts.l2()
                ),
                err
            );
            this.send(ctx, counts == null ? null : this.countsJson(counts), err);
        });
    }

    /**
     * POST /api/v1/admin/neg-cache/invalidate-package
     * Body: {artifactName, repoType?} — every entry of the package under any
     * spelling, in every scope, both tiers, on every node.
     * @param ctx Routing context
     */
    private void invalidatePackage(final RoutingContext ctx) {
        final JsonObject body = NegativeCacheAdminResource.body(ctx);
        final String name = body == null ? null : body.getString("artifactName");
        if (name == null || name.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Field artifactName is required");
            return;
        }
        final String repoType = body.getString("repoType");
        final PackageName pkg = new PackageName(repoType, name);
        final AdminMutation mutation = new AdminMutation(ctx);
        CompletableFuture.supplyAsync(() -> pkg, HandlerExecutor.get())
            .thenCompose(ignored -> this.cache.invalidateMatching(pkg::matches))
            .whenComplete((counts, err) -> {
                mutation.record(
                    "neg_cache_invalidate", "CACHE_CLEAR", name,
                    "Manual neg-cache invalidation: package " + name
                        + " (repo_type=" + repoType + ")"
                        + NegativeCacheAdminResource.countsText(counts),
                    Map.of(
                        "repository.type", repoType == null ? "" : repoType,
                        "package.name", name,
                        "l1_invalidated", counts == null ? 0 : counts.l1(),
                        "l2_invalidated", counts == null ? 0 : counts.l2()
                    ),
                    err
                );
                this.send(ctx, counts == null ? null : this.countsJson(counts), err);
            });
    }

    /**
     * POST /api/v1/admin/neg-cache/invalidate-pattern
     * Body: {scope?, repoType?, artifactName?, version?}; each field is an
     * exact value or a {@code *} glob. Covers L2 (SCAN) and every node.
     * Rate-limited: 10 per minute per admin user.
     * @param ctx Routing context
     */
    private void invalidatePattern(final RoutingContext ctx) {
        final AdminMutation mutation = new AdminMutation(ctx);
        if (!this.checkRateLimit(mutation.actor())) {
            ApiResponse.sendError(ctx, 429, "RATE_LIMITED",
                "Pattern invalidation is limited to "
                    + RATE_LIMIT_PER_MINUTE + " requests per minute");
            return;
        }
        final JsonObject body = NegativeCacheAdminResource.body(ctx);
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final String scope = body.getString("scope");
        final String type = body.getString("repoType");
        final String name = body.getString("artifactName");
        final String version = body.getString("version");
        final Predicate<NegativeCacheKey> match = nck ->
            NegativeCacheAdminResource.matchesFilter(nck.scope(), scope)
                && NegativeCacheAdminResource.matchesFilter(nck.repoType(), type)
                && NegativeCacheAdminResource.matchesFilter(nck.artifactName(), name)
                && NegativeCacheAdminResource.matchesFilter(nck.artifactVersion(), version);
        CompletableFuture.supplyAsync(() -> match, HandlerExecutor.get())
            .thenCompose(this.cache::invalidateMatching)
            .whenComplete((counts, err) -> {
                mutation.record(
                    "neg_cache_invalidate", "CACHE_CLEAR", String.valueOf(name),
                    "Manual neg-cache invalidation: pattern"
                        + " (filter_scope=" + scope
                        + ", filter_type=" + type
                        + ", filter_artifact=" + name
                        + ", filter_version=" + version + ")"
                        + NegativeCacheAdminResource.countsText(counts),
                    Map.of(
                        "scope", String.valueOf(scope),
                        "repository.type", String.valueOf(type),
                        "package.name", String.valueOf(name),
                        "package.version", String.valueOf(version),
                        "l1_invalidated", counts == null ? 0 : counts.l1(),
                        "l2_invalidated", counts == null ? 0 : counts.l2()
                    ),
                    err
                );
                this.send(ctx, counts == null ? null : this.countsJson(counts), err);
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
                .put("requestCount", cstats.requestCount())
                .put("source", this.cache.hasL2() ? "L2+L1" : "L1-only")
                .put(NODE, this.diag.node());
        }, HandlerExecutor.get()).thenCompose(json -> {
            if (!this.cache.hasL2()) {
                return CompletableFuture.completedFuture(
                    json.put("l2Size", (Object) null).put("l2SizeTruncated", false)
                );
            }
            return this.cache.l2Keys(L2_SCAN_LIMIT).thenApply(l2 -> json
                .put("l2Size", l2.flats().size())
                .put("l2SizeTruncated", l2.truncated()));
        }).whenComplete((result, err) -> this.send(ctx, result, err));
    }

    /**
     * This node's L1 merged with L2, keyed by flat key (sorted).
     * @return Future of the merged view
     */
    private CompletableFuture<Merged> merged() {
        return CompletableFuture.supplyAsync(this.cache::l1Keys, HandlerExecutor.get())
            .thenCompose(l1 -> this.cache.l2Keys(L2_SCAN_LIMIT).thenApply(l2 -> {
                final Map<String, Entry> all = new TreeMap<>();
                for (final String flat : l1) {
                    final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
                    if (nck != null) {
                        all.put(flat, new Entry(nck, true, false));
                    }
                }
                for (final String flat : l2.flats()) {
                    final NegativeCacheKey nck = NegativeCacheKey.parse(flat);
                    if (nck != null) {
                        all.merge(flat, new Entry(nck, false, true),
                            (left, right) -> new Entry(left.key(), true, true));
                    }
                }
                return new Merged(all, l2.truncated());
            }));
    }

    /**
     * One listing item.
     * @param entry Entry
     * @param ttls L2 TTLs of the page
     * @return JSON
     */
    private JsonObject item(final Entry entry, final Map<String, Long> ttls) {
        final JsonArray tiers = new JsonArray();
        if (entry.l1()) {
            tiers.add("L1");
        }
        if (entry.l2()) {
            tiers.add("L2");
        }
        Long ttl = null;
        if (entry.l2()) {
            final long raw = ttls.getOrDefault(entry.key().flat(), -2L);
            ttl = raw >= 0 ? raw : null;
        }
        return new JsonObject()
            .put(KEY, NegativeCacheAdminResource.keyJson(entry.key()))
            .put("tiers", tiers)
            .put("tier", entry.l1() ? "L1" : "L2")
            .put("ttlRemainingMs", ttl);
    }

    /**
     * Invalidation counts response (new top-level shape plus the legacy
     * nested one).
     * @param counts Counts
     * @return JSON
     */
    private JsonObject countsJson(final NegativeCache.Invalidation counts) {
        return new JsonObject()
            .put("l1", counts.l1())
            .put("l2", counts.l2())
            .put(NODE, this.diag.node())
            .put("invalidated", new JsonObject().put("l1", counts.l1()).put("l2", counts.l2()));
    }

    /**
     * Send a JSON result or a 500.
     * @param ctx Routing context
     * @param result Result
     * @param err Failure
     */
    private void send(final RoutingContext ctx, final JsonObject result, final Throwable err) {
        if (err != null) {
            final Throwable cause = err instanceof CompletionException && err.getCause() != null
                ? err.getCause() : err;
            ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", String.valueOf(cause.getMessage()));
        } else {
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(result.encode());
        }
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
     * JSON body or null.
     * @param ctx Routing context
     * @return Body
     */
    private static JsonObject body(final RoutingContext ctx) {
        try {
            return ctx.body().asJsonObject();
        } catch (final io.vertx.core.json.DecodeException ex) {
            return null;
        }
    }

    /**
     * Structured key JSON.
     * @param nck Key
     * @return JSON
     */
    private static JsonObject keyJson(final NegativeCacheKey nck) {
        return new JsonObject()
            .put("scope", nck.scope())
            .put("repoType", nck.repoType())
            .put("artifactName", nck.artifactName())
            .put("artifactVersion", nck.artifactVersion());
    }

    /**
     * Counts suffix for log lines.
     * @param counts Counts, may be null
     * @return Text
     */
    private static String countsText(final NegativeCache.Invalidation counts) {
        return counts == null ? "" : " (l1_invalidated=" + counts.l1()
            + ", l2_invalidated=" + counts.l2() + ")";
    }

    /**
     * Exact filter: null/blank matches all.
     * @param value Value
     * @param filter Filter
     * @return Match
     */
    private static boolean exact(final String value, final String filter) {
        return filter == null || filter.isBlank() || value.equals(filter);
    }

    /**
     * Free-text search: case-insensitive substring over name, version and
     * scope, also matched in the canonical name shape so
     * {@code com.example:foo} finds {@code com/example/foo}.
     * @param nck Key
     * @param query Query, null/blank matches all
     * @return Match
     */
    private static boolean containsQuery(final NegativeCacheKey nck, final String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        final String needle = query.trim().toLowerCase(Locale.ROOT);
        final String canon = PackageName.canonical(needle);
        return nck.artifactName().toLowerCase(Locale.ROOT).contains(needle)
            || nck.artifactVersion().toLowerCase(Locale.ROOT).contains(needle)
            || nck.scope().toLowerCase(Locale.ROOT).contains(needle)
            || !canon.isEmpty() && PackageName.canonical(nck.artifactName()).contains(canon);
    }

    /**
     * Pattern filter. {@code null}/empty matches all; a value containing
     * {@code *} is a glob; otherwise exact.
     * @param value Value to check
     * @param filter Filter string (null/empty match all)
     * @return true if matches
     */
    private static boolean matchesFilter(final String value, final String filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (filter.indexOf('*') < 0) {
            return value.equals(filter);
        }
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
     * Merged entry.
     * @param key Key
     * @param l1 In this node's L1
     * @param l2 In L2
     */
    private record Entry(NegativeCacheKey key, boolean l1, boolean l2) {
    }

    /**
     * Merged view.
     * @param entries Entries by flat key
     * @param truncated Whether the L2 scan hit its bound
     */
    private record Merged(Map<String, Entry> entries, boolean truncated) {
    }
}
