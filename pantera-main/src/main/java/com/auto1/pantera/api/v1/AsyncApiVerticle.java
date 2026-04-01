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

import com.auto1.pantera.api.ManageRepoSettings;
import com.auto1.pantera.api.ManageRoles;
import com.auto1.pantera.api.ManageUsers;
import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.cooldown.CooldownSupport;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.db.dao.AuthProviderDao;
import com.auto1.pantera.db.dao.AuthSettingsDao;
import com.auto1.pantera.db.dao.RoleDao;
import com.auto1.pantera.db.dao.RepositoryDao;
import com.auto1.pantera.db.dao.StorageAliasDao;
import com.auto1.pantera.db.dao.UserDao;
import com.auto1.pantera.db.dao.UserTokenDao;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.RepoData;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.cache.PanteraCaches;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import com.auto1.pantera.settings.users.CrudRoles;
import com.auto1.pantera.settings.users.CrudUsers;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Unified management API verticle serving /api/v1/* endpoints.
 * Replaces the old RestApi verticle. Uses plain Vert.x Router.
 */
public final class AsyncApiVerticle extends AbstractVerticle {

    /**
     * Pantera caches.
     */
    private final PanteraCaches caches;

    /**
     * Pantera settings storage.
     */
    private final Storage configsStorage;

    /**
     * Application port.
     */
    private final int port;

    /**
     * Actual port the server is listening on (set after listen succeeds).
     */
    private volatile int actualPort = -1;

    /**
     * Pantera security.
     */
    private final PanteraSecurity security;

    /**
     * SSL KeyStore.
     */
    private final Optional<KeyStore> keystore;

    /**
     * JWT authentication provider.
     */
    private final JWTAuth jwt;

    /**
     * Artifact metadata events queue.
     */
    private final Optional<MetadataEventQueues> events;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown metadata filtering service (for cache invalidation on unblock).
     */
    private final CooldownMetadataService cooldownMetadata;

    /**
     * Pantera settings.
     */
    private final Settings settings;

    /**
     * Artifact index for search operations.
     */
    private final ArtifactIndex artifactIndex;

    /**
     * Database data source (nullable). When present, DAO-backed
     * implementations are used instead of YAML-backed ones.
     */
    private final DataSource dataSource;

    /**
     * JWT tokens provider (RS256). Used for token issuance and auth handler.
     */
    private final JwtTokens jwtTokens;

    /**
     * Primary constructor.
     * @param caches Pantera settings caches
     * @param configsStorage Pantera settings storage
     * @param port Port to run API on
     * @param security Pantera security
     * @param keystore KeyStore
     * @param jwt JWT authentication provider (Vert.x, for route protection)
     * @param events Artifact metadata events queue
     * @param cooldown Cooldown service
     * @param settings Pantera settings
     * @param artifactIndex Artifact index for search
     * @param dataSource Database data source, nullable
     * @param jwtTokens RS256 tokens provider for token issuance
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public AsyncApiVerticle(
        final PanteraCaches caches,
        final Storage configsStorage,
        final int port,
        final PanteraSecurity security,
        final Optional<KeyStore> keystore,
        final JWTAuth jwt,
        final Optional<MetadataEventQueues> events,
        final CooldownService cooldown,
        final Settings settings,
        final ArtifactIndex artifactIndex,
        final DataSource dataSource,
        final JwtTokens jwtTokens
    ) {
        this.caches = caches;
        this.configsStorage = configsStorage;
        this.port = port;
        this.security = security;
        this.keystore = keystore;
        this.jwt = jwt;
        this.events = events;
        this.cooldown = cooldown;
        this.cooldownMetadata = CooldownSupport.createMetadataService(cooldown, settings);
        this.settings = settings;
        this.artifactIndex = artifactIndex;
        this.dataSource = dataSource;
        this.jwtTokens = jwtTokens;
    }

    /**
     * Convenience constructor (no JwtTokens — creates a stub tokens provider).
     * @param settings Pantera settings
     * @param port Port to start verticle on
     * @param jwt JWT authentication provider
     * @param dataSource Database data source, nullable
     */
    public AsyncApiVerticle(final Settings settings, final int port,
        final JWTAuth jwt, final DataSource dataSource) {
        this(
            settings.caches(), settings.configStorage(),
            port, settings.authz(), settings.keyStore(), jwt,
            settings.artifactMetadata(),
            CooldownSupport.create(settings),
            settings,
            settings.artifactIndex(),
            dataSource,
            null
        );
    }

    /**
     * Returns the actual port the server is listening on.
     * Returns -1 if the server has not started yet.
     * @return Actual port or -1
     */
    public int actualPort() {
        return this.actualPort;
    }

    @Override
    public void start() {
        final Router router = Router.router(this.vertx);
        // Create named worker pool for blocking DAO calls
        final WorkerExecutor apiWorkers =
            this.vertx.createSharedWorkerExecutor("api-workers");
        // Store in routing context for handlers to use
        router.route("/api/v1/*").handler(ctx -> {
            ctx.put("apiWorkers", apiWorkers);
            ctx.next();
        });
        // Body handler for all API routes (1MB limit)
        router.route("/api/v1/*").handler(BodyHandler.create().setBodyLimit(1_048_576));
        // CORS headers
        router.route("/api/v1/*").handler(ctx -> {
            ctx.response()
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader(
                    "Access-Control-Allow-Methods",
                    "GET,POST,PUT,DELETE,HEAD,OPTIONS"
                )
                .putHeader(
                    "Access-Control-Allow-Headers",
                    "Authorization,Content-Type,Accept"
                )
                .putHeader("Access-Control-Max-Age", "3600");
            if ("OPTIONS".equals(ctx.request().method().name())) {
                ctx.response().setStatusCode(204).end();
            } else {
                ctx.next();
            }
        });
        // Health endpoint (public, no auth)
        router.get("/api/v1/health").handler(ctx ->
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("status", "ok").encode())
        );
        // Resolve DAO implementations
        final BlockingStorage asto = new BlockingStorage(this.configsStorage);
        final CrudRepoSettings crs;
        final ManageRepoSettings manageRepo;
        if (this.dataSource != null) {
            crs = new RepositoryDao(this.dataSource);
            manageRepo = null;
        } else {
            manageRepo = new ManageRepoSettings(asto);
            crs = manageRepo;
        }
        final CrudUsers users;
        final CrudRoles roles;
        if (this.dataSource != null) {
            // Database is the single source of truth
            users = new UserDao(this.dataSource);
            roles = new RoleDao(this.dataSource);
        } else if (this.security.policyStorage().isPresent()) {
            final Storage policyStorage = this.security.policyStorage().get();
            users = new ManageUsers(new BlockingStorage(policyStorage));
            roles = new ManageRoles(new BlockingStorage(policyStorage));
        } else {
            users = null;
            roles = null;
        }
        // Auth handler routes (token generation + providers are public)
        final AuthHandler authHandler = new AuthHandler(
            this.jwtTokens,
            this.security.authentication(),
            users,
            this.security.policy(),
            this.dataSource != null ? new AuthProviderDao(this.dataSource) : null,
            this.dataSource != null ? new UserTokenDao(this.dataSource) : null,
            this.dataSource != null ? new AuthSettingsDao(this.dataSource) : null
        );
        authHandler.register(router);
        // JWT auth for all /api/v1/* routes EXCEPT download-direct (uses HMAC token auth).
        // Uses UnifiedJwtAuthHandler (RS256 via Auth0 java-jwt) instead of Vert.x JWTAuthHandler.
        // After validation, bridges the result into ctx.setUser() so all downstream handlers
        // that read ctx.user().principal() continue to work unchanged.
        final com.auto1.pantera.auth.UnifiedJwtAuthHandler unifiedAuth =
            this.jwtTokens != null
                ? (com.auto1.pantera.auth.UnifiedJwtAuthHandler) this.jwtTokens.auth()
                : null;
        router.route("/api/v1/*").handler(ctx -> {
            if (ctx.request().path().contains("/artifact/download-direct")) {
                ctx.next();
                return;
            }
            if (unifiedAuth == null) {
                // No RS256 keys configured — fall back to legacy Vert.x JWTAuth
                JWTAuthHandler.create(this.jwt).handle(ctx);
                return;
            }
            final String authHeader = ctx.request().getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Bearer token required");
                return;
            }
            final String rawToken = authHeader.substring(7);
            unifiedAuth.user(rawToken).toCompletableFuture()
                .thenAccept(userOpt -> {
                    if (userOpt.isPresent()) {
                        final com.auto1.pantera.http.auth.AuthUser authUser = userOpt.get();
                        // Bridge into Vert.x User so ctx.user().principal() works
                        // for all downstream handlers (me, generate, list, settings, etc.)
                        final io.vertx.core.json.JsonObject principal = new io.vertx.core.json.JsonObject()
                            .put(com.auto1.pantera.api.AuthTokenRest.SUB, authUser.name())
                            .put(com.auto1.pantera.api.AuthTokenRest.CONTEXT, authUser.authContext());
                        ctx.setUser(io.vertx.ext.auth.User.fromToken(rawToken));
                        ctx.user().principal().mergeIn(principal);
                        ctx.next();
                    } else {
                        ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Invalid or expired token");
                    }
                })
                .exceptionally(err -> {
                    ApiResponse.sendError(ctx, 401, "UNAUTHORIZED", "Authentication failed");
                    return null;
                });
        });
        // Register protected auth routes (requires JWT)
        authHandler.registerProtected(router);
        // Register all handler groups
        new RepositoryHandler(
            this.caches.filtersCache(), crs,
            new RepoData(this.configsStorage, this.caches.storagesCache()),
            this.security.policy(), this.events,
            this.cooldown,
            this.vertx.eventBus()
        ).register(router);
        if (users != null) {
            new UserHandler(users, this.caches, this.security).register(router);
        }
        if (roles != null) {
            new RoleHandler(
                roles, this.caches.policyCache(), this.security.policy()
            ).register(router);
        }
        new StorageAliasHandler(
            this.caches.storagesCache(), asto, this.security.policy(),
            this.dataSource != null ? new StorageAliasDao(this.dataSource) : null
        ).register(router);
        new SettingsHandler(
            this.port, this.settings, manageRepo, this.dataSource,
            this.security.policy()
        ).register(router);
        new DashboardHandler(crs, this.dataSource).register(router);
        new ArtifactHandler(
            crs, new RepoData(this.configsStorage, this.caches.storagesCache()),
            this.security.policy()
        ).register(router);
        new CooldownHandler(
            this.cooldown, this.cooldownMetadata, crs, this.settings.cooldown(), this.dataSource,
            this.security.policy()
        ).register(router);
        new SearchHandler(this.artifactIndex, this.security.policy()).register(router);
        new PypiHandler(
            crs, new RepoData(this.configsStorage, this.caches.storagesCache())
        ).register(router);
        if (this.dataSource != null) {
            new AdminAuthHandler(
                new AuthSettingsDao(this.dataSource),
                new UserTokenDao(this.dataSource),
                this.jwtTokens != null ? this.jwtTokens.blocklist() : null
            ).register(router);
        }
        // Start server
        final HttpServer server;
        final String schema;
        if (this.keystore.isPresent() && this.keystore.get().enabled()) {
            final HttpServerOptions sslOptions = this.keystore.get()
                .secureOptions(this.vertx, this.configsStorage);
            sslOptions
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true)
                .setIdleTimeout(60)
                .setUseAlpn(true);
            server = this.vertx.createHttpServer(sslOptions);
            schema = "https";
        } else {
            server = this.vertx.createHttpServer(
                new HttpServerOptions()
                    .setTcpNoDelay(true)
                    .setTcpKeepAlive(true)
                    .setIdleTimeout(60)
                    .setUseAlpn(true)
                    .setHttp2ClearTextEnabled(true)
            );
            schema = "http";
        }
        server.requestHandler(router)
            .listen(this.port)
            .onComplete(res -> {
                if (res.succeeded()) {
                    this.actualPort = res.result().actualPort();
                }
                EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("AsyncApiVerticle started")
                    .eventCategory("api")
                    .eventAction("server_start")
                    .eventOutcome("success")
                    .field("url.port", this.actualPort)
                    .field("url.scheme", schema)
                    .log();
            })
            .onFailure(
                err -> EcsLogger.error("com.auto1.pantera.api.v1")
                    .message("Failed to start AsyncApiVerticle")
                    .eventCategory("api")
                    .eventAction("server_start")
                    .eventOutcome("failure")
                    .field("url.port", this.port)
                    .error(err)
                    .log()
            );
    }
}
