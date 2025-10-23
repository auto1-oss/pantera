/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.settings;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlSequence;
import com.artipie.api.ssl.KeyStore;
import com.artipie.asto.Storage;
import com.artipie.cooldown.CooldownSettings;
import com.artipie.http.client.HttpClientSettings;
import com.artipie.scheduling.MetadataEventQueues;
import com.artipie.settings.cache.ArtipieCaches;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Application settings.
 * Implements AutoCloseable to ensure proper cleanup of storage resources,
 * particularly important for S3Storage which holds S3AsyncClient connections.
 *
 * @since 0.1
 */
public interface Settings extends AutoCloseable {

    /**
     * Close and cleanup all resources held by this settings instance.
     * This includes closing any storage instances that implement AutoCloseable.
     * Default implementation does nothing - implementations should override.
     */
    @Override
    default void close() {
        // Default: no-op. Implementations should override to cleanup resources.
    }

    /**
     * Provides a configuration storage.
     *
     * @return Storage instance.
     */
    Storage configStorage();

    /**
     * Artipie authorization.
     * @return Authentication and policy
     */
    ArtipieSecurity authz();

    /**
     * Artipie meta configuration.
     * @return Yaml mapping
     */
    YamlMapping meta();

    /**
     * Repo configs storage, or, in file system storage terms, subdirectory where repo
     * configs are located relatively to the storage.
     * @return Repo configs storage
     */
    Storage repoConfigsStorage();

    /**
     * Key store.
     * @return KeyStore
     */
    Optional<KeyStore> keyStore();

    /**
     * Metrics setting.
     * @return Metrics configuration
     */
    MetricsContext metrics();

    /**
     * Artipie caches.
     * @return The caches
     */
    ArtipieCaches caches();

    /**
     * Artifact metadata events queue.
     * @return Artifact events queue
     */
    Optional<MetadataEventQueues> artifactMetadata();

    /**
     * Crontab settings.
     * @return Yaml sequence of crontab strings.
     */
    Optional<YamlSequence> crontab();

    /**
     * Logging configuration.
     * @return Logging context
     * @deprecated Logging is now configured via log4j2.xml. This method is no longer used.
     */
    @Deprecated
    default LoggingContext logging() {
        return null;
    }

    default HttpClientSettings httpClientSettings() {
        return new HttpClientSettings();
    }

    /**
     * Cooldown configuration for proxy repositories.
     * @return Cooldown settings
     */
    CooldownSettings cooldown();

    /**
     * Artifacts database data source, if configured.
     * @return Optional data source
     */
    Optional<DataSource> artifactsDatabase();

    /**
     * Global URL prefixes configuration.
     * @return Prefixes configuration
     */
    PrefixesConfig prefixes();

    /**
     * Path to the artipie.yaml configuration file.
     * @return Path to config file
     */
    java.nio.file.Path configPath();
}
