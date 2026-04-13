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
import com.amihaiemil.eoyaml.YamlSequence;
import com.auto1.pantera.api.ssl.KeyStore;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.cooldown.CooldownSettings;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.settings.cache.PanteraCaches;
import java.util.Optional;
import javax.sql.DataSource;
import java.time.Duration;

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
     * Pantera authorization.
     * @return Authentication and policy
     */
    PanteraSecurity authz();

    /**
     * Pantera meta configuration.
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
     * Pantera caches.
     * @return The caches
     */
    PanteraCaches caches();

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
     * Maximum duration allowed for processing a single HTTP request on Vert.x server side.
     * @return Request timeout duration; a zero duration disables the timeout.
     */
    default Duration httpServerRequestTimeout() {
        return Duration.ofMinutes(2);
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
     * Path to the pantera.yaml configuration file.
     * @return Path to config file
     */
    java.nio.file.Path configPath();

    /**
     * JWT token settings.
     * @return JWT settings
     */
    default JwtSettings jwtSettings() {
        return new JwtSettings();
    }

    /**
     * Artifact search index.
     * @return Artifact index (NOP if indexing is disabled)
     */
    default ArtifactIndex artifactIndex() {
        return ArtifactIndex.NOP;
    }

    /**
     * Optional Valkey connection for cache operations.
     * @return Valkey connection if configured
     */
    default Optional<ValkeyConnection> valkeyConnection() {
        return Optional.empty();
    }

    /**
     * Whether Proxy Protocol v2 is enabled for AWS NLB integration.
     * When true, the HTTP server will parse the PROXY protocol header
     * prepended by the NLB to obtain real client IPs.
     * @return True if proxy protocol should be enabled on the HTTP server
     */
    default boolean proxyProtocol() {
        return false;
    }
}
