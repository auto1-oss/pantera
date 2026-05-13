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
import com.auto1.pantera.api.RepositoryEvents;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.api.perms.ApiRepositoryPermission;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.context.HandlerExecutor;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.RepoData;
import com.auto1.pantera.settings.cache.FiltersCache;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonStructure;

/**
 * Repository handler for /api/v1/repositories/* endpoints.
 */
public final class RepositoryHandler {

    /**
     * JSON key for repo section.
     */
    private static final String REPO = "repo";

    /**
     * Pantera filters cache.
     */
    private final FiltersCache filtersCache;

    /**
     * Repository settings create/read/update/delete.
     */
    private final CrudRepoSettings crs;

    /**
     * Repository data management.
     */
    private final RepoData repoData;

    /**
     * Pantera security policy.
     */
    private final Policy<?> policy;

    /**
     * Artifact metadata events queue.
     */
    private final Optional<MetadataEventQueues> events;

    /**
     * Vert.x event bus.
     */
    private final EventBus eventBus;

    /**
     * Ctor.
     * @param filtersCache Pantera filters cache
     * @param crs Repository settings CRUD
     * @param repoData Repository data management
     * @param policy Pantera security policy
     * @param events Artifact events queue
     * @param cooldown Cooldown service
     * @param eventBus Vert.x event bus
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    public RepositoryHandler(final FiltersCache filtersCache,
        final CrudRepoSettings crs, final RepoData repoData,
        final Policy<?> policy, final Optional<MetadataEventQueues> events,
        final CooldownService cooldown, // NOPMD UnusedFormalParameter - public API; reserved for upcoming cooldown integration in repo CRUD endpoints
        final EventBus eventBus) {
        this.filtersCache = filtersCache;
        this.crs = crs;
        this.repoData = repoData;
        this.policy = policy;
        this.events = events;
        this.eventBus = eventBus;
    }

    /**
     * Register repository routes on the router.
     * @param router Vert.x router
     */
    public void register(final Router router) {
        final ApiRepositoryPermission read =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.READ);
        final ApiRepositoryPermission delete =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.DELETE);
        final ApiRepositoryPermission move =
            new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.MOVE);
        // GET /api/v1/repositories — paginated list
        router.get("/api/v1/repositories")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::listRepositories);
        // GET /api/v1/repositories/:name — get repo config
        router.get("/api/v1/repositories/:name")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::getRepository);
        // HEAD /api/v1/repositories/:name — check existence
        router.head("/api/v1/repositories/:name")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::headRepository);
        // PUT /api/v1/repositories/:name — create or update
        router.put("/api/v1/repositories/:name")
            .handler(this::createOrUpdateRepository);
        // DELETE /api/v1/repositories/:name — delete
        router.delete("/api/v1/repositories/:name")
            .handler(new AuthzHandler(this.policy, delete))
            .handler(this::deleteRepository);
        // PUT /api/v1/repositories/:name/move — rename/move
        router.put("/api/v1/repositories/:name/move")
            .handler(new AuthzHandler(this.policy, move))
            .handler(this::moveRepository);
        // GET /api/v1/repositories/:name/members — group repo members
        router.get("/api/v1/repositories/:name/members")
            .handler(new AuthzHandler(this.policy, read))
            .handler(this::getMembers);
    }

    /**
     * GET /api/v1/repositories — paginated list with optional filter/search.
     * @param ctx Routing context
     */
    private void listRepositories(final RoutingContext ctx) {
        final int page = ApiResponse.intParam(ctx.queryParam("page").stream().findFirst().orElse(null), 0);
        final int size = ApiResponse.clampSize(
            ApiResponse.intParam(ctx.queryParam("size").stream().findFirst().orElse(null), 20)
        );
        final String type = ctx.queryParam("type").stream().findFirst().orElse(null);
        final String query = ctx.queryParam("q").stream().findFirst().orElse(null);
        final PermissionCollection perms = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        );
        CompletableFuture.supplyAsync((java.util.function.Supplier<List<JsonObject>>) () -> {
            final Collection<String> all = this.crs.listAll();
            final List<JsonObject> filtered = new ArrayList<>(all.size());
            for (final String name : all) {
                if (query != null
                    && !name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!perms.implies(new AdapterBasicPermission(name, "read"))) {
                    continue;
                }
                String repoType = "unknown";
                try {
                    final javax.json.JsonStructure config =
                        this.crs.value(new RepositoryName.Simple(name));
                    if (config instanceof javax.json.JsonObject) {
                        final javax.json.JsonObject jobj = (javax.json.JsonObject) config;
                        final javax.json.JsonObject repo =
                            jobj.containsKey(RepositoryHandler.REPO)
                                ? jobj.getJsonObject(RepositoryHandler.REPO) : jobj;
                        repoType = repo.getString("type", "unknown");
                    }
                } catch (final Exception ignored) {
                    // Use "unknown" type
                }
                if (type != null && !repoType.toLowerCase(Locale.ROOT).contains(
                    type.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                filtered.add(new JsonObject()
                    .put("name", name)
                    .put("type", repoType));
            }
            return filtered;
        }, HandlerExecutor.get()).whenComplete((filtered, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                final int total = filtered.size();
                final int from = Math.min(page * size, total);
                final int to = Math.min(from + size, total);
                final JsonArray items = new JsonArray();
                for (final JsonObject item : filtered.subList(from, to)) {
                    items.add(item);
                }
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("items", items)
                        .put("page", page)
                        .put("size", size)
                        .put("total", total)
                        .put("hasMore", to < total)
                        .encode());
            }
        });
    }

    /**
     * GET /api/v1/repositories/:name — get repository config.
     * @param ctx Routing context
     */
    private void getRepository(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonStructure>) () -> {
            if (!this.crs.exists(rname)) {
                return null;
            }
            return this.crs.value(rname);
        }, HandlerExecutor.get()).whenComplete((config, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (config == null) {
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(config.toString());
            }
        });
    }

    /**
     * HEAD /api/v1/repositories/:name — check repository existence.
     * @param ctx Routing context
     */
    private void headRepository(final RoutingContext ctx) {
        final RepositoryName rname = new RepositoryName.Simple(ctx.pathParam("name"));
        CompletableFuture.supplyAsync(
            () -> this.crs.exists(rname),
            HandlerExecutor.get()
        ).whenComplete((exists, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (Boolean.TRUE.equals(exists)) {
                ctx.response().setStatusCode(200).end();
            } else {
                ctx.response().setStatusCode(404).end();
            }
        });
    }

    /**
     * PUT /api/v1/repositories/:name — create or update repository.
     * @param ctx Routing context
     * @checkstyle ExecutableStatementCountCheck (70 lines)
     */
    private void createOrUpdateRepository(final RoutingContext ctx) {
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
        if (!body.containsKey(RepositoryHandler.REPO)
            || body.getJsonObject(RepositoryHandler.REPO) == null) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Section `repo` is required");
            return;
        }
        final javax.json.JsonObject repo = body.getJsonObject(RepositoryHandler.REPO);
        if (!repo.containsKey("type")) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "Repository type is required");
            return;
        }
        final String repoType = repo.getString("type");
        if (RepositoryHandler.isGroupType(repoType)) {
            if (!repo.containsKey("members")
                || !(repo.get("members") instanceof javax.json.JsonArray)
                || repo.getJsonArray("members").isEmpty()) {
                ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                    "Group repository requires non-empty 'members' array");
                return;
            }
        } else if (!repo.containsKey("storage")) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST",
                "Repository storage is required for non-group repositories");
            return;
        }
        final boolean exists = this.crs.exists(rname);
        final ApiRepositoryPermission needed;
        if (exists) {
            needed = new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.UPDATE);
        } else {
            needed = new ApiRepositoryPermission(ApiRepositoryPermission.RepositoryAction.CREATE);
        }
        final boolean allowed = this.policy.getPermissions(
            new AuthUser(
                ctx.user().principal().getString(AuthTokenRest.SUB),
                ctx.user().principal().getString(AuthTokenRest.CONTEXT)
            )
        ).implies(needed);
        if (!allowed) {
            ApiResponse.sendError(ctx, 403, "FORBIDDEN", "Insufficient permissions");
            return;
        }
        final String actor = ctx.user().principal().getString(AuthTokenRest.SUB);
        CompletableFuture.runAsync(
            () -> this.crs.save(rname, body, actor),
            HandlerExecutor.get()
        ).whenComplete((ignored, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else {
                this.filtersCache.invalidate(rname.toString());
                this.eventBus.publish(RepositoryEvents.ADDRESS, RepositoryEvents.upsert(name));
                ctx.response().setStatusCode(200).end();
            }
        });
    }

    /**
     * DELETE /api/v1/repositories/:name — delete repository.
     * @param ctx Routing context
     */
    private void deleteRepository(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        CompletableFuture.supplyAsync(
            () -> this.crs.exists(rname),
            HandlerExecutor.get()
        ).whenComplete((exists, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                return;
            }
            if (!Boolean.TRUE.equals(exists)) {
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
                return;
            }
            this.repoData.remove(rname)
                .thenRun(() -> this.crs.delete(rname))
                .exceptionally(exc -> {
                    this.crs.delete(rname);
                    return null;
                });
            this.filtersCache.invalidate(rname.toString());
            this.eventBus.publish(RepositoryEvents.ADDRESS, RepositoryEvents.remove(name));
            this.events.ifPresent(item -> item.stopProxyMetadataProcessing(name));
            ctx.response().setStatusCode(200).end();
        });
    }

    /**
     * PUT /api/v1/repositories/:name/move — rename/move repository.
     * @param ctx Routing context
     */
    private void moveRepository(final RoutingContext ctx) {
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
        final String newName = body.getString("new_name", "").trim();
        if (newName.isEmpty()) {
            ApiResponse.sendError(ctx, 400, "BAD_REQUEST", "new_name is required");
            return;
        }
        CompletableFuture.supplyAsync(
            () -> this.crs.exists(rname),
            HandlerExecutor.get()
        ).whenComplete((exists, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
                return;
            }
            if (!Boolean.TRUE.equals(exists)) {
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
                return;
            }
            final RepositoryName newrname = new RepositoryName.Simple(newName);
            this.repoData.move(rname, newrname)
                .thenRun(() -> this.crs.move(rname, newrname));
            this.filtersCache.invalidate(rname.toString());
            this.eventBus.publish(
                RepositoryEvents.ADDRESS, RepositoryEvents.move(name, newName)
            );
            ctx.response().setStatusCode(200).end();
        });
    }

    /**
     * Returns true when the given repository type is a group type
     * (i.e. its name ends with the {@code -group} suffix).
     * Group repositories are pure routing abstractions — they have no
     * storage of their own, only a {@code members} list.
     * @param type Repository type string (may be null)
     * @return True if the type ends with {@code -group}
     */
    private static boolean isGroupType(final String type) {
        return type != null && type.endsWith("-group");
    }

    /**
     * GET /api/v1/repositories/:name/members — get group repository members.
     * @param ctx Routing context
     */
    private void getMembers(final RoutingContext ctx) {
        final String name = ctx.pathParam("name");
        final RepositoryName rname = new RepositoryName.Simple(name);
        CompletableFuture.supplyAsync((java.util.function.Supplier<JsonObject>) () -> {
            if (!this.crs.exists(rname)) {
                return null;
            }
            final JsonStructure config = this.crs.value(rname);
            if (config == null) {
                return null;
            }
            final javax.json.JsonObject jconfig;
            if (config instanceof javax.json.JsonObject) {
                jconfig = (javax.json.JsonObject) config;
            } else {
                return new JsonObject().put("members", new JsonArray()).put("type", "not-a-group");
            }
            final javax.json.JsonObject repoSection = jconfig.containsKey(RepositoryHandler.REPO)
                ? jconfig.getJsonObject(RepositoryHandler.REPO) : jconfig;
            final String repoType = repoSection.getString("type", "");
            if (!repoType.endsWith("-group")) {
                return new JsonObject().put("members", new JsonArray()).put("type", "not-a-group");
            }
            final JsonArray members = new JsonArray();
            if (repoSection.containsKey("remotes")) {
                final javax.json.JsonArray remotes = repoSection.getJsonArray("remotes");
                for (int idx = 0; idx < remotes.size(); idx++) {
                    final javax.json.JsonObject remote = remotes.getJsonObject(idx);
                    members.add(remote.getString("url", remote.toString()));
                }
            }
            return new JsonObject().put("members", members).put("type", repoType);
        }, HandlerExecutor.get()).whenComplete((result, err) -> {
            if (err != null) {
                ApiResponse.sendError(ctx, 500, "INTERNAL_ERROR", err.getMessage());
            } else if (result == null) {
                ApiResponse.sendError(
                    ctx, 404, "NOT_FOUND",
                    String.format("Repository '%s' not found", name)
                );
            } else {
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(result.encode());
            }
        });
    }
}
