/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
import com.auto1.pantera.db.dao.AuthProviderDao;
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
import com.auto1.pantera.settings.repo.DualCrudRepoSettings;
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
     * Primary constructor.
     * @param caches Pantera settings caches
     * @param configsStorage Pantera settings storage
     * @param port Port to run API on
     * @param security Pantera security
     * @param keystore KeyStore
     * @param jwt JWT authentication provider
     * @param events Artifact metadata events queue
     * @param cooldown Cooldown service
     * @param settings Pantera settings
     * @param artifactIndex Artifact index for search
     * @param dataSource Database data source, nullable
     */
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
        final DataSource dataSource
    ) {
        this.caches = caches;
        this.configsStorage = configsStorage;
        this.port = port;
        this.security = security;
        this.keystore = keystore;
        this.jwt = jwt;
        this.events = events;
        this.cooldown = cooldown;
        this.settings = settings;
        this.artifactIndex = artifactIndex;
        this.dataSource = dataSource;
    }

    /**
     * Convenience constructor.
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
            dataSource
        );
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
        final ManageRepoSettings manageRepo = new ManageRepoSettings(asto);
        final CrudRepoSettings crs;
        if (this.dataSource != null) {
            crs = new DualCrudRepoSettings(
                new RepositoryDao(this.dataSource), manageRepo
            );
        } else {
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
            new JwtTokens(this.jwt, this.settings.jwtSettings()),
            this.security.authentication(),
            users,
            this.security.policy(),
            this.dataSource != null ? new AuthProviderDao(this.dataSource) : null,
            this.dataSource != null ? new UserTokenDao(this.dataSource) : null
        );
        authHandler.register(router);
        // JWT auth for all remaining /api/v1/* routes
        router.route("/api/v1/*").handler(JWTAuthHandler.create(this.jwt));
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
            this.cooldown, crs, this.settings.cooldown(), this.dataSource,
            this.security.policy()
        ).register(router);
        new SearchHandler(this.artifactIndex, this.security.policy()).register(router);
        // Start server
        final HttpServer server;
        final String schema;
        if (this.keystore.isPresent() && this.keystore.get().enabled()) {
            final HttpServerOptions sslOptions = this.keystore.get()
                .secureOptions(this.vertx, this.configsStorage);
            sslOptions.setTcpNoDelay(true).setTcpKeepAlive(true).setIdleTimeout(60);
            server = this.vertx.createHttpServer(sslOptions);
            schema = "https";
        } else {
            server = this.vertx.createHttpServer(
                new HttpServerOptions()
                    .setTcpNoDelay(true)
                    .setTcpKeepAlive(true)
                    .setIdleTimeout(60)
            );
            schema = "http";
        }
        server.requestHandler(router)
            .listen(this.port)
            .onComplete(
                res -> EcsLogger.info("com.auto1.pantera.api.v1")
                    .message("AsyncApiVerticle started")
                    .eventCategory("api")
                    .eventAction("server_start")
                    .eventOutcome("success")
                    .field("url.port", this.port)
                    .field("url.scheme", schema)
                    .log()
            )
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
