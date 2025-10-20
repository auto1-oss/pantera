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
import com.artipie.cache.StoragesCache;
import com.artipie.cooldown.CooldownSettings;
import com.artipie.cooldown.YamlCooldownSettings;
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
import com.jcabi.log.Logger;
import org.quartz.SchedulerException;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Consumer;

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
     * Path to artipie.yaml config file.
     */
    private final Path configFilePath;

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
        final CachedUsers auth = YamlSettings.initAuth(this.meta());
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
    }

    @Override
    public Storage configStorage() {
        final YamlMapping yaml = meta().yamlMapping("storage");
        if (yaml == null) {
            throw new ArtipieException("Failed to find storage configuration in \n" + this);
        }
        return this.acach.storagesCache().storage(yaml);
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
    public Path configPath() {
        return this.configFilePath;
    }

    @Override
    public String toString() {
        return String.format("YamlSettings{\n%s\n}", this.meta.toString());
    }

    /**
     * Initialise authentication. If `credentials` section is absent or empty,
     * {@link AuthFromEnv} is used.
     * @param settings Yaml settings
     * @return Authentication
     */
    private static CachedUsers initAuth(final YamlMapping settings) {
        Authentication res;
        final YamlSequence creds = settings.yamlSequence(YamlSettings.NODE_CREDENTIALS);
        if (creds == null || creds.isEmpty()) {
            Logger.info(
                ArtipieSecurity.class,
                "Credentials yaml section is absent or empty, using AuthFromEnv()"
            );
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
        return new CachedUsers(res);
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
