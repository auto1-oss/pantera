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
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
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
import com.auto1.pantera.prefetch.PrefetchMetrics;
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
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
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
     * Prefetch metrics for the {@link PrefetchStatsHandler} 24h sliding-window
     * stats endpoint. Nullable: when the prefetch subsystem is not wired
     * (DB-less boot, tests without a coordinator), the stats handler returns
     * zero counts.
     *
     * @since 2.2.0
     */
    private final PrefetchMetrics prefetchMetrics;

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
     * @param prefetchMetrics Prefetch metrics for the stats handler, nullable
     */
    public AsyncApiVerticle(
        final PanteraCaches caches,
        final Storage configsStorage,
        final int port,
        final PanteraSecurity security,
        final Optional<KeyStore> keystore,
        final JWTAuth jwt, // NOPMD UnusedFormalParameter - public API; JWTAuth is reserved for upcoming route-protection wiring
        final Optional<MetadataEventQueues> events,
        final CooldownService cooldown,
        final Settings settings,
        final ArtifactIndex artifactIndex,
        final DataSource dataSource,
        final JwtTokens jwtTokens,
        final PrefetchMetrics prefetchMetrics
    ) {
        this.caches = caches;
        this.configsStorage = configsStorage;
        this.port = port;
        this.security = security;
        this.keystore = keystore;
        this.events = events;
        this.cooldown = cooldown;
        this.cooldownMetadata = CooldownSupport.createMetadataService(cooldown, settings);
        this.settings = settings;
        this.artifactIndex = artifactIndex;
        this.dataSource = dataSource;
        this.jwtTokens = jwtTokens;
        this.prefetchMetrics = prefetchMetrics;
    }

    /**
     * Backwards-compatible constructor — defaults
     * {@code prefetchMetrics} to {@code null}. Used by tests and
     * the older convenience constructor.
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
    public AsyncApiVerticle(
        final PanteraCaches caches,
        final Storage configsStorage,
        final int port,
        final PanteraSecurity security,
        final Optional<KeyStore> keystore,
        final JWTAuth jwt, // NOPMD UnusedFormalParameter - public API; JWTAuth is reserved for upcoming route-protection wiring
        final Optional<MetadataEventQueues> events,
        final CooldownService cooldown,
        final Settings settings,
        final ArtifactIndex artifactIndex,
        final DataSource dataSource,
        final JwtTokens jwtTokens
    ) {
        this(
            caches, configsStorage, port, security, keystore, jwt,
            events, cooldown, settings, artifactIndex, dataSource,
            jwtTokens, null
        );
    }

    /**
     * Convenience constructor for deployment from VertxMain.
     * @param settings Pantera settings
     * @param port Port to start verticle on
     * @param jwt JWT authentication provider
     * @param dataSource Database data source, nullable
     * @param jwtTokens RS256 tokens provider for token issuance, nullable
     * @param prefetchMetrics Prefetch metrics for the stats handler, nullable
     */
    public AsyncApiVerticle(final Settings settings, final int port,
        final JWTAuth jwt, final DataSource dataSource,
        final JwtTokens jwtTokens,
        final PrefetchMetrics prefetchMetrics) {
        this(
            settings.caches(), settings.configStorage(),
            port, settings.authz(), settings.keyStore(), jwt,
            settings.artifactMetadata(),
            CooldownSupport.create(settings),
            settings,
            settings.artifactIndex(),
            dataSource,
            jwtTokens,
            prefetchMetrics
        );
    }

    /**
     * Backwards-compatible convenience constructor — defaults
     * {@code prefetchMetrics} to {@code null}.
     * @param settings Pantera settings
     * @param port Port to start verticle on
     * @param jwt JWT authentication provider
     * @param dataSource Database data source, nullable
     * @param jwtTokens RS256 tokens provider for token issuance, nullable
     */
    public AsyncApiVerticle(final Settings settings, final int port,
        final JWTAuth jwt, final DataSource dataSource,
        final JwtTokens jwtTokens) {
        this(settings, port, jwt, dataSource, jwtTokens, null);
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
        // Body handler for all API routes (1MB limit)
        router.route("/api/v1/*").handler(BodyHandler.create().setBodyLimit(1_048_576));
        // Trace context + client.ip MDC setup for all API requests.
        // Must run BEFORE any auth / CORS / handler logic so every log
        // emitted from the request thread carries trace.id, span.id,
        // span.parent.id, and client.ip. The cleanup runs via
        // addEndHandler so we also clear MDC on error paths.
        router.route("/api/v1/*").handler(ctx -> {
            final io.vertx.core.http.HttpServerRequest req = ctx.request();
            // Extract trace context from incoming headers
            final com.auto1.pantera.http.Headers panteraHeaders =
                com.auto1.pantera.http.Headers.from(req.headers().entries());
            final com.auto1.pantera.http.trace.SpanContext span =
                com.auto1.pantera.http.trace.SpanContext.extract(panteraHeaders);
            org.slf4j.MDC.put(
                com.auto1.pantera.http.log.EcsMdc.TRACE_ID, span.traceId()
            );
            org.slf4j.MDC.put(
                com.auto1.pantera.http.log.EcsMdc.SPAN_ID, span.spanId()
            );
            if (span.parentSpanId() != null) {
                org.slf4j.MDC.put(
                    com.auto1.pantera.http.log.EcsMdc.PARENT_SPAN_ID,
                    span.parentSpanId()
                );
            }
            // Extract client IP. X-Forwarded-For (comma-separated, first
            // entry is the real client), fall back to X-Real-IP, then
            // the TCP remote address.
            String clientIp = req.getHeader("X-Forwarded-For");
            if (clientIp != null && clientIp.contains(",")) {
                clientIp = clientIp.substring(0, clientIp.indexOf(',')).trim();
            }
            if (clientIp == null || clientIp.isBlank()) {
                clientIp = req.getHeader("X-Real-IP");
            }
            if (clientIp == null || clientIp.isBlank()) {
                final io.vertx.core.net.SocketAddress remote = req.remoteAddress();
                if (remote != null) {
                    clientIp = remote.host();
                }
            }
            if (clientIp != null && !clientIp.isBlank()) {
                org.slf4j.MDC.put(
                    com.auto1.pantera.http.log.EcsMdc.CLIENT_IP, clientIp
                );
            }
            // Echo the server-generated traceparent in the response so
            // the UI / APM agent can correlate UI transactions with the
            // backend span.
            ctx.response().putHeader(
                "traceparent",
                String.format("00-%s-%s-01", span.traceId(), span.spanId())
            );
            // Clean up MDC when the response completes (success or error).
            ctx.addEndHandler(ignored -> {
                org.slf4j.MDC.remove(com.auto1.pantera.http.log.EcsMdc.TRACE_ID);
                org.slf4j.MDC.remove(com.auto1.pantera.http.log.EcsMdc.SPAN_ID);
                org.slf4j.MDC.remove(com.auto1.pantera.http.log.EcsMdc.PARENT_SPAN_ID);
                org.slf4j.MDC.remove(com.auto1.pantera.http.log.EcsMdc.CLIENT_IP);
                org.slf4j.MDC.remove(com.auto1.pantera.http.log.EcsMdc.USER_NAME);
            });
            ctx.next();
        });
        // CORS headers
        router.route("/api/v1/*").handler(ctx -> {
            ctx.response()
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader(
                    "Access-Control-Allow-Methods",
                    "GET,POST,PUT,PATCH,DELETE,HEAD,OPTIONS"
                )
                .putHeader(
                    "Access-Control-Allow-Headers",
                    // traceparent / b3 / X-B3-* are trace propagation
                    // headers sent by the UI axios interceptor. Without
                    // listing them here the browser CORS preflight
                    // rejects EVERY UI→backend request.
                    "Authorization,Content-Type,Accept,"
                        + "traceparent,tracestate,b3,"
                        + "X-B3-TraceId,X-B3-SpanId,X-B3-ParentSpanId,X-B3-Sampled"
                )
                .putHeader(
                    "Access-Control-Expose-Headers",
                    // Let the browser read the traceparent response
                    // header so APM and debugging tools can see the
                    // server-generated span id.
                    "traceparent"
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
            final String path = ctx.request().path();
            // Skip JWT auth for public endpoints (registered before this filter)
            if (path.contains("/artifact/download-direct")
                || path.endsWith("/auth/token")
                || path.endsWith("/auth/callback")
                || path.endsWith("/auth/providers")
                || path.contains("/auth/providers/")
                || path.endsWith("/health")) {
                ctx.next();
                return;
            }
            if (unifiedAuth == null) {
                // RS256 key pair is required for the API listener since 2.1.0.
                // Fail fast here — the pre-2.1.0 HS256 JWTAuth fallback was
                // removed in 2.1.2 because it silently masked misconfigurations
                // (server would come up but tokens would never verify).
                throw new IllegalStateException(
                    "RS256 key pair is required for the API listener."
                    + " Set meta.jwt.private-key-path and meta.jwt.public-key-path."
                );
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
            // Wire the revocation blocklist + token DAO so that
            // disabling a user via the admin UI also immediately
            // blocks any existing access/refresh/API tokens — not
            // just the session cache, which only covers the current
            // login's L1 entry.
            final com.auto1.pantera.auth.RevocationBlocklist blocklist =
                this.jwtTokens != null ? this.jwtTokens.blocklist() : null;
            final com.auto1.pantera.db.dao.UserTokenDao utDao =
                this.dataSource != null
                    ? new com.auto1.pantera.db.dao.UserTokenDao(this.dataSource) : null;
            new UserHandler(
                users, this.caches, this.security, blocklist, utDao,
                this.settings.cachedLocalEnabledFilter().orElse(null)
            ).register(router);
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
            this.security.policy(),
            // Pass the auth chain so provider toggle/delete/edit can
            // flush the credential cache and take effect immediately.
            this.security.authentication()
                instanceof com.auto1.pantera.asto.misc.Cleanable
                ? (com.auto1.pantera.asto.misc.Cleanable<String>)
                    this.security.authentication()
                : null
        ).register(router);
        new DashboardHandler(crs, this.dataSource).register(router);
        new ArtifactHandler(
            crs, new RepoData(this.configsStorage, this.caches.storagesCache()),
            this.security.policy(), this.dataSource, this.artifactIndex
        ).register(router);
        new CooldownHandler(
            this.cooldown, this.cooldownMetadata,
            CooldownSupport.extractCache(this.cooldown),
            crs, this.settings.cooldown(), this.dataSource,
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
                this.jwtTokens != null ? this.jwtTokens.blocklist() : null,
                this.security.policy()
            ).register(router);
        }
        new com.auto1.pantera.api.v1.admin.NegativeCacheAdminResource(
            this.security.policy()
        ).register(router);
        // Phase 5 / Task 22 — read-only 24h prefetch stats per repo for the
        // Repo edit Performance panel (Task 23). Wired with the prefetch
        // metrics singleton from VertxMain.installPrefetch when the
        // prefetch subsystem is up; nullable in DB-less boot / tests
        // (handler returns zero counts).
        new PrefetchStatsHandler(this.prefetchMetrics).register(router);
        // Start server
        final HttpServer server;
        final String schema;
        // The API listener uses its own PROXYv2 toggle so that operators can
        // run the main port behind an NLB (PROXYv2 on) and the API port behind
        // an ALB (PROXYv2 off, ALB does not emit it). Defaults to the global
        // proxyProtocol() value for backward compatibility.
        final boolean useProxyProtocol = this.settings.apiProxyProtocol();
        if (this.keystore.isPresent() && this.keystore.get().enabled()) {
            final HttpServerOptions sslOptions = this.keystore.get()
                .secureOptions(this.vertx, this.configsStorage);
            sslOptions
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true)
                .setIdleTimeout(60)
                .setUseAlpn(true)
                .setUseProxyProtocol(useProxyProtocol);
            server = this.vertx.createHttpServer(sslOptions);
            schema = "https";
        } else {
            final HttpServerOptions opts = new HttpServerOptions()
                    .setTcpNoDelay(true)
                    .setTcpKeepAlive(true)
                    .setIdleTimeout(60)
                    .setUseAlpn(true)
                    .setHttp2ClearTextEnabled(true)
                    .setUseProxyProtocol(useProxyProtocol);
            server = this.vertx.createHttpServer(opts);
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
