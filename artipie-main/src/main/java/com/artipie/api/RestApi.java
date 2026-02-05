/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.api.ssl.KeyStore;
import com.artipie.asto.Storage;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.auth.JwtTokens;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownSupport;
import com.artipie.scheduling.MetadataEventQueues;
import com.artipie.security.policy.CachedYamlPolicy;
import com.artipie.settings.ArtipieSecurity;
import com.artipie.settings.RepoData;
import com.artipie.settings.Settings;
import com.artipie.settings.cache.ArtipieCaches;
import com.artipie.http.log.EcsLogger;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.contract.OpenAPIContract;
import java.util.Arrays;
import java.util.Optional;

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
     * Artipie caches.
     */
    private final ArtipieCaches caches;

    /**
     * Artipie settings storage.
     */
    private final Storage configsStorage;

    /**
     * Application port.
     */
    private final int port;

    /**
     * Artipie security.
     */
    private final ArtipieSecurity security;

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
     * Artipie settings.
     */
    private final Settings settings;

    /**
     * HTTP server instance for cleanup.
     */
    private HttpServer server;

    /**
     * Primary ctor.
     * @param caches Artipie settings caches
     * @param configsStorage Artipie settings storage
     * @param port Port to run API on
     * @param security Artipie security
     * @param keystore KeyStore
     * @param jwt Jwt authentication provider
     * @param events Artifact metadata events queue
     */
    public RestApi(
        final ArtipieCaches caches,
        final Storage configsStorage,
        final int port,
        final ArtipieSecurity security,
        final Optional<KeyStore> keystore,
        final JWTAuth jwt,
        final Optional<MetadataEventQueues> events,
        final CooldownService cooldown,
        final Settings settings
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
    }

    /**
     * Ctor.
     * @param settings Artipie settings
     * @param port Port to start verticle on
     * @param jwt Jwt authentication provider
     */
    public RestApi(final Settings settings, final int port, final JWTAuth jwt) {
        this(
            settings.caches(), settings.configStorage(),
            port, settings.authz(), settings.keyStore(), jwt, settings.artifactMetadata(),
            CooldownSupport.create(settings),
            settings
        );
    }

    @Override
    public void start(final Promise<Void> startPromise) {
        OpenAPIContract.from(this.vertx, "swagger-ui/yaml/repo.yaml").compose(
            repoContract -> OpenAPIContract.from(this.vertx, "swagger-ui/yaml/users.yaml").compose(
                userContract -> OpenAPIContract.from(this.vertx, "swagger-ui/yaml/token-gen.yaml").compose(
                    tokenContract -> OpenAPIContract.from(this.vertx, "swagger-ui/yaml/settings.yaml").compose(
                        settingsContract -> OpenAPIContract.from(this.vertx, "swagger-ui/yaml/roles.yaml").compose(
                            rolesContract -> OpenAPIContract.from(this.vertx, "swagger-ui/yaml/cache.yaml").compose(
                                cacheContract -> {
                                    final RouterBuilder repoRb = RouterBuilder.create(this.vertx, repoContract);
                                    final RouterBuilder userRb = RouterBuilder.create(this.vertx, userContract);
                                    final RouterBuilder tokenRb = RouterBuilder.create(this.vertx, tokenContract);
                                    final RouterBuilder settingsRb = RouterBuilder.create(this.vertx, settingsContract);
                                    final RouterBuilder rolesRb = RouterBuilder.create(this.vertx, rolesContract);
                                    final RouterBuilder cacheRb = RouterBuilder.create(this.vertx, cacheContract);
                                    return this.startServices(repoRb, userRb, tokenRb, settingsRb, rolesRb, cacheRb);
                                }
                            )
                        )
                    )
                )
            )
        ).onSuccess(v -> startPromise.complete())
         .onFailure(err -> {
             err.printStackTrace();
             startPromise.fail(err);
         });
    }

    @Override
    public void stop(final Promise<Void> stopPromise) {
        if (this.server != null) {
            this.server.close()
                .onSuccess(v -> stopPromise.complete())
                .onFailure(stopPromise::fail);
        } else {
            stopPromise.complete();
        }
    }

    /**
     * Start rest services.
     * @param repoRb Repository RouterBuilder
     * @param userRb User RouterBuilder
     * @param tokenRb Token RouterBuilder
     * @param settingsRb Settings RouterBuilder
     * @param rolesRb Roles RouterBuilder
     * @param cacheRb Cache RouterBuilder
     * @return Future that completes when server is listening
     */
    private Future<Void> startServices(final RouterBuilder repoRb, final RouterBuilder userRb,
        final RouterBuilder tokenRb, final RouterBuilder settingsRb, final RouterBuilder rolesRb,
        final RouterBuilder cacheRb) {
        this.addJwtAuth(tokenRb, repoRb, userRb, settingsRb, rolesRb, cacheRb);
        final BlockingStorage asto = new BlockingStorage(this.configsStorage);
        new RepositoryRest(
            this.caches.filtersCache(),
            new ManageRepoSettings(asto),
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
            new UsersRest(
                    new ManageUsers(new BlockingStorage(policyStorage)),
                    this.caches, this.security
            ).init(userRb);
            if (this.security.policy() instanceof CachedYamlPolicy) {
                new RolesRest(
                        new ManageRoles(new BlockingStorage(policyStorage)),
                        this.caches.policyCache(), this.security.policy()
                ).init(rolesRb);
            }
        }
        new SettingsRest(this.port, this.settings).init(settingsRb);
        new CacheRest(this.security.policy()).init(cacheRb);
        final Router router = repoRb.createRouter();
        router.route("/*").subRouter(rolesRb.createRouter());
        router.route("/*").subRouter(userRb.createRouter());
        router.route("/*").subRouter(tokenRb.createRouter());
        router.route("/*").subRouter(settingsRb.createRouter());
        router.route("/*").subRouter(cacheRb.createRouter());
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
        final String schema;
        if (this.keystore.isPresent() && this.keystore.get().enabled()) {
            // SSL server with TCP optimizations for low latency
            final io.vertx.core.http.HttpServerOptions sslOptions =
                this.keystore.get().secureOptions(this.vertx, this.configsStorage);
            sslOptions
                .setTcpNoDelay(true)      // Disable Nagle's algorithm for low latency
                .setTcpKeepAlive(true)    // Enable keep-alive for connection reuse
                .setIdleTimeout(60);      // Close idle connections after 60 seconds
            this.server = vertx.createHttpServer(sslOptions);
            schema = "https";
        } else {
            // Non-SSL server with TCP optimizations matching main server config
            this.server = this.vertx.createHttpServer(
                new io.vertx.core.http.HttpServerOptions()
                    .setTcpNoDelay(true)      // Disable Nagle's algorithm for low latency
                    .setTcpKeepAlive(true)    // Enable keep-alive for connection reuse
                    .setIdleTimeout(60)       // Close idle connections after 60 seconds
                    .setUseAlpn(true)         // Enable ALPN for HTTP/2 negotiation
                    .setHttp2ClearTextEnabled(true)  // Enable HTTP/2 over cleartext (h2c)
            );
            schema = "http";
        }
        final String finalSchema = schema;
        return this.server.requestHandler(router)
            .listen(this.port)
            .onSuccess(s -> EcsLogger.info("com.artipie.api")
                .message("Rest API started")
                .eventCategory("api")
                .eventAction("server_start")
                .eventOutcome("success")
                .field("url.port", this.port)
                .field("url.scheme", finalSchema)
                .field("url.full", finalSchema + "://localhost:" + this.port + "/api/index.html")
                .log())
            .onFailure(err -> EcsLogger.error("com.artipie.api")
                .message("Failed to start Rest API")
                .eventCategory("api")
                .eventAction("server_start")
                .eventOutcome("failure")
                .field("url.port", this.port)
                .error(err)
                .log())
            .mapEmpty();
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
            item -> item.security(RestApi.SECURITY_SCHEME).httpHandler(JWTAuthHandler.create(this.jwt))
        );
    }
}
