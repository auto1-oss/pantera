/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api.v1;

import com.artipie.api.AuthzHandler;
import com.artipie.api.ManageStorageAliases;
import com.artipie.api.perms.ApiAliasPermission;
import com.artipie.asto.Key;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.cache.StoragesCache;
import com.artipie.db.dao.StorageAliasDao;
import com.artipie.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.util.Collection;
import java.util.List;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Storage alias handler for /api/v1/storages/* and
 * /api/v1/repositories/:name/storages/* endpoints.
 */
public final class StorageAliasHandler {

    /**
     * Artipie settings storage cache.
     */
    private final StoragesCache storagesCache;

    /**
     * Artipie settings storage.
     */
    private final BlockingStorage asto;

    /**
     * Artipie security policy.
     */
    private final Policy<?> policy;

    /**
     * Storage alias DAO (nullable — present only when DB is configured).
     */
    private final StorageAliasDao aliasDao;

    /**
     * Ctor.
     * @param storagesCache Artipie settings storage cache
     * @param asto Artipie settings storage
     * @param policy Artipie security policy
     * @param aliasDao Storage alias DAO, nullable
     */
    public StorageAliasHandler(final StoragesCache storagesCache,
        final BlockingStorage asto, final Policy<?> policy,
        final StorageAliasDao aliasDao) {
        this.storagesCache = storagesCache;
        this.asto = asto;
        this.policy = policy;
        this.aliasDao = aliasDao;
    }

    /**
     * Register storage alias routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final ApiAliasPermission read =
            new ApiAliasPermission(ApiAliasPermission.AliasAction.READ);
        final ApiAliasPermission create =
            new ApiAliasPermission(ApiAliasPermission.AliasAction.CREATE);
        final ApiAliasPermission delete =
            new ApiAliasPermission(ApiAliasPermission.AliasAction.DELETE);
        // GET /api/v1/storages — list global aliases
        router.get("/api/v1/storages")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::listGlobalAliases);
        // PUT /api/v1/storages/:name — create/update global alias
        router.put("/api/v1/storages/:name")
            .handler(new AuthzHandler(this.policy, create))
            .handler(this::putGlobalAlias);
        // DELETE /api/v1/storages/:name — delete global alias
        router.delete("/api/v1/storages/:name")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleteGlobalAlias);
        // GET /api/v1/repositories/:name/storages — list per-repo aliases
        router.get("/api/v1/repositories/:name/storages")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::listRepoAliases);
        // PUT /api/v1/repositories/:name/storages/:alias — create/update repo alias
        router.put("/api/v1/repositories/:name/storages/:alias")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::putRepoAlias);
        // DELETE /api/v1/repositories/:name/storages/:alias — delete repo alias
        router.delete("/api/v1/repositories/:name/storages/:alias")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleteRepoAlias);
    }

    /**
     * GET /api/v1/storages — list global storage aliases.
     * Reads from DB when available, falls back to YAML.
     * @param ctx Routing context
     */
    private void listGlobalAliases(final RoutingContext ctx) {
        ctx.vertx().<JsonArray>executeBlocking(
            () -> {
                if (this.aliasDao != null) {
                    return aliasesToArray(this.aliasDao.listGlobal());
                }
                return yamlAliasesToArray(new ManageStorageAliases(this.asto).list());
            },
            false
        ).onSuccess(
            arr -> ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(arr.encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * PUT /api/v1/storages/:name — create or update a global alias.
     * Writes to both DB and YAML for dual persistence.
     * @param ctx Routing context
     */
    private void putGlobalAlias(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final JsonObject body = bodyAsJson(ctx);
        if (body == null) {
            return;
        }
        ctx.vertx().executeBlocking(
            () -> {
                if (this.aliasDao != null) {
                    this.aliasDao.put(name, null, body);
                }
                try {
                    new ManageStorageAliases(this.asto).add(name, body);
                } catch (final Exception ignored) {
                    // YAML write is best-effort when DB is primary
                }
                this.storagesCache.invalidateAll();
                return null;
            },
            false
        ).onSuccess(
            ignored -> ctx.response().setStatusCode(200).end()
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * DELETE /api/v1/storages/:name — delete a global alias.
     * Checks for dependent repositories when aliasDao is present.
     * @param ctx Routing context
     */
    private void deleteGlobalAlias(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        ctx.vertx().executeBlocking(
            () -> {
                if (this.aliasDao != null) {
                    final List<String> repos = this.aliasDao.findReposUsing(name);
                    if (repos != null && !repos.isEmpty()) {
                        throw new DependencyException(
                            String.format(
                                "Cannot delete alias '%s': used by repositories: %s",
                                name, String.join(", ", repos)
                            )
                        );
                    }
                    this.aliasDao.delete(name, null);
                }
                try {
                    new ManageStorageAliases(this.asto).remove(name);
                } catch (final Exception ignored) {
                    // YAML delete is best-effort when DB is primary
                }
                this.storagesCache.invalidateAll();
                return null;
            },
            false
        ).onSuccess(
            ignored -> ctx.response().setStatusCode(200).end()
        ).onFailure(
            err -> {
                if (err instanceof DependencyException) {
                    ApiResponse.sendError(ctx, 409, "CONFLICT", err.getMessage());
                } else if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(ctx, 404, "NOT_FOUND", err.getMessage());
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }

    /**
     * GET /api/v1/repositories/:name/storages — list per-repo aliases.
     * Reads from DB when available, falls back to YAML.
     * @param ctx Routing context
     */
    private void listRepoAliases(final RoutingContext ctx) {
        final String repoName = ctx.pathParam("name");
        ctx.vertx().<JsonArray>executeBlocking(
            () -> {
                if (this.aliasDao != null) {
                    return aliasesToArray(this.aliasDao.listForRepo(repoName));
                }
                return yamlAliasesToArray(
                    new ManageStorageAliases(new Key.From(repoName), this.asto).list()
                );
            },
            false
        ).onSuccess(
            arr -> ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(arr.encode())
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * PUT /api/v1/repositories/:name/storages/:alias — create or update a repo alias.
     * Writes to both DB and YAML for dual persistence.
     * @param ctx Routing context
     */
    private void putRepoAlias(final RoutingContext ctx) {
        final String repoName = ctx.pathParam("name");
        final String aliasName = ctx.pathParam("alias");
        final JsonObject body = bodyAsJson(ctx);
        if (body == null) {
            return;
        }
        ctx.vertx().executeBlocking(
            () -> {
                if (this.aliasDao != null) {
                    this.aliasDao.put(aliasName, repoName, body);
                }
                try {
                    new ManageStorageAliases(new Key.From(repoName), this.asto)
                        .add(aliasName, body);
                } catch (final Exception ignored) {
                    // YAML write is best-effort when DB is primary
                }
                this.storagesCache.invalidateAll();
                return null;
            },
            false
        ).onSuccess(
            ignored -> ctx.response().setStatusCode(200).end()
        ).onFailure(
            err -> ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage())
        );
    }

    /**
     * DELETE /api/v1/repositories/:name/storages/:alias — delete a repo alias.
     * @param ctx Routing context
     */
    private void deleteRepoAlias(final RoutingContext ctx) {
        final String repoName = ctx.pathParam("name");
        final String aliasName = ctx.pathParam("alias");
        ctx.vertx().executeBlocking(
            () -> {
                if (this.aliasDao != null) {
                    this.aliasDao.delete(aliasName, repoName);
                }
                try {
                    new ManageStorageAliases(new Key.From(repoName), this.asto)
                        .remove(aliasName);
                } catch (final Exception ignored) {
                    // YAML delete is best-effort when DB is primary
                }
                this.storagesCache.invalidateAll();
                return null;
            },
            false
        ).onSuccess(
            ignored -> ctx.response().setStatusCode(200).end()
        ).onFailure(
            err -> {
                if (err instanceof IllegalStateException) {
                    ApiResponse.sendError(ctx, 404, "NOT_FOUND", err.getMessage());
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        );
    }

    /**
     * Convert DB alias entries (with "name" and "config" keys) to a Vert.x JsonArray.
     * @param aliases Collection from StorageAliasDao
     * @return Vert.x JsonArray
     */
    private static JsonArray aliasesToArray(final Collection<JsonObject> aliases) {
        final JsonArray arr = new JsonArray();
        for (final JsonObject alias : aliases) {
            arr.add(new io.vertx.core.json.JsonObject(alias.toString()));
        }
        return arr;
    }

    /**
     * Convert YAML alias entries (with "alias" and "storage" keys) to a Vert.x
     * JsonArray, normalising to the same "name"/"config" format as the DB layer.
     * @param aliases Collection from ManageStorageAliases.list()
     * @return Vert.x JsonArray
     */
    private static JsonArray yamlAliasesToArray(final Collection<JsonObject> aliases) {
        final JsonArray arr = new JsonArray();
        for (final JsonObject alias : aliases) {
            final io.vertx.core.json.JsonObject entry =
                new io.vertx.core.json.JsonObject();
            entry.put("name", alias.getString("alias", ""));
            if (alias.containsKey("storage")) {
                entry.put("config",
                    new io.vertx.core.json.JsonObject(
                        alias.getJsonObject("storage").toString()));
            }
            arr.add(entry);
        }
        return arr;
    }

    /**
     * Parse the request body as a javax.json.JsonObject.
     * Sends a 400 error and returns null if the body is missing or invalid.
     * @param ctx Routing context
     * @return Parsed object, or null if invalid (response already sent)
     */
    private static JsonObject bodyAsJson(final RoutingContext ctx) {
        final String raw = ctx.body().asString();
        if (raw == null || raw.isBlank()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "JSON body is required");
            return null;
        }
        try {
            return Json.createReader(new StringReader(raw)).readObject();
        } catch (final Exception ex) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Invalid JSON body");
            return null;
        }
    }

    /**
     * Signals that an alias cannot be deleted because other resources depend on it.
     */
    private static final class DependencyException extends RuntimeException {
        /**
         * Required serial version UID.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Ctor.
         * @param message Error message
         */
        DependencyException(final String message) {
            super(message);
        }
    }
}
