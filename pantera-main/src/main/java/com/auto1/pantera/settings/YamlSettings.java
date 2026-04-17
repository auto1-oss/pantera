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
package com.auto1.pantera.settings;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.PanteraException;
import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.api.ssl.KeyStoreFactory;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.asto.factory.Config;
import com.auto1.pantera.asto.factory.StoragesLoader;
import com.auto1.pantera.auth.AuthFromDb;
import com.auto1.pantera.auth.AuthFromEnv;
import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.cache.PublishingCleanable;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.YamlCooldownSettings;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheConfig;
import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.DbConsumer;
import com.auto1.pantera.http.auth.AuthLoader;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.scheduling.QuartzService;
import com.auto1.pantera.security.policy.CachedYamlPolicy;
import com.auto1.pantera.settings.cache.PanteraCaches;
import com.auto1.pantera.settings.cache.CachedUsers;
import com.auto1.pantera.settings.cache.GuavaFiltersCache;
import com.auto1.pantera.settings.cache.PublishingFiltersCache;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.DbArtifactIndex;
import org.quartz.SchedulerException;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import com.auto1.pantera.asto.factory.StorageFactory;
import com.auto1.pantera.asto.factory.StoragesLoader;

/**
 * Settings built from YAML.
 *
 * @since 0.1
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class YamlSettings implements Settings {

    /**
     * Yaml node credentials.
     */
    public static final String NODE_CREDENTIALS = "credentials";

    /**
     * YAML node name `type` for credentials type.
     */
    public static final String NODE_TYPE = "type";

    /**
     * Yaml node policy.
     */
    private static final String NODE_POLICY = "policy";

    /**
     * Yaml node storage.
     */
    private static final String NODE_STORAGE = "storage";

    /**
     * Pantera policy and creds type name.
     */
    private static final String ARTIPIE = "local";

    /**
     * YAML node name for `ssl` yaml section.
     */
    private static final String NODE_SSL = "ssl";

    /**
     * YAML file content.
     */
    private final YamlMapping meta;

    /**
     * Settings for
     */
    private final HttpClientSettings httpClientSettings;

    /**
     * A set of caches for pantera settings.
     */
    private final PanteraCaches acach;

    /**
     * Metrics context.
     */
    private final MetricsContext mctx;

    /**
     * Authentication and policy.
     */
    private final PanteraSecurity security;

    /**
     * Artifacts event queue.
     */
    private final Optional<MetadataEventQueues> events;

    /**
     * Logging context.
     */
    private final LoggingContext lctx;

    /**
     * Cooldown settings.
     */
    private final CooldownSettings cooldown;

    /**
     * Artifacts database data source if configured.
     */
    private final Optional<DataSource> artifactsDb;

    /**
     * Global URL prefixes configuration.
     */
    private final PrefixesConfig prefixesConfig;

    /**
     * HTTP server request timeout.
     */
    private final Duration httpServerRequestTimeout;

    /**
     * JWT token settings.
     */
    private final JwtSettings jwtSettings;

    /**
     * Artifact index (PostgreSQL-backed).
     */
    private final ArtifactIndex artifactIndex;

    /**
     * Path to pantera.yaml config file.
     */
    private final Path configFilePath;

    /**
     * Redis pub/sub for cross-instance cache invalidation, or null if Valkey is not configured.
     * @since 1.20.13
     */
    private final CacheInvalidationPubSub cachePubSub;

    /**
     * Valkey connection for proper cleanup on shutdown, or null if Valkey is not configured.
     * @since 1.20.13
     */
    private final ValkeyConnection valkeyConn;

    /**
     * Guard flag to make {@link #close()} idempotent without spurious error logs.
     * @since 1.20.13
     */
    private volatile boolean closed;

    /**
     * Tracked storages for proper cleanup.
     * Thread-safe list to track all storage instances created by this settings.
     */
    private final List<Storage> trackedStorages = new CopyOnWriteArrayList<>();

    /**
     * Config storage instance (cached).
     */
    private volatile Storage configStorageInstance;

    /**
     * Ctor.
     * @param content YAML file content.
     * @param path Path to the folder with yaml settings file
     * @param quartz Quartz service
     */
    public YamlSettings(final YamlMapping content, final Path path, final QuartzService quartz) {
        this(content, path, quartz, Optional.empty());
    }

    /**
     * Ctor with optional pre-created DataSource.
     * <p>
     * When a shared DataSource is provided, it is reused instead of creating a
     * new connection pool. This allows VertxMain to share one HikariCP pool
     * between Quartz JDBC clustering and artifact operations.
     *
     * @param content YAML file content.
     * @param path Path to the folder with yaml settings file
     * @param quartz Quartz service
     * @param shared Pre-created DataSource to reuse, or empty to create a new one
     * @since 1.20.13
     */
    @SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
    public YamlSettings(final YamlMapping content, final Path path,
        final QuartzService quartz, final Optional<DataSource> shared) {
        this(content, path, quartz, shared, Optional.empty());
    }

    /**
     * Ctor with separate write pool for DbConsumer.
     * @param content Yaml settings content
     * @param path Path to settings file
     * @param quartz Quartz service
     * @param shared Pre-created API DataSource (auth, admin, Quartz)
     * @param writeDs Dedicated write pool for DbConsumer (empty = use shared)
     */
    public YamlSettings(final YamlMapping content, final Path path,
        final QuartzService quartz, final Optional<DataSource> shared,
        final Optional<DataSource> writeDs) {
        // Config file can be pantera.yaml or pantera.yml
        this.configFilePath = YamlSettings.findConfigFile(path);
        this.meta = content.yamlMapping("meta");
        if (this.meta == null) {
            throw new IllegalStateException("Invalid settings: not empty `meta` section is expected");
        }
        this.httpClientSettings = HttpClientSettings.from(this.meta.yamlMapping("http_client"));
        // Parse JWT settings first - needed for auth cache TTL capping
        this.jwtSettings = JwtSettings.fromYaml(this.meta());
        final Optional<ValkeyConnection> valkey = YamlSettings.initValkey(this.meta());
        this.valkeyConn = valkey.orElse(null);
        // Initialize global cache config for all adapters
        GlobalCacheConfig.initialize(valkey);
        // Initialize unified negative cache config
        NegativeCacheConfig.initialize(this.meta().yamlMapping("caches"));
        // Initialize cooldown metadata cache config
        FilteredMetadataCacheConfig.initialize(this.meta().yamlMapping("caches"));
        // Initialize database early so AuthFromDb can be used in auth chain
        if (shared.isPresent()) {
            this.artifactsDb = shared;
        } else {
            this.artifactsDb = YamlSettings.initArtifactsDb(this.meta());
        }
        final CachedUsers auth = YamlSettings.initAuth(
            this.meta(), valkey, this.jwtSettings, this.artifactsDb.orElse(null)
        );
        this.security = new PanteraSecurity.FromYaml(
            this.meta(), auth, new PolicyStorage(this.meta()).parse(),
            this.artifactsDb.orElse(null)
        );
        // Initialize cross-instance cache invalidation via Redis pub/sub
        if (valkey.isPresent()) {
            final CacheInvalidationPubSub ps =
                new CacheInvalidationPubSub(valkey.get());
            this.cachePubSub = ps;
            ps.register("auth", auth);
            final GuavaFiltersCache filters = new GuavaFiltersCache();
            ps.register("filters", filters);
            final Cleanable<String> policyCache;
            if (this.security.policy() instanceof Cleanable) {
                policyCache = (Cleanable<String>) this.security.policy();
                ps.register("policy", policyCache);
            }
            this.acach = new PanteraCaches.All(
                new PublishingCleanable(auth, ps, "auth"),
                new StoragesCache(),
                this.security.policy(),
                new PublishingFiltersCache(filters, ps)
            );
        } else {
            this.cachePubSub = null;
            this.acach = new PanteraCaches.All(
                auth, new StoragesCache(), this.security.policy(), new GuavaFiltersCache()
            );
        }
        this.mctx = new MetricsContext(this.meta());
        this.lctx = new LoggingContext(this.meta());
        this.cooldown = YamlCooldownSettings.fromMeta(this.meta());
        // Initialize artifact index
        final YamlMapping indexConfig = this.meta.yamlMapping("artifact_index");
        final boolean indexEnabled = indexConfig != null
            && "true".equals(indexConfig.string("enabled"));
        if (indexEnabled && this.artifactsDb.isPresent()) {
            this.artifactIndex = new DbArtifactIndex(this.artifactsDb.get());
        } else if (indexEnabled) {
            throw new IllegalStateException(
                "artifact_index.enabled=true requires artifacts_database to be configured"
            );
        } else if (this.artifactsDb.isPresent()) {
            // Auto-enable DB-backed index when database is configured
            this.artifactIndex = new DbArtifactIndex(this.artifactsDb.get());
        } else {
            this.artifactIndex = ArtifactIndex.NOP;
        }
        // Use the dedicated write pool for DbConsumer when provided;
        // fall back to the shared pool so single-pool deployments work unchanged.
        final Optional<DataSource> eventsDs = writeDs.isPresent() ? writeDs : this.artifactsDb;
        this.events = eventsDs.flatMap(
            db -> YamlSettings.initArtifactsEvents(this.meta(), quartz, db)
        );
        this.prefixesConfig = new PrefixesConfig(YamlSettings.readPrefixes(this.meta()));
        this.httpServerRequestTimeout = YamlSettings.parseRequestTimeout(this.meta());
    }

    @Override
    public Storage configStorage() {
        if (this.configStorageInstance == null) {
            synchronized (this) {
                if (this.configStorageInstance == null) {
                    final YamlMapping yaml = meta().yamlMapping("storage");
                    if (yaml == null) {
                        throw new PanteraException("Failed to find storage configuration in \n" + this);
                    }
                    this.configStorageInstance = this.acach.storagesCache().storage(yaml);
                    this.trackedStorages.add(this.configStorageInstance);
                }
            }
        }
        return this.configStorageInstance;
    }

    @Override
    public PanteraSecurity authz() {
        return this.security;
    }

    @Override
    public YamlMapping meta() {
        return this.meta;
    }

    @Override
    public Storage repoConfigsStorage() {
        return Optional.ofNullable(this.meta().string("repo_configs"))
            .<Storage>map(str -> new SubStorage(new Key.From(str), this.configStorage()))
            .orElse(this.configStorage());
    }

    @Override
    public Optional<KeyStore> keyStore() {
        return Optional.ofNullable(this.meta().yamlMapping(YamlSettings.NODE_SSL))
            .map(KeyStoreFactory::newInstance);
    }

    @Override
    public MetricsContext metrics() {
        return this.mctx;
    }

    @Override
    public PanteraCaches caches() {
        return this.acach;
    }

    @Override
    public Optional<MetadataEventQueues> artifactMetadata() {
        return this.events;
    }

    @Override
    public Optional<YamlSequence> crontab() {
        return Optional.ofNullable(this.meta().yamlSequence("crontab"));
    }

    @Override
    public LoggingContext logging() {
        return this.lctx;
    }

    @Override
    public HttpClientSettings httpClientSettings() {
        return this.httpClientSettings;
    }

    @Override
    public Duration httpServerRequestTimeout() {
        return this.httpServerRequestTimeout;
    }

    @Override
    public CooldownSettings cooldown() {
        return this.cooldown;
    }

    @Override
    public Optional<DataSource> artifactsDatabase() {
        return this.artifactsDb;
    }

    @Override
    public Optional<ValkeyConnection> valkeyConnection() {
        return Optional.ofNullable(this.valkeyConn);
    }

    @Override
    public PrefixesConfig prefixes() {
        return this.prefixesConfig;
    }

    @Override
    public JwtSettings jwtSettings() {
        return this.jwtSettings;
    }

    @Override
    public ArtifactIndex artifactIndex() {
        return this.artifactIndex;
    }

    @Override
    public boolean proxyProtocol() {
        final YamlMapping server = this.meta != null
            ? this.meta.yamlMapping("http_server") : null;
        if (server == null) {
            return false;
        }
        return "true".equalsIgnoreCase(server.string("proxy_protocol"));
    }

    @Override
    public boolean apiProxyProtocol() {
        final YamlMapping server = this.meta != null
            ? this.meta.yamlMapping("http_server") : null;
        if (server == null) {
            return false;
        }
        final String explicit = server.string("api_proxy_protocol");
        if (explicit == null) {
            // Backward-compatible default — pre-2.1.2 single-flag behaviour.
            return this.proxyProtocol();
        }
        return "true".equalsIgnoreCase(explicit);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        EcsLogger.info("com.auto1.pantera.settings")
            .message("Closing YamlSettings and cleaning up storage resources")
            .eventCategory("configuration")
            .eventAction("settings_close")
            .log();
        // Close ordering is critical — dependencies flow downward:
        // 1. Tracked storages (may use DataSource / Valkey indirectly)
        // 2. Artifact index (uses DataSource via its executor)
        // 3. Cache pub/sub (uses ValkeyConnection's pub/sub connections)
        // 4. HikariDataSource (safe after index executor drained)
        // 5. ValkeyConnection (safe after pub/sub closed)
        // 6. Clear tracked storages list
        // Note: VertxMain.stop() closes HTTP servers before calling this,
        // so in-flight requests should have drained by the time we get here.
        for (final Storage storage : this.trackedStorages) {
            try {
                // Try to close via factory first (preferred method)
                final String storageType = detectStorageType(storage);
                if (storageType != null) {
                    final StorageFactory factory = StoragesLoader.STORAGES.getFactory(storageType);
                    factory.closeStorage(storage);
                    EcsLogger.info("com.auto1.pantera.settings")
                        .message("Closed storage via factory (type: " + storageType + ")")
                        .eventCategory("configuration")
                        .eventAction("storage_close")
                        .eventOutcome("success")
                        .log();
                } else if (storage instanceof AutoCloseable) {
                    // Fallback: direct close for AutoCloseable storages
                    ((AutoCloseable) storage).close();
                    EcsLogger.info("com.auto1.pantera.settings")
                        .message("Closed storage directly (type: " + storage.getClass().getSimpleName() + ")")
                        .eventCategory("configuration")
                        .eventAction("storage_close")
                        .eventOutcome("success")
                        .log();
                }
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera.settings")
                    .message("Failed to close storage")
                    .eventCategory("configuration")
                    .eventAction("storage_close")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // Close artifact index
        if (this.artifactIndex != null && this.artifactIndex != ArtifactIndex.NOP) {
            try {
                this.artifactIndex.close();
                EcsLogger.info("com.auto1.pantera.settings")
                    .message("Closed artifact index")
                    .eventCategory("configuration")
                    .eventAction("index_close")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera.settings")
                    .message("Failed to close artifact index")
                    .eventCategory("configuration")
                    .eventAction("index_close")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // Close cache invalidation pub/sub
        if (this.cachePubSub != null) {
            try {
                this.cachePubSub.close();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera.settings")
                    .message("Failed to close cache invalidation pub/sub")
                    .eventCategory("configuration")
                    .eventAction("pubsub_close")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // Close artifacts database connection pool
        if (this.artifactsDb.isPresent()) {
            final javax.sql.DataSource ds = this.artifactsDb.get();
            if (ds instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) ds).close();
                    EcsLogger.info("com.auto1.pantera.settings")
                        .message("Closed artifacts database connection pool")
                        .eventCategory("configuration")
                        .eventAction("database_close")
                        .eventOutcome("success")
                        .log();
                } catch (final Exception e) {
                    EcsLogger.error("com.auto1.pantera.settings")
                        .message("Failed to close artifacts database connection pool")
                        .eventCategory("configuration")
                        .eventAction("database_close")
                        .eventOutcome("failure")
                        .error(e)
                        .log();
                }
            }
        }
        // Close Valkey connection pool
        if (this.valkeyConn != null) {
            try {
                this.valkeyConn.close();
                EcsLogger.info("com.auto1.pantera.settings")
                    .message("Closed Valkey connection")
                    .eventCategory("configuration")
                    .eventAction("valkey_close")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera.settings")
                    .message("Failed to close Valkey connection")
                    .eventCategory("configuration")
                    .eventAction("valkey_close")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        this.trackedStorages.clear();
        EcsLogger.info("com.auto1.pantera.settings")
            .message("YamlSettings cleanup complete")
            .eventCategory("configuration")
            .eventAction("settings_close")
            .eventOutcome("success")
            .log();
    }

    /**
     * Detect storage type from storage instance.
     * @param storage Storage instance
     * @return Storage type string (e.g., "s3", "fs") or null if unknown
     */
    private String detectStorageType(final Storage storage) {
        Storage target = storage;
        if (target instanceof com.auto1.pantera.http.misc.DispatchedStorage) {
            target = ((com.auto1.pantera.http.misc.DispatchedStorage) target).unwrap();
        }
        final String className = target.getClass().getSimpleName().toLowerCase();
        if (className.contains("s3")) {
            return "s3";
        } else if (className.contains("file")) {
            return "fs";
        }
        return null;
    }

    @Override
    public Path configPath() {
        return this.configFilePath;
    }

    private static Duration parseRequestTimeout(final YamlMapping meta) {
        final YamlMapping server = meta.yamlMapping("http_server");
        final Duration fallback = Duration.ofMinutes(2);
        if (server == null) {
            return fallback;
        }
        final String value = server.string("request_timeout");
        if (value == null) {
            return fallback;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            final Duration parsed = Duration.parse(trimmed);
            if (parsed.isNegative()) {
                throw new IllegalStateException("`http_server.request_timeout` must be zero or positive duration");
            }
            return parsed;
        } catch (final DateTimeParseException ex) {
            try {
                final long millis = Long.parseLong(trimmed);
                if (millis < 0) {
                    throw new IllegalStateException("`http_server.request_timeout` must be zero or positive");
                }
                return Duration.ofMillis(millis);
            } catch (final NumberFormatException num) {
                throw new IllegalStateException(
                    String.format(
                        "Invalid `http_server.request_timeout` value '%s'. Provide ISO-8601 duration (e.g. PT30S) or milliseconds.",
                        trimmed
                    ),
                    ex
                );
            }
        }
    }

    @Override
    public String toString() {
        return String.format("YamlSettings{\n%s\n}", this.meta.toString());
    }

    /**
     * Initialize Valkey connection from configuration.
     * @param settings Yaml settings
     * @return Optional Valkey connection
     */
    private static Optional<ValkeyConnection> initValkey(final YamlMapping settings) {
        final YamlMapping caches = settings.yamlMapping("caches");
        if (caches == null) {
            EcsLogger.debug("com.auto1.pantera.settings")
                .message("No caches configuration found")
                .eventCategory("configuration")
                .eventAction("valkey_init")
                .log();
            return Optional.empty();
        }
        final YamlMapping valkeyConfig = caches.yamlMapping("valkey");
        if (valkeyConfig == null) {
            EcsLogger.debug("com.auto1.pantera.settings")
                .message("No valkey configuration found in caches")
                .eventCategory("configuration")
                .eventAction("valkey_init")
                .log();
            return Optional.empty();
        }
        final boolean enabled = Optional.ofNullable(valkeyConfig.string("enabled"))
            .map(Boolean::parseBoolean)
            .orElse(false);
        if (!enabled) {
            EcsLogger.info("com.auto1.pantera.settings")
                .message("Valkey is disabled in configuration (enabled: false)")
                .eventCategory("configuration")
                .eventAction("valkey_init")
                .log();
            return Optional.empty();
        }
        final String host = Optional.ofNullable(valkeyConfig.string("host"))
            .orElse("localhost");
        final int port = Optional.ofNullable(valkeyConfig.string("port"))
            .map(Integer::parseInt)
            .orElse(6379);
        final Duration timeout = Optional.ofNullable(valkeyConfig.string("timeout"))
            .map(str -> {
                // Parse simple duration formats like "100ms" or ISO-8601 "PT0.1S"
                if (str.endsWith("ms")) {
                    return Duration.ofMillis(Long.parseLong(str.substring(0, str.length() - 2)));
                } else if (str.endsWith("s")) {
                    return Duration.ofSeconds(Long.parseLong(str.substring(0, str.length() - 1)));
                } else {
                    return Duration.parse(str);
                }
            })
            .orElse(Duration.ofMillis(100));

        EcsLogger.info("com.auto1.pantera.settings")
            .message("Initializing Valkey connection (timeout: " + timeout.toMillis() + "ms)")
            .eventCategory("configuration")
            .eventAction("valkey_init")
            .field("destination.address", host)
            .field("destination.port", port)
            .log();
        try {
            return Optional.of(new ValkeyConnection(host, port, timeout));
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.settings")
                .message("Failed to initialize Valkey connection")
                .eventCategory("configuration")
                .eventAction("valkey_init")
                .eventOutcome("failure")
                .error(ex)
                .log();
            return Optional.empty();
        }
    }

    /**
     * Initialise authentication. When a database is available, {@link AuthFromDb}
     * is used as the primary authenticator. File-based and other providers from
     * the YAML credentials section are added as fallbacks.
     * @param settings Yaml settings
     * @param valkey Optional Valkey connection for L2 cache
     * @param jwtSettings JWT settings for cache TTL capping
     * @param dataSource Database data source (nullable)
     * @return Authentication
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private static CachedUsers initAuth(
        final YamlMapping settings,
        final Optional<ValkeyConnection> valkey,
        final JwtSettings jwtSettings,
        final DataSource dataSource
    ) {
        Authentication res;
        if (dataSource != null) {
            // Database is the primary source of truth for user credentials
            res = new AuthFromDb(dataSource);
            EcsLogger.info("com.auto1.pantera.security")
                .message("Using AuthFromDb as primary authenticator")
                .eventCategory("authentication")
                .eventAction("auth_init")
                .field("event.provider", "db")
                .log();
        } else {
            res = new AuthFromEnv();
        }
        // Shared cache of currently-enabled auth_providers.type values.
        // Every dynamic provider (SSO, jwt-password, ...) is wrapped in
        // DbGatedAuth with a reference to this cache so UI-driven
        // enable/disable/delete takes effect within seconds (5s TTL)
        // without requiring a server restart.
        final com.auto1.pantera.auth.DbGatedAuth.EnabledTypesCache enabledCache =
            dataSource != null
                ? new com.auto1.pantera.auth.DbGatedAuth.EnabledTypesCache(dataSource)
                : null;
        // Add YAML-configured providers as fallbacks (SSO, env, etc.)
        final YamlSequence creds = settings.yamlSequence(YamlSettings.NODE_CREDENTIALS);
        if (creds != null && !creds.isEmpty()) {
            final AuthLoader loader = new AuthLoader();
            for (final YamlNode node : creds.values()) {
                final String type = node.asMapping().string(YamlSettings.NODE_TYPE);
                // Skip "local" file-based auth when DB is primary
                if (dataSource != null && "local".equals(type)) {
                    continue;
                }
                try {
                    final Authentication auth = loader.newObject(type, settings);
                    // Wrap in DbGatedAuth so disable/delete via the UI
                    // actually takes effect on the running chain.
                    final Authentication gated = enabledCache != null && type != null
                        ? new com.auto1.pantera.auth.DbGatedAuth(
                            auth, type, enabledCache)
                        : auth;
                    res = new Authentication.Joined(res, gated);
                } catch (final Exception ex) {
                    EcsLogger.warn("com.auto1.pantera.security")
                        .message("Failed to load auth provider: " + type)
                        .eventCategory("authentication")
                        .eventAction("auth_init")
                        .eventOutcome("failure")
                        .error(ex)
                        .log();
                }
            }
        }
        // Final enabled-state gate on the whole chain. Rejects any
        // successful authentication whose local user row is disabled.
        // This closes the hole where a user disabled in Pantera is
        // still authenticated via basic auth (CLI pulls) using their
        // upstream Keycloak / Okta credentials — AuthFromDb already
        // checks enabled for local users, but SSO providers do not.
        // Order matters: wrap BEFORE CachedUsers so a stale cache
        // entry cannot let a just-disabled user through.
        if (dataSource != null) {
            res = new com.auto1.pantera.auth.LocalEnabledFilter(res, dataSource);
        }
        // Create CachedUsers with Valkey connection and JWT settings for TTL capping
        if (valkey.isPresent()) {
            EcsLogger.info("com.auto1.pantera.settings")
                .message(String.format("Initializing auth cache with Valkey L2 cache and JWT TTL cap: expires=%s, expirySeconds=%d", jwtSettings.expires(), jwtSettings.expirySeconds()))
                .eventCategory("authentication")
                .eventAction("auth_cache_init")
                .log();
            return new CachedUsers(res, valkey.get(), jwtSettings);
        } else {
            return new CachedUsers(res, null, jwtSettings);
        }
    }

    /**
     * Initialize and scheduled mechanism to gather artifact events
     * (adding and removing artifacts) and create {@link MetadataEventQueues} instance.
     * @param settings Pantera settings
     * @param quartz Quartz service
     * @param database Artifact database
     * @return Event queue to gather artifacts events
     */
    private static Optional<MetadataEventQueues> initArtifactsEvents(
        final YamlMapping settings, final QuartzService quartz, final DataSource database
    ) {
        final YamlMapping prop = settings.yamlMapping("artifacts_database");
        if (prop == null) {
            return Optional.empty();
        }
        final int threads = readPositive(prop.integer("threads_count"), 1);
        final int interval = readPositive(prop.integer("interval_seconds"), 1);
        
        // Read configurable buffer settings
        final int bufferTimeSeconds = prop.string("buffer_time_seconds") != null
            ? Integer.parseInt(prop.string("buffer_time_seconds"))
            : 2;
        final int bufferSize = prop.string("buffer_size") != null
            ? Integer.parseInt(prop.string("buffer_size"))
            : 50;
        
        final List<Consumer<ArtifactEvent>> consumers = new ArrayList<>(threads);
        for (int idx = 0; idx < threads; idx = idx + 1) {
            consumers.add(new DbConsumer(database, bufferTimeSeconds, bufferSize));
        }
        try {
            final Queue<ArtifactEvent> res = quartz.addPeriodicEventsProcessor(interval, consumers);
            return Optional.of(new MetadataEventQueues(res, quartz));
        } catch (final SchedulerException error) {
            throw new PanteraException(error);
        }
    }

    /**
     * Initialize artifacts database.
     * @param settings Pantera settings
     * @return Data source if configuration is present
     */
    private static Optional<DataSource> initArtifactsDb(final YamlMapping settings) {
        if (settings.yamlMapping("artifacts_database") == null) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDbFactory(settings, "artifacts").initialize());
    }

    private static int readPositive(final Integer value, final int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    /**
     * Read global_prefixes from meta section.
     * @param meta Meta section of pantera.yml
     * @return List of prefixes
     */
    private static List<String> readPrefixes(final YamlMapping meta) {
        final YamlSequence seq = meta.yamlSequence("global_prefixes");
        if (seq == null || seq.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>(seq.size());
        seq.values().forEach(node -> {
            final String value = node.asScalar().value();
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        });
        return result;
    }

    /**
     * Policy (auth and permissions) storage from config yaml.
     * @since 0.13
     */
    public static class PolicyStorage {

        /**
         * Yaml mapping config.
         */
        private final YamlMapping cfg;

        /**
         * Ctor.
         * @param cfg Settings config
         */
        public PolicyStorage(final YamlMapping cfg) {
            this.cfg = cfg;
        }

        /**
         * Read policy storage from config yaml. Normally policy storage should be configured
         * in `policy` yaml section, but, if policy is absent, storage should be specified in
         * credentials sections for `pantera` credentials type.
         * @return Storage if present
         */
        public Optional<Storage> parse() {
            Optional<Storage> res = Optional.empty();
            final YamlSequence credentials = this.cfg.yamlSequence(YamlSettings.NODE_CREDENTIALS);
            final YamlMapping policy = this.cfg.yamlMapping(YamlSettings.NODE_POLICY);
            if (credentials != null && !credentials.isEmpty()) {
                final Optional<YamlMapping> asto = credentials
                    .values().stream().map(YamlNode::asMapping)
                    .filter(
                        node -> YamlSettings.ARTIPIE.equals(node.string(YamlSettings.NODE_TYPE))
                    ).findFirst().map(node -> node.yamlMapping(YamlSettings.NODE_STORAGE));
                if (asto.isPresent()) {
                    res = Optional.of(
                        StoragesLoader.STORAGES.newObject(
                            asto.get().string(YamlSettings.NODE_TYPE),
                            new Config.YamlStorageConfig(asto.get())
                        )
                    );
                } else if (policy != null
                    && YamlSettings.ARTIPIE.equals(policy.string(YamlSettings.NODE_TYPE))
                    && policy.yamlMapping(YamlSettings.NODE_STORAGE) != null) {
                    res = Optional.of(
                        StoragesLoader.STORAGES.newObject(
                            policy.yamlMapping(YamlSettings.NODE_STORAGE)
                                .string(YamlSettings.NODE_TYPE),
                            new Config.YamlStorageConfig(
                                policy.yamlMapping(YamlSettings.NODE_STORAGE)
                            )
                        )
                    );
                }
            }
            return res;
        }
    }

    /**
     * Find the actual config file (pantera.yaml or pantera.yml).
     * @param dir Directory containing the config file
     * @return Path to the config file
     */
    private static Path findConfigFile(final Path dir) {
        final Path yaml = dir.resolve("pantera.yaml");
        if (Files.exists(yaml)) {
            return yaml;
        }
        final Path yml = dir.resolve("pantera.yml");
        if (Files.exists(yml)) {
            return yml;
        }
        // Default to .yaml if neither exists (will fail later with better error)
        return yaml;
    }

}
