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
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.api.perms.ApiCooldownPermission;
import com.auto1.pantera.cooldown.CooldownRepository;
import com.auto1.pantera.cooldown.DbBlockRecord;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metrics.CooldownMetrics;
import com.auto1.pantera.db.dao.SettingsDao;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.http.observability.StructuredLogger;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import javax.sql.DataSource;

/**
 * Cooldown handler for /api/v1/cooldown/* endpoints.
 * @since 1.21.0
 * @checkstyle ClassDataAbstractionCouplingCheck (300 lines)
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class CooldownHandler {

    /**
     * Logger component name for StructuredLogger.local() (Tier-4).
     */
    private static final String LOG_COMPONENT = "com.auto1.pantera.cooldown.admin";

    /**
     * JSON key for repo section.
     */
    private static final String REPO = "repo";

    /**
     * JSON key for type field.
     */
    private static final String TYPE = "type";

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown metadata service (for filtered metadata cache invalidation on unblock).
     */
    private final CooldownMetadataService metadataService;

    /**
     * Cooldown decision cache (for L1+L2 invalidation on unblock).
     * May be null when cooldown is backed by NoopCooldownService.
     */
    private final CooldownCache cooldownCache;

    /**
     * Repository settings CRUD.
     */
    private final CrudRepoSettings crs;

    /**
     * Cooldown settings from pantera.yml.
     */
    private final CooldownSettings csettings;

    /**
     * Cooldown repository for direct DB queries (nullable).
     */
    private final CooldownRepository repository;

    /**
     * Settings DAO for persisting cooldown config (nullable).
     */
    private final SettingsDao settingsDao;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param cooldown Cooldown service
     * @param metadataService Cooldown metadata service for cache invalidation
     * @param cooldownCache Cooldown decision cache (nullable)
     * @param crs Repository settings CRUD
     * @param csettings Cooldown settings
     * @param dataSource Database data source (nullable)
     * @param policy Security policy
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public CooldownHandler(final CooldownService cooldown,
        final CooldownMetadataService metadataService,
        final CooldownCache cooldownCache,
        final CrudRepoSettings crs,
        final CooldownSettings csettings, final DataSource dataSource,
        final Policy<?> policy) {
        this.cooldown = cooldown;
        this.metadataService = metadataService;
        this.cooldownCache = cooldownCache;
        this.crs = crs;
        this.csettings = csettings;
        this.repository = dataSource != null ? new CooldownRepository(dataSource) : null;
        this.settingsDao = dataSource != null ? new SettingsDao(dataSource) : null;
        this.policy = policy;
    }

    /**
     * Backward-compatible ctor (no CooldownCache).
     * @param cooldown Cooldown service
     * @param metadataService Cooldown metadata service for cache invalidation
     * @param crs Repository settings CRUD
     * @param csettings Cooldown settings
     * @param dataSource Database data source (nullable)
     * @param policy Security policy
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public CooldownHandler(final CooldownService cooldown,
        final CooldownMetadataService metadataService, final CrudRepoSettings crs,
        final CooldownSettings csettings, final DataSource dataSource,
        final Policy<?> policy) {
        this(cooldown, metadataService, null, crs, csettings, dataSource, policy);
    }

    /**
     * Register cooldown routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        // GET /api/v1/cooldown/config — current cooldown configuration
        router.get("/api/v1/cooldown/config")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.READ))
            .handler(this::getConfig);
        // PUT /api/v1/cooldown/config — update cooldown configuration (hot reload)
        router.put("/api/v1/cooldown/config")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.WRITE))
            .handler(this::updateConfig);
        // GET /api/v1/cooldown/overview — cooldown-enabled repos
        router.get("/api/v1/cooldown/overview")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.READ))
            .handler(this::overview);
        // GET /api/v1/cooldown/blocked — paginated blocked list
        router.get("/api/v1/cooldown/blocked")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.READ))
            .handler(this::blocked);
        // POST /api/v1/repositories/:name/cooldown/unblock — unblock single artifact
        router.post("/api/v1/repositories/:name/cooldown/unblock")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.WRITE))
            .handler(this::unblock);
        // POST /api/v1/repositories/:name/cooldown/unblock-all — unblock all
        router.post("/api/v1/repositories/:name/cooldown/unblock-all")
            .handler(new AuthzHandler(this.policy, ApiCooldownPermission.WRITE))
            .handler(this::unblockAll);
    }

    /**
     * GET /api/v1/cooldown/config — return current cooldown configuration.
     * @param ctx Routing context
     */
    private void getConfig(final RoutingContext ctx) {
        final JsonObject response = new JsonObject()
            .put("enabled", this.csettings.enabled())
            .put("minimum_allowed_age",
                CooldownHandler.formatDuration(this.csettings.minimumAllowedAge()));
        final JsonObject overrides = new JsonObject();
        for (final Map.Entry<String, CooldownSettings.RepoTypeConfig> entry
            : this.csettings.repoTypeOverrides().entrySet()) {
            overrides.put(entry.getKey(), new JsonObject()
                .put("enabled", entry.getValue().enabled())
                .put("minimum_allowed_age",
                    CooldownHandler.formatDuration(entry.getValue().minimumAllowedAge())));
        }
        response.put("repo_types", overrides);
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(response.encode());
    }

    /**
     * PUT /api/v1/cooldown/config — update cooldown configuration with hot reload.
     * @param ctx Routing context
     * @checkstyle ExecutableStatementCountCheck (60 lines)
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    private void updateConfig(final RoutingContext ctx) {
        final JsonObject body = ctx.body().asJsonObject();
        if (body == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final boolean newEnabled = body.getBoolean("enabled", this.csettings.enabled());
        final Duration newAge = body.containsKey("minimum_allowed_age")
            ? CooldownHandler.parseDuration(body.getString("minimum_allowed_age"))
            : this.csettings.minimumAllowedAge();
        final Map<String, CooldownSettings.RepoTypeConfig> overrides = new HashMap<>();
        final JsonObject repoTypes = body.getJsonObject("repo_types");
        if (repoTypes != null) {
            for (final String key : repoTypes.fieldNames()) {
                final JsonObject rt = repoTypes.getJsonObject(key);
                overrides.put(key.toLowerCase(Locale.ROOT),
                    new CooldownSettings.RepoTypeConfig(
                        rt.getBoolean("enabled", true),
                        rt.containsKey("minimum_allowed_age")
                            ? CooldownHandler.parseDuration(
                                rt.getString("minimum_allowed_age"))
                            : newAge
                    ));
            }
        }
        final boolean wasEnabled = this.csettings.enabled();
        final Map<String, CooldownSettings.RepoTypeConfig> oldOverrides =
            this.csettings.repoTypeOverrides();
        this.csettings.update(newEnabled, newAge, overrides);
        // Auto-unblock when cooldown changes
        if (this.repository != null) {
            final String actor = ctx.user() != null
                ? ctx.user().principal().getString(AuthTokenRest.SUB, "system")
                : "system";
            if (wasEnabled && !newEnabled) {
                // Global cooldown disabled — unblock everything
                this.repository.unblockAll(actor);
            } else if (newEnabled) {
                // Check each repo type override for disable transitions
                for (final Map.Entry<String, CooldownSettings.RepoTypeConfig> entry
                    : overrides.entrySet()) {
                    if (!entry.getValue().enabled()) {
                        final CooldownSettings.RepoTypeConfig old =
                            oldOverrides.get(entry.getKey());
                        // Unblock if was enabled (or new) and now disabled
                        if (old == null || old.enabled()) {
                            this.repository.unblockByRepoType(entry.getKey(), actor);
                        }
                    }
                }
            }
        }
        // Persist to DB if available
        if (this.settingsDao != null) {
            final String actor2 = ctx.user() != null
                ? ctx.user().principal().getString(AuthTokenRest.SUB, "system")
                : "system";
            final javax.json.JsonObjectBuilder jb = Json.createObjectBuilder()
                .add("enabled", newEnabled)
                .add("minimum_allowed_age",
                    CooldownHandler.formatDuration(newAge));
            if (!overrides.isEmpty()) {
                final javax.json.JsonObjectBuilder rtb = Json.createObjectBuilder();
                for (final Map.Entry<String, CooldownSettings.RepoTypeConfig> entry
                    : overrides.entrySet()) {
                    rtb.add(entry.getKey(), Json.createObjectBuilder()
                        .add("enabled", entry.getValue().enabled())
                        .add("minimum_allowed_age",
                            CooldownHandler.formatDuration(
                                entry.getValue().minimumAllowedAge())));
                }
                jb.add("repo_types", rtb);
            }
            this.settingsDao.put("cooldown", jb.build(), actor2);
        }
        // Invalidate ALL caches: a policy change (e.g. 30d→7d) can shift
        // which versions are in/out of the cooldown window, so every cached
        // decision and every cached filtered-metadata response may be stale.
        this.metadataService.clearAll();
        if (this.cooldownCache != null) {
            this.cooldownCache.clear();
        }
        CooldownHandler.recordAdminMetric("policy_change");
        StructuredLogger.local().forComponent(LOG_COMPONENT)
            .message("Cooldown policy updated — all caches invalidated")
            .field("cooldown.enabled", newEnabled)
            .field("cooldown.minimum_allowed_age",
                CooldownHandler.formatDuration(newAge))
            .info();
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("status", "saved").encode());
    }

    /**
     * GET /api/v1/cooldown/overview — list repositories that have cooldown enabled,
     * based on CooldownSettings (pantera.yml config), not just repo type.
     * @param ctx Routing context
     */
    private void overview(final RoutingContext ctx) {
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        CompletableFuture.supplyAsync((java.util.function.Supplier<List<JsonObject>>) () -> {
            final Collection<String> all = this.crs.listAll();
            final List<JsonObject> result = new ArrayList<>(all.size());
            for (final String name : all) {
                if (!perms.implies(new AdapterBasicPermission(name, "read"))) {
                    continue;
                }
                final RepositoryName rname = new RepositoryName.Simple(name);
                try {
                    final JsonStructure config = this.crs.value(rname);
                    if (config == null
                        || !(config instanceof javax.json.JsonObject)) {
                        continue;
                    }
                    final javax.json.JsonObject jobj =
                        (javax.json.JsonObject) config;
                    final javax.json.JsonObject repoSection;
                    if (jobj.containsKey(CooldownHandler.REPO)) {
                        final javax.json.JsonValue rv =
                            jobj.get(CooldownHandler.REPO);
                        if (rv.getValueType() != JsonValue.ValueType.OBJECT) {
                            continue;
                        }
                        repoSection = (javax.json.JsonObject) rv;
                    } else {
                        repoSection = jobj;
                    }
                    final String repoType = repoSection.getString(
                        CooldownHandler.TYPE, ""
                    );
                    // Check if cooldown is actually enabled for this repo type
                    if (!this.csettings.enabledFor(repoType)) {
                        continue;
                    }
                    // Only proxy repos can have cooldown
                    if (!repoType.endsWith("-proxy")) {
                        continue;
                    }
                    final Duration minAge =
                        this.csettings.minimumAllowedAgeFor(repoType);
                    final JsonObject entry = new JsonObject()
                        .put("name", name)
                        .put(CooldownHandler.TYPE, repoType)
                        .put("cooldown", formatDuration(minAge));
                    // Add active block count if DB is available
                    if (this.repository != null) {
                        final long count =
                            this.repository.countActiveBlocks(repoType, name);
                        entry.put("active_blocks", count);
                    }
                    result.add(entry);
                } catch (final Exception ex) {
                    // skip repos that cannot be read
                }
            }
            return result;
        }, HandlerExecutor.get()).whenComplete((repos, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                final JsonArray arr = new JsonArray();
                for (final JsonObject repo : repos) {
                    arr.add(repo);
                }
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject().put("repos", arr).encode());
            }
        });
    }

    /**
     * Frontend sort field → DB column mapping.
     * Prevents SQL injection by explicitly mapping known UI fields to DB columns.
     */
    private static final Map<String, String> SORT_COL_MAP = Map.of(
        "package_name", "artifact",
        "version", "version",
        "repo", "repo_name",
        "repo_type", "repo_type",
        "reason", "reason",
        "remaining_hours", "blocked_until"
    );

    /**
     * GET /api/v1/cooldown/blocked — paginated list of actively blocked artifacts.
     * Supports server-side search via ?search= and sort via ?sort_by= / ?sort_dir=.
     * @param ctx Routing context
     */
    private void blocked(final RoutingContext ctx) {
        if (this.repository == null) {
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(ApiResponse.paginated(new JsonArray(), 0, 20, 0).encode());
            return;
        }
        final int page = ApiResponse.intParam(
            ctx.queryParam("page").stream().findFirst().orElse(null), 0
        );
        final int size = ApiResponse.clampSize(
            ApiResponse.intParam(
                ctx.queryParam("size").stream().findFirst().orElse(null), 50
            )
        );
        final String searchQuery = ctx.queryParam("search").stream()
            .findFirst().orElse(null);
        final String repoFilter = ctx.queryParam("repo").stream()
            .findFirst().orElse(null);
        final String repoTypeFilter = ctx.queryParam("repo_type").stream()
            .findFirst().orElse(null);
        final String sortByParam = ctx.queryParam("sort_by").stream()
            .findFirst().orElse(null);
        // SORT_COL_MAP is an immutable Map.of() which throws NPE on null key — guard explicitly
        final String sortDbCol = sortByParam != null
            ? SORT_COL_MAP.getOrDefault(sortByParam, "blocked_at")
            : "blocked_at";
        final boolean sortAsc = "asc".equalsIgnoreCase(
            ctx.queryParam("sort_dir").stream().findFirst().orElse("desc")
        );
        // Build AuthUser on the event loop (cheap — just reads ctx principal).
        // Defer policy.getPermissions(...) and crs.listAll() into the async closure:
        // CachedYamlPolicy falls back to storage (blocking) on cache miss, and
        // CrudRepoSettings (RepositoryDao) runs JDBC — neither belongs on the
        // Vert.x event loop.
        final AuthUser authUser = new AuthUser(
            ctx.user().principal().getString(AuthTokenRest.SUB),
            ctx.user().principal().getString(AuthTokenRest.CONTEXT)
        );
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonObject>) () -> {
            final PermissionCollection perms = this.policy.getPermissions(authUser);
            // Compute accessible repo set once per request — O(repos), not O(blocks).
            // Scoping is pushed into SQL via ANY(?), so the DB returns only rows
            // the caller may see — the former Java-side skip/filter pagination
            // (and its off-by-N filteredTotal bug) is gone.
            final Set<String> accessibleRepos = this.crs.listAll().stream()
                .filter(n -> perms.implies(new AdapterBasicPermission(n, "read")))
                .collect(java.util.stream.Collectors.toSet());
            final List<DbBlockRecord> rows = this.repository.findActivePaginated(
                accessibleRepos, repoFilter, repoTypeFilter, searchQuery,
                sortDbCol, sortAsc, page * size, size
            );
            final long total = this.repository.countActiveBlocks(
                accessibleRepos, repoFilter, repoTypeFilter, searchQuery
            );
            final Instant now = Instant.now();
            final JsonArray items = new JsonArray();
            for (final DbBlockRecord rec : rows) {
                final long remainingSecs =
                    Duration.between(now, rec.blockedUntil()).getSeconds();
                items.add(new JsonObject()
                    .put("package_name", rec.artifact())
                    .put("version", rec.version())
                    .put("repo", rec.repoName())
                    .put("repo_type", rec.repoType())
                    .put("reason", rec.reason().name())
                    .put("blocked_date", rec.blockedAt().toString())
                    .put("blocked_until", rec.blockedUntil().toString())
                    .put("remaining_hours",
                        Math.max(0, remainingSecs / 3600)));
            }
            return ApiResponse.paginated(items, page, size, (int) total);
        }, HandlerExecutor.get()).whenComplete((result, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        });
    }

    /**
     * POST /api/v1/repositories/:name/cooldown/unblock — unblock a single artifact version.
     * Flow: DB write → CooldownCache invalidation → FilteredMetadataCache invalidation → 204.
     * All invalidations complete synchronously before the response is sent.
     * @param ctx Routing context
     * @checkstyle ExecutableStatementCountCheck (60 lines)
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    private void unblock(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        final String bodyStr = ctx.body().asString();
        if (bodyStr == null || bodyStr.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return;
        }
        final javax.json.JsonObject body;
        try {
            body = Json.createReader(new StringReader(bodyStr)).readObject();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return;
        }
        final String artifact = body.getString("artifact", "").trim();
        final String version = body.getString("version", "").trim();
        if (artifact.isEmpty() || version.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "artifact and version are required");
            return;
        }
        final String repoType;
        try {
            repoType = this.repoType(rname);
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 404, "NOT_FOUND", ex.getMessage());
            return;
        }
        if (repoType.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Repository type is required");
            return;
        }
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        // DB write completes first, then synchronous cache invalidation, then response
        this.cooldown.unblock(repoType, name, artifact, version, actor)
            .thenRun(() -> {
                // CooldownCache L1+L2 invalidation (handler-level guarantee)
                if (this.cooldownCache != null) {
                    this.cooldownCache.unblock(name, artifact, version);
                }
                // FilteredMetadataCache invalidation
                this.metadataService.invalidate(repoType, name, artifact);
                CooldownHandler.recordAdminMetric("unblock");
                StructuredLogger.local().forComponent(LOG_COMPONENT)
                    .message("Admin unblock: version unblocked")
                    .field("repository.name", name)
                    .field("repository.type", repoType)
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .field("user.name", actor)
                    .info();
            })
            .whenComplete((ignored, error) -> {
                if (error == null) {
                    ctx.response().setStatusCode(204).end();
                } else {
                    ApiResponse.sendError(
                        ctx, 500, "INTERNAL_ERROR", error.getMessage()
                    );
                }
            });
    }

    /**
     * POST /api/v1/repositories/:name/cooldown/unblock-all — unblock all artifacts in repo.
     * Flow: DB write → CooldownCache invalidation → FilteredMetadataCache invalidation → 204.
     * All invalidations complete synchronously before the response is sent.
     * @param ctx Routing context
     */
    private void unblockAll(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        final String repoType;
        try {
            repoType = this.repoType(rname);
        } catch (final IllegalArgumentException ex) {
            ApiResponse.sendError(ctx, 404, "NOT_FOUND", ex.getMessage());
            return;
        }
        if (repoType.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Repository type is required");
            return;
        }
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        // DB write completes first, then synchronous cache invalidation, then response
        this.cooldown.unblockAll(repoType, name, actor)
            .thenRun(() -> {
                // CooldownCache L1+L2 invalidation (handler-level guarantee)
                if (this.cooldownCache != null) {
                    this.cooldownCache.unblockAll(name);
                }
                // FilteredMetadataCache invalidation
                this.metadataService.invalidateAll(repoType, name);
                CooldownHandler.recordAdminMetric("unblock_all");
                StructuredLogger.local().forComponent(LOG_COMPONENT)
                    .message("Admin unblock-all: all versions unblocked for repo")
                    .field("repository.name", name)
                    .field("repository.type", repoType)
                    .field("user.name", actor)
                    .info();
            })
            .whenComplete((ignored, error) -> {
                if (error == null) {
                    ctx.response().setStatusCode(204).end();
                } else {
                    ApiResponse.sendError(
                        ctx, 500, "INTERNAL_ERROR", error.getMessage()
                    );
                }
            });
    }

    /**
     * Extract repository type from config, throwing if repo not found.
     * @param rname Repository name
     * @return Repository type string (may be empty if not set)
     * @throws IllegalArgumentException if repo does not exist or config is unreadable
     */
    private String repoType(final RepositoryName rname) {
        if (!this.crs.exists(rname)) {
            throw new IllegalArgumentException(
                String.format("Repository '%s' not found", rname)
            );
        }
        final JsonStructure config = this.crs.value(rname);
        if (config == null) {
            throw new IllegalArgumentException(
                String.format("Repository '%s' not found", rname)
            );
        }
        if (!(config instanceof javax.json.JsonObject)) {
            return "";
        }
        final javax.json.JsonObject jobj = (javax.json.JsonObject) config;
        if (!jobj.containsKey(CooldownHandler.REPO)) {
            return "";
        }
        final javax.json.JsonValue repoVal = jobj.get(CooldownHandler.REPO);
        if (repoVal.getValueType() != JsonValue.ValueType.OBJECT) {
            return "";
        }
        return ((javax.json.JsonObject) repoVal).getString(CooldownHandler.TYPE, "");
    }

    /**
     * Format duration as human-readable string (e.g. "7d", "24h", "30m").
     * @param duration Duration to format
     * @return Formatted string
     */
    private static String formatDuration(final Duration duration) {
        final long days = duration.toDays();
        if (days > 0 && duration.equals(Duration.ofDays(days))) {
            return days + "d";
        }
        final long hours = duration.toHours();
        if (hours > 0 && duration.equals(Duration.ofHours(hours))) {
            return hours + "h";
        }
        return duration.toMinutes() + "m";
    }

    /**
     * Parse duration string (e.g. "7d", "24h", "30m") to Duration.
     * @param value Duration string
     * @return Duration
     */
    private static Duration parseDuration(final String value) {
        if (value == null || value.isEmpty()) {
            return Duration.ofHours(CooldownSettings.DEFAULT_HOURS);
        }
        final String trimmed = value.trim().toLowerCase(Locale.ROOT);
        final String num = trimmed.replaceAll("[^0-9]", "");
        if (num.isEmpty()) {
            return Duration.ofHours(CooldownSettings.DEFAULT_HOURS);
        }
        final long amount = Long.parseLong(num);
        if (trimmed.endsWith("d")) {
            return Duration.ofDays(amount);
        } else if (trimmed.endsWith("h")) {
            return Duration.ofHours(amount);
        } else if (trimmed.endsWith("m")) {
            return Duration.ofMinutes(amount);
        }
        return Duration.ofHours(amount);
    }

    /**
     * Record admin action counter: {@code pantera.cooldown.admin{action=...}}.
     * Safe to call even when Micrometer is not initialised (guard-checked).
     * @param action Action tag value (unblock, unblock_all, policy_change)
     */
    private static void recordAdminMetric(final String action) {
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordAdminAction(action);
        }
    }
}
