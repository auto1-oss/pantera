/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.group.GroupNegativeCache;
import com.artipie.http.log.EcsLogger;
import com.artipie.security.policy.Policy;
import com.artipie.api.perms.ApiCachePermission;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.eclipse.jetty.http.HttpStatus;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Optional;

/**
 * REST API for cache management operations.
 * Provides endpoints to invalidate negative caches without process restart.
 * 
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/health - Health check endpoint</li>
 *   <li>GET /api/cache/negative/groups - List all registered groups</li>
 *   <li>GET /api/cache/negative/group/{groupName}/stats - Get cache stats for a group</li>
 *   <li>DELETE /api/cache/negative/group/{groupName} - Clear all negative cache for a group</li>
 *   <li>DELETE /api/cache/negative/group/{groupName}/package - Invalidate specific package</li>
 *   <li>DELETE /api/cache/negative/package - Invalidate package in ALL groups</li>
 * </ul>
 * 
 * @since 1.0
 */
public final class CacheRest extends BaseRest {

    /**
     * Artipie policy.
     */
    private final Policy<?> policy;

    /**
     * Ctor.
     * @param policy Artipie policy for authorization
     */
    public CacheRest(final Policy<?> policy) {
        this.policy = policy;
    }

    @Override
    public void init(final RouterBuilder rbr) {
        // Health check (no auth required)
        rbr.operation("healthCheck")
            .handler(this::healthCheck)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        
        // List all registered groups
        rbr.operation("listCacheGroups")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.READ))
            .handler(this::listGroups)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        
        // Get stats for a specific group
        rbr.operation("getCacheStats")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.READ))
            .handler(this::groupStats)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        
        // Clear all negative cache for a group
        rbr.operation("clearGroupCache")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.WRITE))
            .handler(this::clearGroup)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        
        // Invalidate specific package in a group
        rbr.operation("invalidatePackageInGroup")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.WRITE))
            .handler(this::invalidatePackageInGroup)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
        
        // Invalidate package in ALL groups
        rbr.operation("invalidatePackageGlobally")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.WRITE))
            .handler(this::invalidatePackageGlobally)
            .failureHandler(this.errorHandler(HttpStatus.INTERNAL_SERVER_ERROR_500));
    }

    /**
     * Initialize cache API routes directly on a router without OpenAPI validation.
     * This is used to avoid breaking the main RouterBuilder async chain.
     * @param router The router to add routes to
     */
    public void initDirect(final io.vertx.ext.web.Router router) {
        // List all registered groups
        router.get("/api/v1/cache/negative/groups")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.READ))
            .handler(this::listGroups);
        
        // Get stats for a specific group
        router.get("/api/v1/cache/negative/group/:groupName/stats")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.READ))
            .handler(this::groupStats);
        
        // Clear all negative cache for a group
        router.delete("/api/v1/cache/negative/group/:groupName")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.WRITE))
            .handler(this::clearGroup);
        
        // Invalidate specific package in a group
        router.delete("/api/v1/cache/negative/group/:groupName/package")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.WRITE))
            .handler(this::invalidatePackageInGroup);
        
        // Invalidate package in ALL groups
        router.delete("/api/v1/cache/negative/package")
            .handler(new AuthzHandler(this.policy, ApiCachePermission.WRITE))
            .handler(this::invalidatePackageGlobally);
    }

    /**
     * Health check endpoint.
     * GET /api/health
     */
    private void healthCheck(final RoutingContext ctx) {
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end("{\"status\":\"ok\"}");
    }

    /**
     * List all registered group names.
     * GET /api/cache/negative/groups
     */
    private void listGroups(final RoutingContext ctx) {
        final List<String> groups = GroupNegativeCache.registeredGroups();
        
        final JsonObject response = new JsonObject()
            .put("groups", new JsonArray(groups))
            .put("count", groups.size());
        
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(response.encode());
    }

    /**
     * Get cache stats for a specific group.
     * GET /api/cache/negative/group/{groupName}/stats
     */
    private void groupStats(final RoutingContext ctx) {
        final String groupName = ctx.pathParam("groupName");
        
        final var instance = GroupNegativeCache.getInstance(groupName);
        if (instance.isEmpty()) {
            ctx.response()
                .setStatusCode(404)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("error", "Group not found in cache registry")
                    .put("group", groupName)
                    .encode());
            return;
        }
        
        final GroupNegativeCache cache = instance.get();
        final JsonObject response = new JsonObject()
            .put("group", groupName)
            .put("l1Size", cache.size())
            .put("twoTier", cache.isTwoTier());
        
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(response.encode());
    }

    /**
     * Clear all negative cache entries for a group.
     * DELETE /api/cache/negative/group/{groupName}
     */
    private void clearGroup(final RoutingContext ctx) {
        final String groupName = ctx.pathParam("groupName");
        
        EcsLogger.info("com.artipie.api")
            .message("Clearing negative cache for group")
            .eventCategory("cache")
            .eventAction("clear_group")
            .field("group.name", groupName)
            .log();
        
        GroupNegativeCache.clearGroup(groupName)
            .whenComplete((v, err) -> {
                if (err != null) {
                    EcsLogger.error("com.artipie.api")
                        .message("Failed to clear negative cache")
                        .eventCategory("cache")
                        .eventAction("clear_group")
                        .eventOutcome("failure")
                        .field("group.name", groupName)
                        .error(err)
                        .log();
                    
                    ctx.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("error", "Failed to clear cache")
                            .put("message", err.getMessage())
                            .encode());
                } else {
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("status", "cleared")
                            .put("group", groupName)
                            .encode());
                }
            });
    }

    /**
     * Invalidate specific package in a group.
     * DELETE /api/cache/negative/group/{groupName}/package
     * Body: {"path": "@scope/package-name"}
     */
    private void invalidatePackageInGroup(final RoutingContext ctx) {
        final String groupName = ctx.pathParam("groupName");
        final Optional<String> packagePath = this.readPackagePath(ctx);
        if (packagePath.isEmpty()) {
            return;
        }
        
        // URL decode the package path (in case it's encoded)
        final String decodedPath = URLDecoder.decode(packagePath.get(), StandardCharsets.UTF_8);
        
        EcsLogger.info("com.artipie.api")
            .message("Invalidating negative cache for package in group")
            .eventCategory("cache")
            .eventAction("invalidate_package")
            .field("group.name", groupName)
            .field("package.name", decodedPath)
            .log();
        
        GroupNegativeCache.invalidatePackageInGroup(groupName, decodedPath)
            .whenComplete((v, err) -> {
                if (err != null) {
                    EcsLogger.error("com.artipie.api")
                        .message("Failed to invalidate package cache")
                        .eventCategory("cache")
                        .eventAction("invalidate_package")
                        .eventOutcome("failure")
                        .field("group.name", groupName)
                        .field("package.name", decodedPath)
                        .error(err)
                        .log();
                    
                    ctx.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("error", "Failed to invalidate cache")
                            .put("message", err.getMessage())
                            .encode());
                } else {
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("status", "invalidated")
                            .put("group", groupName)
                            .put("package", decodedPath)
                            .encode());
                }
            });
    }

    /**
     * Invalidate package in ALL groups.
     * DELETE /api/cache/negative/package
     * Body: {"path": "@scope/package-name"}
     */
    private void invalidatePackageGlobally(final RoutingContext ctx) {
        final Optional<String> packagePath = this.readPackagePath(ctx);
        if (packagePath.isEmpty()) {
            return;
        }
        
        // URL decode the package path (in case it's encoded)
        final String decodedPath = URLDecoder.decode(packagePath.get(), StandardCharsets.UTF_8);
        
        EcsLogger.info("com.artipie.api")
            .message("Invalidating negative cache for package globally")
            .eventCategory("cache")
            .eventAction("invalidate_package_global")
            .field("package.name", decodedPath)
            .log();
        
        GroupNegativeCache.invalidatePackageGlobally(decodedPath)
            .whenComplete((v, err) -> {
                if (err != null) {
                    EcsLogger.error("com.artipie.api")
                        .message("Failed to invalidate package cache globally")
                        .eventCategory("cache")
                        .eventAction("invalidate_package_global")
                        .eventOutcome("failure")
                        .field("package.name", decodedPath)
                        .error(err)
                        .log();
                    
                    ctx.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("error", "Failed to invalidate cache")
                            .put("message", err.getMessage())
                            .encode());
                } else {
                    final List<String> groups = GroupNegativeCache.registeredGroups();
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject()
                            .put("status", "invalidated")
                            .put("package", decodedPath)
                            .put("groupsAffected", new JsonArray(groups))
                            .encode());
                }
            });
    }

    private Optional<String> readPackagePath(final RoutingContext ctx) {
        final JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (final Exception e) {
            ctx.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("error", "Invalid JSON body")
                    .put("example", "{\"path\": \"@scope/package-name\"}")
                    .encode());
            return Optional.empty();
        }

        final String packagePath = body != null ? body.getString("path") : null;
        if (packagePath == null || packagePath.isBlank()) {
            ctx.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("error", "Missing 'path' in request body")
                    .put("example", "{\"path\": \"@scope/package-name\"}")
                    .encode());
            return Optional.empty();
        }
        return Optional.of(packagePath);
    }
}
