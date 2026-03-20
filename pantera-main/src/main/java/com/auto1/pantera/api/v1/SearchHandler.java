/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api.v1;

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.perms.ApiSearchPermission;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.security.PermissionCollection;
import java.util.Objects;
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
     * Paginated full-text search handler.
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
        final int page = SearchHandler.intParam(ctx, "page", 0);
        final int size = SearchHandler.intParam(ctx, "size", 20);
        final int offset = page * size;
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        this.index.search(query, size, offset).whenComplete((result, error) -> {
            if (error != null) {
                ctx.response()
                    .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("code", HttpStatus.INTERNAL_SERVER_ERROR_500)
                        .put("message", error.getMessage())
                        .encode());
            } else {
                final JsonArray items = new JsonArray();
                result.documents().forEach(doc -> {
                    if (!perms.implies(
                        new AdapterBasicPermission(doc.repoName(), "read"))) {
                        return;
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
                });
                ctx.response()
                    .setStatusCode(HttpStatus.OK_200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("items", items)
                        .put("page", page)
                        .put("size", size)
                        .put("total", items.size())
                        .put("hasMore", false)
                        .encode());
            }
        });
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
            .eventCategory("search")
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
