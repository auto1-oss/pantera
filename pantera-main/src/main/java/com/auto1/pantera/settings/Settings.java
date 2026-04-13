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
     * Whether Proxy Protocol v2 is enabled for the main + per-repo HTTP listeners.
     * When true, the HTTP server will parse the PROXYv2 header prepended by an
     * upstream load balancer (typically AWS NLB with
     * {@code proxy_protocol_v2.enabled} on the target group) to obtain real
     * client IPs.
     *
     * <p>Application Load Balancers (ALB) do <em>not</em> emit PROXYv2 — they
     * forward an L7 HTTP request with {@code X-Forwarded-For}. Enabling this
     * flag for an ALB-fronted listener will break every connection (including
     * health checks) because the ALB's plain {@code GET /} bytes will be
     * misparsed as a malformed PROXY header. See {@link #apiProxyProtocol()}
     * for the API-port-specific override.
     *
     * @return True if proxy protocol should be enabled on the main and per-repo
     *     HTTP listeners
     */
    default boolean proxyProtocol() {
        return false;
    }

    /**
     * Whether Proxy Protocol v2 is enabled specifically for the API listener
     * (typically port 8086). Allows operators with mixed topologies (NLB →
     * main port + ALB → API port) to enable PROXYv2 on the NLB-fronted
     * listeners without breaking ALB health checks on the API port.
     *
     * <p>Default: returns {@link #proxyProtocol()} for backward compatibility
     * — pre-2.1.2 deployments that set a single {@code proxy_protocol: true}
     * keep their existing behaviour. Set
     * {@code meta.http_server.api_proxy_protocol: false} explicitly to disable
     * PROXYv2 only for the API listener.
     *
     * @return True if proxy protocol should be enabled on the API listener
     * @since 2.1.2
     */
    default boolean apiProxyProtocol() {
        return this.proxyProtocol();
    }
}
