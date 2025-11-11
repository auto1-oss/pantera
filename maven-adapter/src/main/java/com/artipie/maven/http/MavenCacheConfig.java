/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;

/**
 * Maven cache configuration for metadata and negative caching.
 * Supports global cache profiles (preferred) and Maven-specific profiles (legacy).
 * 
 * <p>Preferred configuration (global profiles, reusable by all proxy types):
 * <pre>
 * # Global settings in _server.yaml
 * cache:
 *   profiles:
 *     # Default profile for most repositories
 *     default:
 *       metadata:
 *         ttl: 24h
 *         maxSize: 10000
 *       negative:
 *         enabled: true
 *         ttl: 24h
 *         maxSize: 50000
 *     
 *     # Profile for stable repositories (Maven Central, npmjs.org, PyPI, etc.)
 *     stable-public:
 *       metadata:
 *         ttl: 7d
 *         maxSize: 15000
 *       negative:
 *         enabled: true
 *         ttl: 1d
 *         maxSize: 75000
 *     
 *     # Profile for volatile repositories (snapshots, pre-releases)
 *     snapshots:
 *       metadata:
 *         ttl: 5m
 *         maxSize: 25000
 *       negative:
 *         enabled: false
 * 
 * # Repository references profile by name
 * repo:
 *   type: maven-proxy
 *   url: https://repo.maven.apache.org/maven2
 *   cacheProfile: stable    # Reference global profile
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
     * Default negative cache TTL (24 hours).
     */
    private static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofHours(24);
    
    /**
     * Default negative cache max size (50,000 entries).
     */
    private static final int DEFAULT_NEGATIVE_MAX_SIZE = 50_000;
    
    /**
     * Default negative cache enabled.
     */
    private static final boolean DEFAULT_NEGATIVE_ENABLED = true;
    
    /**
     * Metadata cache TTL.
     */
    private final Duration metadataTtl;
    
    /**
     * Metadata cache max size.
     */
    private final int metadataMaxSize;
    
    /**
     * Negative cache TTL.
     */
    private final Duration negativeTtl;
    
    /**
     * Negative cache max size.
     */
    private final int negativeMaxSize;
    
    /**
     * Whether negative caching is enabled.
     */
    private final boolean negativeEnabled;
    
    /**
     * Create config with all defaults.
     */
    public MavenCacheConfig() {
        this(
            DEFAULT_METADATA_TTL,
            DEFAULT_METADATA_MAX_SIZE,
            DEFAULT_NEGATIVE_TTL,
            DEFAULT_NEGATIVE_MAX_SIZE,
            DEFAULT_NEGATIVE_ENABLED
        );
    }
    
    /**
     * Create config with specific values.
     * @param metadataTtl Metadata TTL
     * @param metadataMaxSize Metadata max size
     * @param negativeTtl Negative cache TTL
     * @param negativeMaxSize Negative cache max size
     * @param negativeEnabled Whether negative cache is enabled
     */
    public MavenCacheConfig(
        final Duration metadataTtl,
        final int metadataMaxSize,
        final Duration negativeTtl,
        final int negativeMaxSize,
        final boolean negativeEnabled
    ) {
        this.metadataTtl = metadataTtl;
        this.metadataMaxSize = metadataMaxSize;
        this.negativeTtl = negativeTtl;
        this.negativeMaxSize = negativeMaxSize;
        this.negativeEnabled = negativeEnabled;
    }
    
    /**
     * Parse cache profile from YAML.
     * @param profile YAML mapping for a specific profile
     * @return Cache config
     */
    @SuppressWarnings("PMD.ProhibitPublicStaticMethods")
    public static MavenCacheConfig fromProfile(final YamlMapping profile) {
        if (profile == null) {
            return new MavenCacheConfig();
        }
        
        // Parse metadata config
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
        
        // Parse negative cache config
        final Duration negativeTtl;
        final int negativeMaxSize;
        final boolean negativeEnabled;
        final YamlMapping negative = profile.yamlMapping("negative");
        if (negative != null) {
            negativeEnabled = parseBoolean(
                negative.string("enabled"),
                DEFAULT_NEGATIVE_ENABLED
            );
            negativeTtl = parseDuration(
                negative.string("ttl"),
                DEFAULT_NEGATIVE_TTL
            );
            negativeMaxSize = parseInt(
                negative.string("maxSize"),
                DEFAULT_NEGATIVE_MAX_SIZE
            );
        } else {
            negativeEnabled = DEFAULT_NEGATIVE_ENABLED;
            negativeTtl = DEFAULT_NEGATIVE_TTL;
            negativeMaxSize = DEFAULT_NEGATIVE_MAX_SIZE;
        }
        
        return new MavenCacheConfig(
            metadataTtl,
            metadataMaxSize,
            negativeTtl,
            negativeMaxSize,
            negativeEnabled
        );
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
     * Parse boolean string.
     * @param value Boolean string
     * @param defaultValue Default if parsing fails
     * @return Parsed boolean
     */
    @SuppressWarnings({"PMD.UseLocaleWithCaseConversions", "PMD.SystemPrintln"})
    private static boolean parseBoolean(final String value, final boolean defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        
        final String lower = value.trim().toLowerCase();
        if ("true".equals(lower) || "yes".equals(lower) || "1".equals(lower)) {
            return true;
        } else if ("false".equals(lower) || "no".equals(lower) || "0".equals(lower)) {
            return false;
        } else {
            System.err.printf(
                "[MavenCacheConfig] Failed to parse boolean '%s', using default: %b%n",
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
    
    /**
     * Get negative cache TTL.
     * @return Negative TTL
     */
    public Duration negativeTtl() {
        return this.negativeTtl;
    }
    
    /**
     * Get negative cache max size.
     * @return Negative max size
     */
    public int negativeMaxSize() {
        return this.negativeMaxSize;
    }
    
    /**
     * Check if negative caching is enabled.
     * @return True if enabled
     */
    public boolean negativeEnabled() {
        return this.negativeEnabled;
    }
    
    @Override
    public String toString() {
        return String.format(
            "MavenCacheConfig{metadata: ttl=%s, max=%d; negative: enabled=%b, ttl=%s, max=%d}",
            this.metadataTtl,
            this.metadataMaxSize,
            this.negativeEnabled,
            this.negativeTtl,
            this.negativeMaxSize
        );
    }
}
