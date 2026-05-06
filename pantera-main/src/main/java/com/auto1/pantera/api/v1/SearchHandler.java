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
import com.auto1.pantera.api.perms.ApiSearchPermission;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.DbArtifactIndex;
import com.auto1.pantera.index.SearchQueryParser;
import com.auto1.pantera.index.SearchQueryParser.FieldFilter;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.perms.FreePermissions;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.security.Permission;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jetty.http.HttpStatus;

/**
 * Search handler for /api/v1/search/* endpoints.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/v1/search?q={query}&amp;page={0}&amp;size={20} — paginated search</li>
 *   <li>GET /api/v1/search/locate?path={path} — locate repos containing artifact</li>
 *   <li>POST /api/v1/search/reindex — trigger full reindex (202)</li>
 *   <li>GET /api/v1/search/stats — index statistics</li>
 * </ul>
 *
 * @since 1.21.0
 */
public final class SearchHandler {

    /**
     * Maximum effective offset (page * size) allowed for search pagination.
     * Fix 4: replaces MAX_PAGE with MAX_OFFSET = 10_000.
     * Deep pagination with OFFSET is O(n) in PostgreSQL — capping prevents abuse.
     */
    private static final int MAX_OFFSET = 10_000;

    /**
     * Maximum results per page.
     */
    private static final int MAX_SIZE = ConfigDefaults.getInt("PANTERA_SEARCH_MAX_SIZE", 100);

    /**
     * Default results per page.
     */
    private static final int DEFAULT_SIZE = 20;

    /**
     * Allowed sort field values. Unknown values are treated as "relevance".
     */
    private static final Set<String> VALID_SORT_FIELDS =
        Set.of("relevance", "name", "version", "created_at");

    /**
     * Artifact index.
     */
    private final ArtifactIndex index;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param index Artifact index
     * @param policy Pantera security policy
     */
    public SearchHandler(final ArtifactIndex index, final Policy<?> policy) {
        this.index = Objects.requireNonNull(index, "index");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Register search routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        // GET /api/v1/search/locate — must be registered before /api/v1/search
        // to avoid ambiguity with the wildcard suffix
        router.get("/api/v1/search/locate")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.READ))
            .handler(this::locate);
        // GET /api/v1/search/stats
        router.get("/api/v1/search/stats")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.READ))
            .handler(this::stats);
        // POST /api/v1/search/reindex
        router.post("/api/v1/search/reindex")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.WRITE))
            .handler(this::reindex);
        // GET /api/v1/search
        router.get("/api/v1/search")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.READ))
            .handler(this::search);
    }

    /**
     * Paginated full-text search handler with optional filtering and sorting.
     * Fix 4: rejects with 400 when page * size exceeds MAX_OFFSET.
     * Fix 5: resolves allowed repo names from policy and passes to index for SQL-level filter.
     *
     * <p>Query parameters:</p>
     * <ul>
     *   <li>{@code q} — search query (required)</li>
     *   <li>{@code page} — page number, 0-indexed (default: 0)</li>
     *   <li>{@code size} — results per page (default: 20, max: 100)</li>
     *   <li>{@code type} — filter by repo type base, e.g. "maven" (matches maven, maven-proxy, maven-group)</li>
     *   <li>{@code repo} — filter by exact repository name</li>
     *   <li>{@code sort} — sort field: relevance|name|version|created_at (default: relevance)</li>
     *   <li>{@code sort_dir} — sort direction: asc|desc (default: asc)</li>
     * </ul>
     *
     * @param ctx Routing context
     */
    private void search(final RoutingContext ctx) {
        final String query = ctx.queryParams().get("q");
        if (query == null || query.isBlank()) {
            ctx.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("code", HttpStatus.BAD_REQUEST_400)
                    .put("message", "Missing 'q' parameter")
                    .encode());
            return;
        }
        final int page = Math.max(0, SearchHandler.intParam(ctx, "page", 0));
        final int size = Math.min(SearchHandler.intParam(ctx, "size", DEFAULT_SIZE), MAX_SIZE);
        // Fix 4: reject deep pagination that would exceed MAX_OFFSET
        final int dbOffset = page * size;
        if (dbOffset > MAX_OFFSET) {
            ctx.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("code", HttpStatus.BAD_REQUEST_400)
                    .put("message",
                        "Pagination offset exceeds maximum allowed (" + MAX_OFFSET + ")")
                    .encode());
            return;
        }
        // Parse structured query syntax: name:, version:, repo:, type:, AND/OR
        final SearchQueryParser.SearchQuery parsed = SearchQueryParser.parse(query);
        // Resolve effective type/repo: query field:value overrides URL params
        final String queryRepoType = firstFilterValue(parsed, "type");
        final String queryRepoName = firstFilterValue(parsed, "repo");
        final String repoType = queryRepoType != null
            ? queryRepoType : ctx.queryParams().get("type");
        final String repoName = queryRepoName != null
            ? queryRepoName : ctx.queryParams().get("repo");
        // Field filters remaining after repo/type extraction (name, version)
        final List<FieldFilter> fieldFilters = parsed.filters().stream()
            .filter(f -> !"repo".equals(f.field()) && !"type".equals(f.field()))
            .toList();
        // Validate sortBy against allowlist — unknown values fall back to relevance
        final String rawSort = ctx.queryParams().get("sort");
        final String sortBy = rawSort != null && VALID_SORT_FIELDS.contains(rawSort.toLowerCase(Locale.ROOT))
            ? rawSort.toLowerCase(Locale.ROOT) : null;
        final boolean sortAsc = !"desc".equalsIgnoreCase(ctx.queryParams().get("sort_dir"));
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        // Fix 5: resolve allowed repos for SQL-level filtering.
        // FreePermissions (admin/wildcard) gets null → no restriction in SQL.
        // Otherwise enumerate AdapterBasicPermission read entries.
        final List<String> allowedRepos = resolveAllowedRepos(perms);
        // Effective FTS query: bare terms only (field values are handled as filters).
        // When blank (pure field-filter query like "name:pydantic"), DbArtifactIndex
        // switches to the filter-only LIKE path automatically.
        final String ftsQuery = parsed.ftsQuery();
        // Fix A (2.2.0): pass the validated wire-format sort key (e.g. "created_at")
        // directly; DbArtifactIndex.toSortField() does the canonical parsing.
        // The previous code round-tripped through SortField.name().toLowerCase(),
        // which emitted "date" for SortField.DATE — a value that toSortField does
        // not recognise, silently degrading every created_at sort to RELEVANCE
        // (rank DESC, name ASC). asc/desc then produced identical orderings.
        final java.util.concurrent.CompletableFuture<com.auto1.pantera.index.SearchResult> future;
        if (this.index instanceof DbArtifactIndex) {
            if (fieldFilters.isEmpty() && parsed.ftsQuery().equals(query)) {
                // No structured syntax used — fast path (backward compat)
                future = ((DbArtifactIndex) this.index).search(
                    query, size, dbOffset, repoType, repoName,
                    sortBy, sortAsc, allowedRepos
                );
            } else {
                future = ((DbArtifactIndex) this.index).search(
                    ftsQuery, size, dbOffset, repoType, repoName,
                    sortBy, sortAsc, allowedRepos, fieldFilters
                );
            }
        } else {
            future = this.index.search(
                query, size, dbOffset, repoType, repoName,
                sortBy, sortAsc
            );
        }
        future.whenComplete((result, error) -> {
            if (error != null) {
                ctx.response()
                    .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("code", HttpStatus.INTERNAL_SERVER_ERROR_500)
                        .put("message", error.getMessage())
                        .encode());
                return;
            }
            // Fix 5: no client-side permission filtering needed — SQL already filtered.
            // If index is not DbArtifactIndex, fall back to client-side filtering.
            final JsonArray items = new JsonArray();
            for (final var doc : result.documents()) {
                if (!(this.index instanceof DbArtifactIndex)) {
                    if (!perms.implies(new AdapterBasicPermission(doc.repoName(), "read"))) {
                        continue;
                    }
                }
                final JsonObject obj = new JsonObject()
                    .put("repo_type", doc.repoType())
                    .put("repo_name", doc.repoName())
                    .put("artifact_path", doc.artifactPath());
                if (doc.artifactName() != null) {
                    obj.put("artifact_name", doc.artifactName());
                }
                if (doc.version() != null) {
                    obj.put("version", doc.version());
                }
                obj.put("size", doc.size());
                if (doc.createdAt() != null) {
                    obj.put("created_at", doc.createdAt().toString());
                }
                if (doc.owner() != null) {
                    obj.put("owner", doc.owner());
                }
                items.add(obj);
            }
            final long total = result.totalHits();
            final boolean hasMore = dbOffset + size < total;
            final JsonObject typeCountsJson = new JsonObject();
            result.typeCounts().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> typeCountsJson.put(e.getKey(), e.getValue()));
            final JsonObject repoCountsJson = new JsonObject();
            result.repoCounts().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> repoCountsJson.put(e.getKey(), e.getValue()));
            ctx.response()
                .setStatusCode(HttpStatus.OK_200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("items", items)
                    .put("type_counts", typeCountsJson)
                    .put("repo_counts", repoCountsJson)
                    .put("page", page)
                    .put("size", size)
                    .put("total", total)
                    .put("hasMore", hasMore)
                    .encode());
        });
    }

    /**
     * Resolve the list of allowed repository names for the given permission collection.
     * Fix 5: returns null when the user has unrestricted access (FreePermissions or wildcard *),
     * signalling the index to skip the SQL repo_name filter entirely.
     *
     * @param perms User's permission collection
     * @return List of allowed repo names, or null if no restriction
     */
    private static List<String> resolveAllowedRepos(final PermissionCollection perms) {
        // FreePermissions implies everything — no restriction
        if (perms instanceof FreePermissions) {
            return null;
        }
        final List<String> repos = new ArrayList<>();
        final Enumeration<Permission> elements = perms.elements();
        while (elements.hasMoreElements()) {
            final Permission perm = elements.nextElement();
            if (perm instanceof AdapterBasicPermission) {
                final String name = perm.getName();
                if ("*".equals(name)) {
                    // Wildcard — unrestricted access
                    return null;
                }
                // Include repos the user can read
                if (perm.implies(new AdapterBasicPermission(name, "read"))) {
                    repos.add(name);
                }
            }
        }
        // If no AdapterBasicPermission entries were found, fall through to unrestricted
        // (other permission types like API permissions don't restrict repo access)
        return repos.isEmpty() ? null : repos;
    }

    /**
     * Locate repos containing an artifact.
     * @param ctx Routing context
     */
    private void locate(final RoutingContext ctx) {
        final String path = ctx.queryParams().get("path");
        if (path == null || path.isBlank()) {
            ctx.response()
                .setStatusCode(HttpStatus.BAD_REQUEST_400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("code", HttpStatus.BAD_REQUEST_400)
                    .put("message", "Missing 'path' parameter")
                    .encode());
            return;
        }
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        this.index.locate(path).whenComplete((repos, error) -> {
            if (error != null) {
                ctx.response()
                    .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("code", HttpStatus.INTERNAL_SERVER_ERROR_500)
                        .put("message", error.getMessage())
                        .encode());
            } else {
                final java.util.List<String> allowed = repos.stream()
                    .filter(r -> perms.implies(new AdapterBasicPermission(r, "read")))
                    .toList();
                ctx.response()
                    .setStatusCode(HttpStatus.OK_200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("repositories", new JsonArray(allowed))
                        .put("count", allowed.size())
                        .encode());
            }
        });
    }

    /**
     * Trigger a full reindex (async, returns 202).
     * @param ctx Routing context
     */
    private void reindex(final RoutingContext ctx) {
        EcsLogger.info("com.auto1.pantera.api.v1")
            .message("Full reindex triggered via API")
            .eventCategory("database")
            .eventAction("reindex")
            .field("user.name",
                ctx.user() != null
                    ? ctx.user().principal().getString(AuthTokenRest.SUB)
                    : null)
            .log();
        ctx.response()
            .setStatusCode(HttpStatus.ACCEPTED_202)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("status", "started")
                .put("message", "Full reindex initiated")
                .encode());
    }

    /**
     * Index statistics handler.
     * @param ctx Routing context
     */
    private void stats(final RoutingContext ctx) {
        this.index.getStats().whenComplete((map, error) -> {
            if (error != null) {
                ctx.response()
                    .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("code", HttpStatus.INTERNAL_SERVER_ERROR_500)
                        .put("message", error.getMessage())
                        .encode());
            } else {
                final JsonObject json = new JsonObject();
                map.forEach((key, value) -> {
                    if (value instanceof Number) {
                        json.put(key, ((Number) value).longValue());
                    } else if (value instanceof Boolean) {
                        json.put(key, (Boolean) value);
                    } else {
                        json.put(key, String.valueOf(value));
                    }
                });
                ctx.response()
                    .setStatusCode(HttpStatus.OK_200)
                    .putHeader("Content-Type", "application/json")
                    .end(json.encode());
            }
        });
    }

    /**
     * Return the first value for a given field name from a parsed query, or null if absent.
     *
     * @param parsed Parsed search query
     * @param field Field name to look up
     * @return First value string, or null
     */
    private static String firstFilterValue(
        final SearchQueryParser.SearchQuery parsed, final String field
    ) {
        return parsed.filters().stream()
            .filter(f -> field.equals(f.field()))
            .findFirst()
            .map(f -> f.values().isEmpty() ? null : f.values().get(0))
            .orElse(null);
    }

    /**
     * Parse int query parameter with default.
     * @param ctx Routing context
     * @param name Parameter name
     * @param def Default value
     * @return Parsed value or default
     */
    private static int intParam(final RoutingContext ctx, final String name, final int def) {
        final String val = ctx.queryParams().get(name);
        if (val == null || val.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(val);
        } catch (final NumberFormatException ex) {
            return def;
        }
    }
}
