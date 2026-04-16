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

import com.auto1.pantera.api.AuthzHandler;
import com.auto1.pantera.api.ManageStorageAliases;
import com.auto1.pantera.api.perms.ApiAliasPermission;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.db.dao.StorageAliasDao;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.security.policy.Policy;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Storage alias handler for /api/v1/storages/* and
 * /api/v1/repositories/:name/storages/* endpoints.
 */
public final class StorageAliasHandler {

    /**
     * Pantera settings storage cache.
     */
    private final StoragesCache storagesCache;

    /**
     * Pantera settings storage.
     */
    private final BlockingStorage asto;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Storage alias DAO (nullable — present only when DB is configured).
     */
    private final StorageAliasDao aliasDao;

    /**
     * Ctor.
     * @param storagesCache Pantera settings storage cache
     * @param asto Pantera settings storage
     * @param policy Pantera security policy
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
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonArray>) () -> {
            if (this.aliasDao != null) {
                return aliasesToArray(this.aliasDao.listGlobal());
            }
            return yamlAliasesToArray(new ManageStorageAliases(this.asto).list());
        }, HandlerExecutor.get()).whenComplete((arr, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(arr.encode());
            }
        });
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
        CompletableFuture.runAsync(() -> {
            if (this.aliasDao != null) {
                this.aliasDao.put(name, null, body);
            }
            try {
                new ManageStorageAliases(this.asto).add(name, body);
            } catch (final Exception ignored) {
                // YAML write is best-effort when DB is primary
            }
            this.storagesCache.invalidateAll();
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response().setStatusCode(200).end();
            }
        });
    }

    /**
     * DELETE /api/v1/storages/:name — delete a global alias.
     * Checks for dependent repositories when aliasDao is present.
     * @param ctx Routing context
     */
    private void deleteGlobalAlias(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        CompletableFuture.runAsync(() -> {
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
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err == null) {
                ctx.response().setStatusCode(200).end();
            } else {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (cause instanceof DependencyException) {
                    ApiResponse.sendError(ctx, 409, "CONFLICT", cause.getMessage());
                } else if (cause instanceof IllegalStateException) {
                    ApiResponse.sendError(ctx, 404, "NOT_FOUND", cause.getMessage());
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        });
    }

    /**
     * GET /api/v1/repositories/:name/storages — list per-repo aliases.
     * Reads from DB when available, falls back to YAML.
     * @param ctx Routing context
     */
    private void listRepoAliases(final RoutingContext ctx) {
        final String repoName = ctx.pathParam("name");
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonArray>) () -> {
            if (this.aliasDao != null) {
                return aliasesToArray(this.aliasDao.listForRepo(repoName));
            }
            return yamlAliasesToArray(
                new ManageStorageAliases(new Key.From(repoName), this.asto).list()
            );
        }, HandlerExecutor.get()).whenComplete((arr, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(arr.encode());
            }
        });
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
        CompletableFuture.runAsync(() -> {
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
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                ctx.response().setStatusCode(200).end();
            }
        });
    }

    /**
     * DELETE /api/v1/repositories/:name/storages/:alias — delete a repo alias.
     * @param ctx Routing context
     */
    private void deleteRepoAlias(final RoutingContext ctx) {
        final String repoName = ctx.pathParam("name");
        final String aliasName = ctx.pathParam("alias");
        CompletableFuture.runAsync(() -> {
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
        }, HandlerExecutor.get()).whenComplete((ignored, err) -> {
            if (err == null) {
                ctx.response().setStatusCode(200).end();
            } else {
                final Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (cause instanceof IllegalStateException) {
                    ApiResponse.sendError(ctx, 404, "NOT_FOUND", cause.getMessage());
                } else {
                    ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                }
            }
        });
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
