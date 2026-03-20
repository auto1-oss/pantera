/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

import com.auto1.pantera.api.perms.ApiSearchPermission;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.Objects;
import org.eclipse.jetty.http.HttpStatus;

/**
 * REST API for artifact search operations.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/v1/search?q={query}&amp;size={20}&amp;from={0} - Full-text search</li>
 *   <li>GET /api/v1/search/locate?path={artifact_path} - Locate repos containing artifact</li>
 *   <li>POST /api/v1/search/reindex - Trigger full reindex</li>
 * </ul>
 *
 * @since 1.20.13
 */
public final class SearchRest extends BaseRest {

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
     * @param policy Security policy
     */
    public SearchRest(final ArtifactIndex index, final Policy<?> policy) {
        this.index = Objects.requireNonNull(index, "index");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public void init(final RouterBuilder rbr) {
        rbr.operation("searchArtifacts")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.READ))
            .handler(this::search)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("locateArtifact")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.READ))
            .handler(this::locate)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("reindexArtifacts")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.WRITE))
            .handler(this::reindex)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        rbr.operation("getIndexStats")
            .handler(new AuthzHandler(this.policy, ApiSearchPermission.READ))
            .handler(this::stats)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * Full-text search handler.
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
        final int size = SearchRest.intParam(ctx, "size", 20);
        final int from = SearchRest.intParam(ctx, "from", 0);
        this.index.search(query, size, from).whenComplete((result, error) -> {
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
                        .put("total_hits", result.totalHits())
                        .put("offset", result.offset())
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
                ctx.response()
                    .setStatusCode(HttpStatus.OK_200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("repositories", new JsonArray(repos))
                        .put("count", repos.size())
                        .encode());
            }
        });
    }

    /**
     * Trigger full reindex.
     * @param ctx Routing context
     */
    private void reindex(final RoutingContext ctx) {
        EcsLogger.info("com.auto1.pantera.api")
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
        this.index.getStats().whenComplete((stats, error) -> {
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
                stats.forEach((key, value) -> {
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
    private static int intParam(final RoutingContext ctx, final String name,
        final int def) {
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
