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
package com.auto1.pantera.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * General cache configuration for all Pantera caches.
 * Uses named profiles defined globally in _server.yaml.
 * 
 * <p>Example YAML configuration:
 * <pre>
 * # Global settings in _server.yaml
 * caches:
 *   profiles:
 *     # Default profile
 *     default:
 *       maxSize: 10000
 *       ttl: 24h
 *     
 *     # Small short-lived cache
 *     small:
 *       maxSize: 1000
 *       ttl: 5m
 *     
 *     # Large long-lived cache
 *     large:
 *       maxSize: 50000
 *       ttl: 7d
 * 
 *   # Named cache configurations (reference profiles)
 *   storage:
 *     profile: small
 *   auth:
 *     profile: small
 *   policy:
 *     profile: default
 *   maven-metadata:
 *     profile: default
 *   maven-negative:
 *     profile: large
 * </pre>
 * 
 * @since 1.18.22
 */
public final class CacheConfig {
    
    /**
     * Default TTL (24 hours).
     */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    
    /**
     * Default max size (10,000 entries).
     */
    private static final int DEFAULT_MAX_SIZE = 10_000;
    
    /**
     * Default L1 max size for two-tier (10,000 entries - hot data).
     */
    private static final int DEFAULT_L1_MAX_SIZE = 10_000;
    
    /**
     * Default L2 max size for two-tier (1,000,000 entries - warm data).
     */
    private static final int DEFAULT_L2_MAX_SIZE = 1_000_000;
    
    /**
     * Cache TTL.
     */
    private final Duration ttl;
    
    /**
     * Cache max size (single-tier).
     */
    private final int maxSize;
    
    /**
     * Valkey/Redis enabled (two-tier cache).
     */
    private final boolean valkeyEnabled;
    
    /**
     * Valkey/Redis host.
     */
    private final String valkeyHost;
    
    /**
     * Valkey/Redis port.
     */
    private final int valkeyPort;
    
    /**
     * Valkey/Redis timeout.
     */
    private final Duration valkeyTimeout;
    
    /**
     * L1 (in-memory) max size for two-tier.
     */
    private final int l1MaxSize;
    
    /**
     * L2 (Valkey) max size for two-tier.
     */
    private final int l2MaxSize;
    
    /**
     * L1 (in-memory) TTL for two-tier.
     * If null, defaults to 5 minutes for hot data.
     */
    private final Duration l1Ttl;
    
    /**
     * L2 (Valkey) TTL for two-tier.
     * If null, uses main TTL value.
     */
    private final Duration l2Ttl;
    
    /**
     * Create config with defaults.
     */
    public CacheConfig() {
        this(DEFAULT_TTL, DEFAULT_MAX_SIZE);
    }
    
    /**
     * Create config with specific values (single-tier).
     * @param ttl Cache TTL
     * @param maxSize Cache max size
     */
    public CacheConfig(final Duration ttl, final int maxSize) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.valkeyEnabled = false;
        this.valkeyHost = null;
        this.valkeyPort = 6379;
        this.valkeyTimeout = Duration.ofMillis(100);
        this.l1MaxSize = DEFAULT_L1_MAX_SIZE;
        this.l2MaxSize = DEFAULT_L2_MAX_SIZE;
        this.l1Ttl = null;
        this.l2Ttl = null;
    }
    
    /**
     * Create config with Valkey two-tier support.
     * @param ttl Cache TTL (default for both single-tier and L2)
     * @param maxSize Cache max size (ignored if Valkey enabled, use l1/l2 sizes)
     * @param valkeyEnabled Whether Valkey is enabled
     * @param valkeyHost Valkey host
     * @param valkeyPort Valkey port
     * @param valkeyTimeout Valkey timeout
     * @param l1MaxSize L1 (in-memory) max size
     * @param l2MaxSize L2 (Valkey) max size
     */
    public CacheConfig(
        final Duration ttl,
        final int maxSize,
        final boolean valkeyEnabled,
        final String valkeyHost,
        final int valkeyPort,
        final Duration valkeyTimeout,
        final int l1MaxSize,
        final int l2MaxSize
    ) {
        this(ttl, maxSize, valkeyEnabled, valkeyHost, valkeyPort, valkeyTimeout,
             l1MaxSize, l2MaxSize, null, null);
    }
    
    /**
     * Create config with full Valkey two-tier support (including TTLs).
     * @param ttl Cache TTL (default for both single-tier and L2)
     * @param maxSize Cache max size (ignored if Valkey enabled, use l1/l2 sizes)
     * @param valkeyEnabled Whether Valkey is enabled
     * @param valkeyHost Valkey host
     * @param valkeyPort Valkey port
     * @param valkeyTimeout Valkey timeout
     * @param l1MaxSize L1 (in-memory) max size
     * @param l2MaxSize L2 (Valkey) max size
     * @param l1Ttl L1 TTL (null = default 5 min)
     * @param l2Ttl L2 TTL (null = use main ttl)
     */
    public CacheConfig(
        final Duration ttl,
        final int maxSize,
        final boolean valkeyEnabled,
        final String valkeyHost,
        final int valkeyPort,
        final Duration valkeyTimeout,
        final int l1MaxSize,
        final int l2MaxSize,
        final Duration l1Ttl,
        final Duration l2Ttl
    ) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.valkeyEnabled = valkeyEnabled;
        this.valkeyHost = valkeyHost;
        this.valkeyPort = valkeyPort;
        this.valkeyTimeout = valkeyTimeout;
        this.l1MaxSize = l1MaxSize;
        this.l2MaxSize = l2MaxSize;
        this.l1Ttl = l1Ttl;
        this.l2Ttl = l2Ttl;
    }
    
    /**
     * Load cache configuration for a named cache.
     * Looks up: caches.{cacheName}.profile → caches.profiles.{profileName}
     * 
     * @param serverYaml Server settings YAML (_server.yaml)
     * @param cacheName Cache name (e.g., "storage", "auth", "policy")
     * @return Cache configuration
     */
    public static CacheConfig from(final YamlMapping serverYaml, final String cacheName) {
        if (serverYaml == null || cacheName == null || cacheName.isEmpty()) {
            return new CacheConfig();
        }
        
        // Look for caches section
        final YamlMapping caches = serverYaml.yamlMapping("caches");
        if (caches == null) {
            System.out.printf( // NOPMD SystemPrintln - config-load is pre-logger initialization; stdout is the only diagnostic channel
                "[CacheConfig] No 'caches' section in _server.yaml for '%s', using defaults%n",
                cacheName
            );
            return new CacheConfig();
        }
        
        // Check if there's a specific config for this cache
        final YamlMapping cacheMapping = caches.yamlMapping(cacheName);
        if (cacheMapping != null) {
            // Check if it references a profile
            final String profileName = cacheMapping.string("profile");
            if (profileName != null && !profileName.isEmpty()) {
                // Load from profile
                return fromProfile(caches, profileName, cacheName);
            }
            
            // Direct configuration (no profile reference)
            return parseConfig(cacheMapping, cacheName);
        }
        
        // No specific config, try default profile
        return fromProfile(caches, "default", cacheName);
    }
    
    /**
     * Load configuration from a named profile.
     * @param caches Caches YAML section
     * @param profileName Profile name
     * @param cacheName Cache name (for logging)
     * @return Cache configuration
     */
    private static CacheConfig fromProfile(
        final YamlMapping caches,
        final String profileName,
        final String cacheName
    ) {
        final YamlMapping profiles = caches.yamlMapping("profiles");
        if (profiles == null) {
            System.out.printf( // NOPMD SystemPrintln - config-load is pre-logger initialization; stdout is the only diagnostic channel
                "[CacheConfig] No 'caches.profiles' section for '%s', using defaults%n",
                cacheName
            );
            return new CacheConfig();
        }
        
        final YamlMapping profile = profiles.yamlMapping(profileName);
        if (profile == null) {
            System.out.printf( // NOPMD SystemPrintln - config-load is pre-logger initialization; stdout is the only diagnostic channel
                "[CacheConfig] Profile '%s' not found for cache '%s', using defaults%n",
                profileName, cacheName
            );
            return new CacheConfig();
        }
        
        System.out.printf( // NOPMD SystemPrintln - config-load is pre-logger initialization; stdout is the only diagnostic channel
            "[CacheConfig] Loaded cache '%s' with profile '%s'%n",
            cacheName, profileName
        );
        return parseConfig(profile, cacheName);
    }
    
    /**
     * Parse configuration from YAML mapping.
     * @param yaml YAML mapping
     * @param cacheName Cache name (for logging)
     * @return Cache configuration
     */
    private static CacheConfig parseConfig(final YamlMapping yaml, final String cacheName) {
        final Duration ttl = parseDuration(
            yaml.string("ttl"),
            DEFAULT_TTL,
            cacheName
        );
        
        final int maxSize = parseInt(
            yaml.string("maxSize"),
            DEFAULT_MAX_SIZE,
            cacheName
        );
        
        // Check for Valkey configuration
        final YamlMapping valkeyYaml = yaml.yamlMapping("valkey");
        if (valkeyYaml != null) {
            final boolean enabled = "true".equalsIgnoreCase(valkeyYaml.string("enabled"));
            if (enabled) {
                System.out.printf( // NOPMD SystemPrintln - config-load is pre-logger initialization; stdout is the only diagnostic channel
                    "[CacheConfig] Enabling Valkey two-tier cache for '%s'%n",
                    cacheName
                );
                // Parse L1/L2 TTLs (optional)
                final Duration l1Ttl = valkeyYaml.string("l1Ttl") != null
                    ? parseDuration(valkeyYaml.string("l1Ttl"), Duration.ofMinutes(5), cacheName)
                    : null;  // null = use default 5 min
                
                final Duration l2Ttl = valkeyYaml.string("l2Ttl") != null
                    ? parseDuration(valkeyYaml.string("l2Ttl"), ttl, cacheName)
                    : null;  // null = use main ttl
                
                return new CacheConfig(
                    ttl,
                    maxSize,
                    true,  // valkeyEnabled
                    valkeyYaml.string("host") != null ? valkeyYaml.string("host") : "localhost",
                    parseInt(valkeyYaml.string("port"), 6379, cacheName),
                    parseDuration(valkeyYaml.string("timeout"), Duration.ofMillis(100), cacheName),
                    parseInt(valkeyYaml.string("l1MaxSize"), DEFAULT_L1_MAX_SIZE, cacheName),
                    parseInt(valkeyYaml.string("l2MaxSize"), DEFAULT_L2_MAX_SIZE, cacheName),
                    l1Ttl,
                    l2Ttl
                );
            }
        }
        
        return new CacheConfig(ttl, maxSize);
    }
    
    /**
     * Parse duration from string (e.g., "24h", "30m", "PT24H").
     * @param value Duration string
     * @param defaultValue Default if parsing fails
     * @param cacheName Cache name (for logging)
     * @return Parsed duration
     */
    private static Duration parseDuration(
        final String value,
        final Duration defaultValue,
        final String cacheName
    ) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        
        try {
            // Try ISO-8601 duration format first (PT24H)
            return Duration.parse(value);
        } catch (Exception e1) {
            // Try simple format: 24h, 30m, 5s
            try {
                final String lower = value.toLowerCase(Locale.ROOT).trim();
                if (lower.endsWith("h")) {
                    return Duration.ofHours(Long.parseLong(lower.substring(0, lower.length() - 1)));
                } else if (lower.endsWith("m")) {
                    return Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1)));
                } else if (lower.endsWith("s")) {
                    return Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1)));
                } else if (lower.endsWith("d")) {
                    return Duration.ofDays(Long.parseLong(lower.substring(0, lower.length() - 1)));
                }
                // Try parsing as seconds
                return Duration.ofSeconds(Long.parseLong(lower));
            } catch (Exception e2) {
                System.err.printf( // NOPMD SystemPrintln - config-load is pre-logger initialization; stderr is the only diagnostic channel
                    "[CacheConfig] Failed to parse duration '%s' for cache '%s', using default: %s%n",
                    value, cacheName, defaultValue
                );
                return defaultValue;
            }
        }
    }
    
    /**
     * Parse integer from string.
     * @param value Integer string
     * @param defaultValue Default if parsing fails
     * @param cacheName Cache name (for logging)
     * @return Parsed integer
     */
    private static int parseInt(
        final String value,
        final int defaultValue,
        final String cacheName
    ) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.printf( // NOPMD SystemPrintln - config-load is pre-logger initialization; stderr is the only diagnostic channel
                "[CacheConfig] Failed to parse maxSize '%s' for cache '%s', using default: %d%n",
                value, cacheName, defaultValue
            );
            return defaultValue;
        }
    }
    
    /**
     * Get cache TTL.
     * @return Cache TTL
     */
    public Duration ttl() {
        return this.ttl;
    }
    
    /**
     * Get cache max size.
     * @return Cache max size
     */
    public int maxSize() {
        return this.maxSize;
    }
    
    /**
     * Check if Valkey two-tier caching is enabled.
     * @return True if Valkey enabled
     */
    public boolean valkeyEnabled() {
        return this.valkeyEnabled;
    }
    
    /**
     * Get Valkey host.
     * @return Valkey host
     */
    public Optional<String> valkeyHost() {
        return Optional.ofNullable(this.valkeyHost);
    }
    
    /**
     * Get Valkey port.
     * @return Valkey port
     */
    public Optional<Integer> valkeyPort() {
        return this.valkeyEnabled ? Optional.of(this.valkeyPort) : Optional.empty();
    }
    
    /**
     * Get Valkey timeout.
     * @return Valkey timeout
     */
    public Optional<Duration> valkeyTimeout() {
        return this.valkeyEnabled ? Optional.of(this.valkeyTimeout) : Optional.empty();
    }
    
    /**
     * Get L1 (in-memory) max size for two-tier.
     * @return L1 max size
     */
    public int l1MaxSize() {
        return this.l1MaxSize;
    }
    
    /**
     * Get L2 (Valkey) max size for two-tier.
     * @return L2 max size
     */
    public int l2MaxSize() {
        return this.l2MaxSize;
    }
    
    /**
     * Get L1 (in-memory) TTL for two-tier.
     * If not configured, returns default of 5 minutes.
     * @return L1 TTL
     */
    public Duration l1Ttl() {
        return this.l1Ttl != null ? this.l1Ttl : Duration.ofMinutes(5);
    }
    
    /**
     * Get L2 (Valkey) TTL for two-tier.
     * If not configured, returns main TTL.
     * @return L2 TTL
     */
    public Duration l2Ttl() {
        return this.l2Ttl != null ? this.l2Ttl : this.ttl;
    }
    
    @Override
    public String toString() {
        if (this.valkeyEnabled) {
            return String.format(
                "CacheConfig{ttl=%s, valkey=enabled, host=%s:%d, l1=%d/%s, l2=%d/%s}",
                this.ttl,
                this.valkeyHost,
                this.valkeyPort,
                this.l1MaxSize,
                this.l1Ttl != null ? this.l1Ttl : "5m",
                this.l2MaxSize,
                this.l2Ttl != null ? this.l2Ttl : this.ttl
            );
        }
        return String.format(
            "CacheConfig{ttl=%s, maxSize=%d}",
            this.ttl,
            this.maxSize
        );
    }
}
