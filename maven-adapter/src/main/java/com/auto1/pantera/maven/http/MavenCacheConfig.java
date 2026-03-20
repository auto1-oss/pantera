/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;

/**
 * Maven cache configuration for metadata caching.
 * Negative cache settings are managed globally via NegativeCacheConfig.
 * 
 * <p>Configuration in artipie.yml:
 * <pre>
 * # Global negative cache settings (applies to all adapters)
 * caches:
 *   negative:
 *     ttl: 24h
 *     maxSize: 50000
 *     valkey:
 *       enabled: true
 * 
 * # Metadata cache is per-repository
 * repo:
 *   type: maven-proxy
 *   url: https://repo.maven.apache.org/maven2
 * </pre>
 * 
 * @since 0.11
 */
public final class MavenCacheConfig {
    
    /**
     * Default metadata TTL (24 hours).
     */
    private static final Duration DEFAULT_METADATA_TTL = Duration.ofHours(24);
    
    /**
     * Default metadata max size (10,000 entries).
     */
    private static final int DEFAULT_METADATA_MAX_SIZE = 10_000;
    
    
    /**
     * Metadata cache TTL.
     */
    private final Duration metadataTtl;
    
    /**
     * Metadata cache max size.
     */
    private final int metadataMaxSize;
    
    
    /**
     * Create config with all defaults.
     */
    public MavenCacheConfig() {
        this(DEFAULT_METADATA_TTL, DEFAULT_METADATA_MAX_SIZE);
    }
    
    /**
     * Create config with specific values.
     * @param metadataTtl Metadata TTL
     * @param metadataMaxSize Metadata max size
     */
    public MavenCacheConfig(final Duration metadataTtl, final int metadataMaxSize) {
        this.metadataTtl = metadataTtl;
        this.metadataMaxSize = metadataMaxSize;
    }
    
    /**
     * Parse cache profile from YAML.
     * Note: Negative cache settings are now managed globally via NegativeCacheConfig.
     * @param profile YAML mapping for a specific profile
     * @return Cache config
     */
    @SuppressWarnings("PMD.ProhibitPublicStaticMethods")
    public static MavenCacheConfig fromProfile(final YamlMapping profile) {
        if (profile == null) {
            return new MavenCacheConfig();
        }
        
        // Parse metadata config only - negative cache uses unified NegativeCacheConfig
        final Duration metadataTtl;
        final int metadataMaxSize;
        final YamlMapping metadata = profile.yamlMapping("metadata");
        if (metadata != null) {
            metadataTtl = parseDuration(
                metadata.string("ttl"),
                DEFAULT_METADATA_TTL
            );
            metadataMaxSize = parseInt(
                metadata.string("maxSize"),
                DEFAULT_METADATA_MAX_SIZE
            );
        } else {
            metadataTtl = DEFAULT_METADATA_TTL;
            metadataMaxSize = DEFAULT_METADATA_MAX_SIZE;
        }
        
        return new MavenCacheConfig(metadataTtl, metadataMaxSize);
    }
    
    /**
     * Load cache configuration from server YAML by profile name.
     * Supports both global cache.profiles (preferred) and maven.cacheProfiles (legacy).
     * 
     * @param serverYaml Server-level YAML configuration
     * @param profileName Name of the cache profile to load
     * @return Cache config
     */
    @SuppressWarnings({"PMD.ProhibitPublicStaticMethods", "PMD.SystemPrintln"})
    public static MavenCacheConfig fromServer(
        final YamlMapping serverYaml,
        final String profileName
    ) {
        if (serverYaml == null) {
            System.err.printf(
                "[MavenCacheConfig] No server settings found, using built-in defaults%n"
            );
            return new MavenCacheConfig();
        }
        
        // Get the requested profile name (default to "default" profile)
        final String profile = profileName != null && !profileName.isEmpty() 
            ? profileName 
            : "default";
        
        // Try global cache.profiles first (preferred structure)
        final YamlMapping cache = serverYaml.yamlMapping("cache");
        if (cache != null) {
            final YamlMapping profiles = cache.yamlMapping("profiles");
            if (profiles != null) {
                final YamlMapping profileConfig = profiles.yamlMapping(profile);
                if (profileConfig != null) {
                    System.out.printf(
                        "[MavenCacheConfig] Loaded cache profile '%s' from cache.profiles (global)%n",
                        profile
                    );
                    return fromProfile(profileConfig);
                } else {
                    System.err.printf(
                        "[MavenCacheConfig] Cache profile '%s' not found in cache.profiles%n",
                        profile
                    );
                }
            }
        }
        
        // Fallback to maven.cacheProfiles (legacy, for backward compatibility)
        final YamlMapping maven = serverYaml.yamlMapping("maven");
        if (maven != null) {
            final YamlMapping cacheProfiles = maven.yamlMapping("cacheProfiles");
            if (cacheProfiles != null) {
                final YamlMapping profileConfig = cacheProfiles.yamlMapping(profile);
                if (profileConfig != null) {
                    System.out.printf(
                        "[MavenCacheConfig] Loaded cache profile '%s' from maven.cacheProfiles (legacy)%n",
                        profile
                    );
                    return fromProfile(profileConfig);
                }
            }
        }
        
        // No profile found, use built-in defaults
        System.err.printf(
            "[MavenCacheConfig] Cache profile '%s' not found in cache.profiles or maven.cacheProfiles, using built-in defaults%n",
            profile
        );
        return new MavenCacheConfig();
    }
    
    /**
     * Get cache profile name from repository YAML.
     * @param repoYaml Repository YAML
     * @return Profile name, or "default" if not specified
     */
    @SuppressWarnings("PMD.ProhibitPublicStaticMethods")
    public static String getProfileName(final YamlMapping repoYaml) {
        if (repoYaml == null) {
            return "default";
        }
        
        final String profile = repoYaml.string("cacheProfile");
        return profile != null && !profile.isEmpty() ? profile : "default";
    }
    
    /**
     * Parse duration string with unit suffix.
     * @param value Duration string (e.g., "24h", "30m", "3600")
     * @param defaultValue Default if parsing fails
     * @return Parsed duration
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.UseLocaleWithCaseConversions", "PMD.SystemPrintln"})
    private static Duration parseDuration(final String value, final Duration defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        
        try {
            // Try ISO-8601 duration format first (PT24H)
            return Duration.parse(value);
        } catch (Exception e1) {
            // Try simple format: 24h, 30m, 5s
            try {
                final String lower = value.toLowerCase().trim();
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
                System.err.printf(
                    "[MavenCacheConfig] Failed to parse duration '%s', using default: %s%n",
                    value, defaultValue
                );
                return defaultValue;
            }
        }
    }
    
    /**
     * Parse integer string.
     * @param value Integer string
     * @param defaultValue Default if parsing fails
     * @return Parsed integer
     */
    @SuppressWarnings("PMD.SystemPrintln")
    private static int parseInt(final String value, final int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.printf(
                "[MavenCacheConfig] Failed to parse integer '%s', using default: %d%n",
                value, defaultValue
            );
            return defaultValue;
        }
    }
    
    
    /**
     * Get metadata cache TTL.
     * @return Metadata TTL
     */
    public Duration metadataTtl() {
        return this.metadataTtl;
    }
    
    /**
     * Get metadata cache max size.
     * @return Metadata max size
     */
    public int metadataMaxSize() {
        return this.metadataMaxSize;
    }
    
    @Override
    public String toString() {
        return String.format(
            "MavenCacheConfig{metadata: ttl=%s, max=%d}",
            this.metadataTtl,
            this.metadataMaxSize
        );
    }
}
