/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import com.artipie.ArtipieException;
import com.artipie.api.ssl.KeyStore;
import com.artipie.api.ssl.KeyStoreFactory;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.SubStorage;
import com.artipie.asto.factory.Config;
import com.artipie.asto.factory.StoragesLoader;
import com.artipie.auth.AuthFromEnv;
import com.artipie.cache.GlobalCacheConfig;
import com.artipie.cache.StoragesCache;
import com.artipie.cache.ValkeyConnection;
import com.artipie.cooldown.CooldownSettings;
import com.artipie.cooldown.YamlCooldownSettings;
import com.artipie.cooldown.metadata.FilteredMetadataCacheConfig;
import com.artipie.db.ArtifactDbFactory;
import com.artipie.db.DbConsumer;
import com.artipie.http.auth.AuthLoader;
import com.artipie.http.auth.Authentication;
import com.artipie.http.client.HttpClientSettings;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.MetadataEventQueues;
import com.artipie.scheduling.QuartzService;
import com.artipie.settings.cache.ArtipieCaches;
import com.artipie.settings.cache.CachedUsers;
import com.artipie.settings.cache.GuavaFiltersCache;
import com.artipie.http.log.EcsLogger;
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
import com.artipie.asto.factory.StorageFactory;
import com.artipie.asto.factory.StoragesLoader;

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
     * Artipie policy and creds type name.
     */
    private static final String ARTIPIE = "artipie";

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
     * A set of caches for artipie settings.
     */
    private final ArtipieCaches acach;

    /**
     * Metrics context.
     */
    private final MetricsContext mctx;

    /**
     * Authentication and policy.
     */
    private final ArtipieSecurity security;

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
     * Path to artipie.yaml config file.
     */
    private final Path configFilePath;

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
    @SuppressWarnings("PMD.ConstructorOnlyInitializesOrCallOtherConstructors")
    public YamlSettings(final YamlMapping content, final Path path, final QuartzService quartz) {
        // Config file can be artipie.yaml or artipie.yml
        this.configFilePath = YamlSettings.findConfigFile(path);
        this.meta = content.yamlMapping("meta");
        if (this.meta == null) {
            throw new IllegalStateException("Invalid settings: not empty `meta` section is expected");
        }
        this.httpClientSettings = HttpClientSettings.from(this.meta.yamlMapping("http_client"));
        // Parse JWT settings first - needed for auth cache TTL capping
        this.jwtSettings = JwtSettings.fromYaml(this.meta());
        final Optional<ValkeyConnection> valkey = YamlSettings.initValkey(this.meta());
        // Initialize global cache config for all adapters
        GlobalCacheConfig.initialize(valkey);
        // Initialize unified negative cache config
        com.artipie.cache.NegativeCacheConfig.initialize(this.meta().yamlMapping("caches"));
        // Initialize cooldown metadata cache config
        FilteredMetadataCacheConfig.initialize(this.meta().yamlMapping("caches"));
        final CachedUsers auth = YamlSettings.initAuth(this.meta(), valkey, this.jwtSettings);
        this.security = new ArtipieSecurity.FromYaml(
            this.meta(), auth, new PolicyStorage(this.meta()).parse()
        );
        this.acach = new ArtipieCaches.All(
            auth, new StoragesCache(), this.security.policy(), new GuavaFiltersCache()
        );
        this.mctx = new MetricsContext(this.meta());
        this.lctx = new LoggingContext(this.meta());
        this.cooldown = YamlCooldownSettings.fromMeta(this.meta());
        this.artifactsDb = YamlSettings.initArtifactsDb(this.meta());
        this.events = this.artifactsDb.flatMap(
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
                        throw new ArtipieException("Failed to find storage configuration in \n" + this);
                    }
                    this.configStorageInstance = this.acach.storagesCache().storage(yaml);
                    this.trackedStorages.add(this.configStorageInstance);
                }
            }
        }
        return this.configStorageInstance;
    }

    @Override
    public ArtipieSecurity authz() {
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
    public ArtipieCaches caches() {
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
    public PrefixesConfig prefixes() {
        return this.prefixesConfig;
    }

    @Override
    public JwtSettings jwtSettings() {
        return this.jwtSettings;
    }

    @Override
    public void close() {
        EcsLogger.info("com.artipie.settings")
            .message("Closing YamlSettings and cleaning up storage resources")
            .eventCategory("configuration")
            .eventAction("settings_close")
            .log();
        for (final Storage storage : this.trackedStorages) {
            try {
                // Try to close via factory first (preferred method)
                final String storageType = detectStorageType(storage);
                if (storageType != null) {
                    final StorageFactory factory = StoragesLoader.STORAGES.getFactory(storageType);
                    factory.closeStorage(storage);
                    EcsLogger.info("com.artipie.settings")
                        .message("Closed storage via factory (type: " + storageType + ")")
                        .eventCategory("configuration")
                        .eventAction("storage_close")
                        .eventOutcome("success")
                        .log();
                } else if (storage instanceof AutoCloseable) {
                    // Fallback: direct close for AutoCloseable storages
                    ((AutoCloseable) storage).close();
                    EcsLogger.info("com.artipie.settings")
                        .message("Closed storage directly (type: " + storage.getClass().getSimpleName() + ")")
                        .eventCategory("configuration")
                        .eventAction("storage_close")
                        .eventOutcome("success")
                        .log();
                }
            } catch (final Exception e) {
                EcsLogger.error("com.artipie.settings")
                    .message("Failed to close storage")
                    .eventCategory("configuration")
                    .eventAction("storage_close")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        this.trackedStorages.clear();
        EcsLogger.info("com.artipie.settings")
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
        final String className = storage.getClass().getSimpleName().toLowerCase();
        if (className.contains("s3")) {
            return "s3";
        } else if (className.contains("file")) {
            return "fs";
        } else if (className.contains("etcd")) {
            return "etcd";
        } else if (className.contains("redis")) {
            return "redis";
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
            EcsLogger.debug("com.artipie.settings")
                .message("No caches configuration found")
                .eventCategory("configuration")
                .eventAction("valkey_init")
                .log();
            return Optional.empty();
        }
        final YamlMapping valkeyConfig = caches.yamlMapping("valkey");
        if (valkeyConfig == null) {
            EcsLogger.debug("com.artipie.settings")
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
            EcsLogger.info("com.artipie.settings")
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

        EcsLogger.info("com.artipie.settings")
            .message("Initializing Valkey connection (timeout: " + timeout.toMillis() + "ms)")
            .eventCategory("configuration")
            .eventAction("valkey_init")
            .field("destination.address", host)
            .field("destination.port", port)
            .log();
        try {
            return Optional.of(new ValkeyConnection(host, port, timeout));
        } catch (final Exception ex) {
            EcsLogger.error("com.artipie.settings")
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
     * Initialise authentication. If `credentials` section is absent or empty,
     * {@link AuthFromEnv} is used.
     * @param settings Yaml settings
     * @param valkey Optional Valkey connection for L2 cache
     * @param jwtSettings JWT settings for cache TTL capping
     * @return Authentication
     */
    private static CachedUsers initAuth(
        final YamlMapping settings,
        final Optional<ValkeyConnection> valkey,
        final JwtSettings jwtSettings
    ) {
        Authentication res;
        final YamlSequence creds = settings.yamlSequence(YamlSettings.NODE_CREDENTIALS);
        if (creds == null || creds.isEmpty()) {
            EcsLogger.info("com.artipie.security")
                .message("Credentials yaml section is absent or empty, using AuthFromEnv()")
                .eventCategory("authentication")
                .eventAction("auth_init")
                .field("event.provider", "env")
                .log();
            res = new AuthFromEnv();
        } else {
            final AuthLoader loader = new AuthLoader();
            final List<Authentication> auths = creds.values().stream().map(
                node -> node.asMapping().string(YamlSettings.NODE_TYPE)
            ).map(type -> loader.newObject(type, settings)).toList();
            res = auths.get(0);
            for (final Authentication auth : auths.subList(1, auths.size())) {
                res = new Authentication.Joined(res, auth);
            }
        }
        // Create CachedUsers with Valkey connection and JWT settings for TTL capping
        if (valkey.isPresent()) {
            EcsLogger.info("com.artipie.settings")
                .message(String.format("Initializing auth cache with Valkey L2 cache and JWT TTL cap (jwt_expires=%s, jwt_expiry_seconds=%d)", jwtSettings.expires(), jwtSettings.expirySeconds()))
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
     * @param settings Artipie settings
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
            throw new ArtipieException(error);
        }
    }

    /**
     * Initialize artifacts database.
     * @param settings Artipie settings
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
     * @param meta Meta section of artipie.yml
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
         * credentials sections for `artipie` credentials type.
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
     * Find the actual config file (artipie.yaml or artipie.yml).
     * @param dir Directory containing the config file
     * @return Path to the config file
     */
    private static Path findConfigFile(final Path dir) {
        final Path yaml = dir.resolve("artipie.yaml");
        if (Files.exists(yaml)) {
            return yaml;
        }
        final Path yml = dir.resolve("artipie.yml");
        if (Files.exists(yml)) {
            return yml;
        }
        // Default to .yaml if neither exists (will fail later with better error)
        return yaml;
    }

}
