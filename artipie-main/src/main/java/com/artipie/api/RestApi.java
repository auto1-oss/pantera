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
import io.vertx.core.http.HttpServer;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
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
    public void start() throws Exception {
        RouterBuilder.create(this.vertx, "swagger-ui/yaml/repo.yaml").compose(
            repoRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/users.yaml").compose(
                userRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/token-gen.yaml").compose(
                    tokenRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/settings.yaml").compose(
                        settingsRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/roles.yaml").onSuccess(
                            rolesRb -> this.startServices(repoRb, userRb, tokenRb, settingsRb, rolesRb)
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
     */
    private void startServices(final RouterBuilder repoRb, final RouterBuilder userRb,
        final RouterBuilder tokenRb, final RouterBuilder settingsRb, final RouterBuilder rolesRb) {
        this.addJwtAuth(tokenRb, repoRb, userRb, settingsRb, rolesRb);
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
        final Router router = repoRb.createRouter();
        router.route("/*").subRouter(rolesRb.createRouter());
        router.route("/*").subRouter(userRb.createRouter());
        router.route("/*").subRouter(tokenRb.createRouter());
        router.route("/*").subRouter(settingsRb.createRouter());
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
            server = vertx.createHttpServer(
                this.keystore.get().secureOptions(this.vertx, this.configsStorage)
            );
            schema = "https";
        } else {
            server = this.vertx.createHttpServer();
            schema = "http";
        }
        server.requestHandler(router)
            .listen(this.port)
            .onComplete(res -> EcsLogger.info("com.artipie.api")
                .message("Rest API started")
                .eventCategory("api")
                .eventAction("server_start")
                .eventOutcome("success")
                .field("url.port", this.port)
                .field("url.scheme", schema)
                .field("url.full", schema + "://localhost:" + this.port + "/api/index.html")
                .log())
            .onFailure(err -> EcsLogger.error("com.artipie.api")
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
        new AuthTokenRest(new JwtTokens(this.jwt), this.security.authentication()).init(token);
        Arrays.stream(builders).forEach(
            item -> item.securityHandler(RestApi.SECURITY_SCHEME, JWTAuthHandler.create(this.jwt))
        );
    }
}
