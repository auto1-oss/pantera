/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.api;

import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.blocking.BlockingStorage;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.cooldown.CooldownService;
import com.auto1.pantera.cooldown.CooldownSupport;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.policy.CachedYamlPolicy;
import com.auto1.pantera.settings.PanteraSecurity;
import com.auto1.pantera.settings.RepoData;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.cache.PanteraCaches;
import com.auto1.pantera.settings.repo.CrudRepoSettings;
import com.auto1.pantera.settings.users.CrudRoles;
import com.auto1.pantera.settings.users.CrudUsers;
import com.auto1.pantera.db.dao.RepositoryDao;
import com.auto1.pantera.db.dao.UserDao;
import com.auto1.pantera.db.dao.RoleDao;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.Arrays;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Vert.x {@link io.vertx.core.Verticle} for exposing Rest API operations.
 * @since 0.26
 */
public final class RestApi extends AbstractVerticle {

    /**
     * The name of the security scheme (from the Open API description yaml).
     */
    private static final String SECURITY_SCHEME = "bearerAuth";

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
     * KeyStore.
     */
    private final Optional<KeyStore> keystore;

    /**
     * Jwt authentication provider.
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
     * Primary ctor.
     * @param caches Pantera settings caches
     * @param configsStorage Pantera settings storage
     * @param port Port to run API on
     * @param security Pantera security
     * @param keystore KeyStore
     * @param jwt Jwt authentication provider
     * @param events Artifact metadata events queue
     * @param cooldown Cooldown service
     * @param settings Pantera settings
     * @param artifactIndex Artifact index for search
     * @param dataSource Database data source, nullable
     */
    public RestApi(
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
     * Ctor.
     * @param settings Pantera settings
     * @param port Port to start verticle on
     * @param jwt Jwt authentication provider
     * @param dataSource Database data source, nullable
     */
    public RestApi(final Settings settings, final int port, final JWTAuth jwt,
        final DataSource dataSource) {
        this(
            settings.caches(), settings.configStorage(),
            port, settings.authz(), settings.keyStore(), jwt, settings.artifactMetadata(),
            CooldownSupport.create(settings),
            settings,
            settings.artifactIndex(),
            dataSource
        );
    }

    @Override
    public void start() throws Exception {
        RouterBuilder.create(this.vertx, "swagger-ui/yaml/repo.yaml").compose(
            repoRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/users.yaml").compose(
                userRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/token-gen.yaml").compose(
                    tokenRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/settings.yaml").compose(
                        settingsRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/roles.yaml").compose(
                            rolesRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/search.yaml").onSuccess(
                                searchRb -> this.startServices(repoRb, userRb, tokenRb, settingsRb, rolesRb, searchRb)
                            ).onFailure(Throwable::printStackTrace)
                        ).onFailure(Throwable::printStackTrace)
                    )
                )
            )
        );
    }

    /**
     * Start rest services.
     * @param repoRb Repository RouterBuilder
     * @param userRb User RouterBuilder
     * @param tokenRb Token RouterBuilder
     * @param settingsRb Settings RouterBuilder
     * @param rolesRb Roles RouterBuilder
     * @param searchRb Search RouterBuilder
     */
    private void startServices(final RouterBuilder repoRb, final RouterBuilder userRb,
        final RouterBuilder tokenRb, final RouterBuilder settingsRb, final RouterBuilder rolesRb,
        final RouterBuilder searchRb) {
        this.addJwtAuth(tokenRb, repoRb, userRb, settingsRb, rolesRb, searchRb);
        final BlockingStorage asto = new BlockingStorage(this.configsStorage);
        final ManageRepoSettings manageRepo = new ManageRepoSettings(asto);
        final CrudRepoSettings crs = this.dataSource != null
            ? new RepositoryDao(this.dataSource)
            : manageRepo;
        new RepositoryRest(
            this.caches.filtersCache(),
            crs,
            new RepoData(this.configsStorage, this.caches.storagesCache()),
            this.security.policy(), this.events,
            this.cooldown,
            this.vertx.eventBus()
        ).init(repoRb);
        new StorageAliasesRest(
            this.caches.storagesCache(), asto, this.security.policy()
        ).init(repoRb);
        if (this.security.policyStorage().isPresent()) {
            Storage policyStorage = this.security.policyStorage().get();
            final CrudUsers users = this.dataSource != null
                ? new UserDao(this.dataSource)
                : new ManageUsers(new BlockingStorage(policyStorage));
            new UsersRest(
                    users,
                    this.caches, this.security
            ).init(userRb);
            if (this.security.policy() instanceof CachedYamlPolicy) {
                final CrudRoles roles = this.dataSource != null
                    ? new RoleDao(this.dataSource)
                    : new ManageRoles(new BlockingStorage(policyStorage));
                new RolesRest(
                        roles,
                        this.caches.policyCache(), this.security.policy()
                ).init(rolesRb);
            }
        }
        new SettingsRest(this.port, this.settings, manageRepo).init(settingsRb);
        new SearchRest(this.artifactIndex, this.security.policy()).init(searchRb);
        final Router router = repoRb.createRouter();
        router.route("/*").subRouter(rolesRb.createRouter());
        router.route("/*").subRouter(userRb.createRouter());
        router.route("/*").subRouter(tokenRb.createRouter());
        router.route("/*").subRouter(settingsRb.createRouter());
        router.route("/*").subRouter(searchRb.createRouter());
        // CRITICAL: Add simple health endpoint BEFORE StaticHandler
        // This avoids StaticHandler's file-serving leak for health checks
        router.get("/api/health").handler(ctx -> {
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end("{\"status\":\"ok\"}");
        });

        router.route("/api/*").handler(
            StaticHandler.create("swagger-ui")
                .setIndexPage("index.html")
                .setCachingEnabled(false)
                .setFilesReadOnly(false)
        );
        final HttpServer server;
        final String schema;
        if (this.keystore.isPresent() && this.keystore.get().enabled()) {
            // SSL server with TCP optimizations for low latency
            final io.vertx.core.http.HttpServerOptions sslOptions =
                this.keystore.get().secureOptions(this.vertx, this.configsStorage);
            sslOptions
                .setTcpNoDelay(true)      // Disable Nagle's algorithm for low latency
                .setTcpKeepAlive(true)    // Enable keep-alive for connection reuse
                .setIdleTimeout(60);      // Close idle connections after 60 seconds
            server = vertx.createHttpServer(sslOptions);
            schema = "https";
        } else {
            // Non-SSL server with TCP optimizations matching main server config
            server = this.vertx.createHttpServer(
                new io.vertx.core.http.HttpServerOptions()
                    .setTcpNoDelay(true)      // Disable Nagle's algorithm for low latency
                    .setTcpKeepAlive(true)    // Enable keep-alive for connection reuse
                    .setIdleTimeout(60)       // Close idle connections after 60 seconds
                    .setUseAlpn(true)         // Enable ALPN for HTTP/2 negotiation
                    .setHttp2ClearTextEnabled(true)  // Enable HTTP/2 over cleartext (h2c)
            );
            schema = "http";
        }
        server.requestHandler(router)
            .listen(this.port)
            .onComplete(res -> EcsLogger.info("com.auto1.pantera.api")
                .message("Rest API started")
                .eventCategory("api")
                .eventAction("server_start")
                .eventOutcome("success")
                .field("url.port", this.port)
                .field("url.scheme", schema)
                .field("url.full", schema + "://localhost:" + this.port + "/api/index.html")
                .log())
            .onFailure(err -> EcsLogger.error("com.auto1.pantera.api")
                .message("Failed to start Rest API")
                .eventCategory("api")
                .eventAction("server_start")
                .eventOutcome("failure")
                .field("url.port", this.port)
                .error(err)
                .log());
    }

    /**
     * Create and add all JWT-auth related settings:
     *  - initialize rest method to issue JWT tokens;
     *  - add security handlers to all REST API requests.
     * @param token Auth tokens generate API router builder
     * @param builders Router builders to add token auth to
     */
    private void addJwtAuth(final RouterBuilder token, final RouterBuilder... builders) {
        new AuthTokenRest(new JwtTokens(this.jwt, this.settings.jwtSettings()), this.security.authentication()).init(token);
        Arrays.stream(builders).forEach(
            item -> item.securityHandler(RestApi.SECURITY_SCHEME, JWTAuthHandler.create(this.jwt))
        );
    }
}
