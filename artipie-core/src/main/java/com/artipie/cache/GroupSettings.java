/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration settings for group repository index and metadata caching.
 * Supports both global (artipie.yaml) and repo-level configuration.
 * Repo-level settings override global settings.
 * Non-blocking for Vert.x event loop compatibility.
 *
 * <p>Example YAML configuration:
 * <pre>
 * # In artipie.yaml (global) or repo config
 * group:
 *   index:
 *     remote_exists_ttl: 15m     # How long to cache that remote has package
 *     remote_not_exists_ttl: 5m  # How long to cache negative lookup
 *     local_event_driven: true   # Use events for local invalidation
 *   metadata:
 *     ttl: 5m                    # Merged metadata cache TTL
 *     stale_serve: 1h            # Serve stale while refreshing
 *     background_refresh_at: 0.8 # Refresh at 80% of TTL
 *   resolution:
 *     upstream_timeout: 5s       # Timeout for upstream requests
 *     max_parallel: 10           # Max parallel upstream requests
 *   cache_sizing:
 *     l1_max_entries: 10000      # L1 (in-memory) cache size
 *     l2_max_entries: 1000000    # L2 (Valkey) cache size
 * </pre>
 *
 * @since 1.18.0
 */
public final class GroupSettings {

    /**
     * Index settings.
     */
    private final IndexSettings index;

    /**
     * Metadata cache settings.
     */
    private final MetadataSettings metadata;

    /**
     * Resolution settings.
     */
    private final ResolutionSettings resolution;

    /**
     * Cache sizing settings.
     */
    private final CacheSizing sizing;

    /**
     * Private constructor.
     * @param index Index settings
     * @param metadata Metadata settings
     * @param resolution Resolution settings
     * @param sizing Cache sizing settings
     */
    private GroupSettings(
        final IndexSettings index,
        final MetadataSettings metadata,
        final ResolutionSettings resolution,
        final CacheSizing sizing
    ) {
        this.index = Objects.requireNonNull(index);
        this.metadata = Objects.requireNonNull(metadata);
        this.resolution = Objects.requireNonNull(resolution);
        this.sizing = Objects.requireNonNull(sizing);
    }

    /**
     * Create default settings.
     * @return Default GroupSettings
     */
    public static GroupSettings defaults() {
        return new GroupSettings(
            IndexSettings.defaults(),
            MetadataSettings.defaults(),
            ResolutionSettings.defaults(),
            CacheSizing.defaults()
        );
    }

    /**
     * Parse settings from YAML.
     * @param yaml YAML mapping (can be null)
     * @return Parsed GroupSettings
     */
    public static GroupSettings from(final YamlMapping yaml) {
        if (yaml == null) {
            return defaults();
        }
        return new GroupSettings(
            IndexSettings.from(yaml.yamlMapping("index")),
            MetadataSettings.from(yaml.yamlMapping("metadata")),
            ResolutionSettings.from(yaml.yamlMapping("resolution")),
            CacheSizing.from(yaml.yamlMapping("cache_sizing"))
        );
    }

    /**
     * Merge with repo-level overrides.
     * Repo-level settings override this (global) settings.
     * @param repoLevel Repo-level YAML (can be null)
     * @return Merged GroupSettings
     */
    public GroupSettings merge(final YamlMapping repoLevel) {
        if (repoLevel == null) {
            return this;
        }
        return new GroupSettings(
            this.index.merge(repoLevel.yamlMapping("index")),
            this.metadata.merge(repoLevel.yamlMapping("metadata")),
            this.resolution.merge(repoLevel.yamlMapping("resolution")),
            this.sizing.merge(repoLevel.yamlMapping("cache_sizing"))
        );
    }

    /**
     * Get index settings.
     * @return Index settings
     */
    public IndexSettings indexSettings() {
        return this.index;
    }

    /**
     * Get metadata cache settings.
     * @return Metadata settings
     */
    public MetadataSettings metadataSettings() {
        return this.metadata;
    }

    /**
     * Get resolution settings.
     * @return Resolution settings
     */
    public ResolutionSettings resolutionSettings() {
        return this.resolution;
    }

    /**
     * Get cache sizing settings.
     * @return Cache sizing settings
     */
    public CacheSizing cacheSizing() {
        return this.sizing;
    }

    @Override
    public String toString() {
        return String.format(
            "GroupSettings{index=%s, metadata=%s, resolution=%s, sizing=%s}",
            this.index, this.metadata, this.resolution, this.sizing
        );
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final GroupSettings other = (GroupSettings) obj;
        return Objects.equals(this.index, other.index)
            && Objects.equals(this.metadata, other.metadata)
            && Objects.equals(this.resolution, other.resolution)
            && Objects.equals(this.sizing, other.sizing);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.index, this.metadata, this.resolution, this.sizing);
    }

    /**
     * Index settings for package location caching.
     */
    public static final class IndexSettings {

        /**
         * Default TTL for positive remote lookups (15 minutes).
         */
        private static final Duration DEFAULT_REMOTE_EXISTS_TTL = Duration.ofMinutes(15);

        /**
         * Default TTL for negative remote lookups (5 minutes).
         */
        private static final Duration DEFAULT_REMOTE_NOT_EXISTS_TTL = Duration.ofMinutes(5);

        /**
         * TTL for caching that remote has package.
         */
        private final Duration remoteExistsTtl;

        /**
         * TTL for caching negative lookups.
         */
        private final Duration remoteNotExistsTtl;

        /**
         * Whether to use event-driven invalidation for local repos.
         */
        private final boolean localEventDriven;

        /**
         * Constructor.
         * @param remoteExistsTtl Remote exists TTL
         * @param remoteNotExistsTtl Remote not exists TTL
         * @param localEventDriven Local event driven flag
         */
        private IndexSettings(
            final Duration remoteExistsTtl,
            final Duration remoteNotExistsTtl,
            final boolean localEventDriven
        ) {
            this.remoteExistsTtl = remoteExistsTtl;
            this.remoteNotExistsTtl = remoteNotExistsTtl;
            this.localEventDriven = localEventDriven;
        }

        /**
         * Create default index settings.
         * @return Default IndexSettings
         */
        static IndexSettings defaults() {
            return new IndexSettings(
                DEFAULT_REMOTE_EXISTS_TTL,
                DEFAULT_REMOTE_NOT_EXISTS_TTL,
                true
            );
        }

        /**
         * Parse from YAML.
         * @param yaml YAML mapping (can be null)
         * @return Parsed IndexSettings
         */
        static IndexSettings from(final YamlMapping yaml) {
            if (yaml == null) {
                return defaults();
            }
            return new IndexSettings(
                parseDuration(
                    yaml.string("remote_exists_ttl"),
                    DEFAULT_REMOTE_EXISTS_TTL,
                    "index.remote_exists_ttl"
                ),
                parseDuration(
                    yaml.string("remote_not_exists_ttl"),
                    DEFAULT_REMOTE_NOT_EXISTS_TTL,
                    "index.remote_not_exists_ttl"
                ),
                parseBoolean(yaml.string("local_event_driven"), true)
            );
        }

        /**
         * Merge with overrides.
         * @param yaml Override YAML (can be null)
         * @return Merged IndexSettings
         */
        IndexSettings merge(final YamlMapping yaml) {
            if (yaml == null) {
                return this;
            }
            return new IndexSettings(
                yaml.string("remote_exists_ttl") != null
                    ? parseDuration(
                        yaml.string("remote_exists_ttl"),
                        this.remoteExistsTtl,
                        "index.remote_exists_ttl"
                    )
                    : this.remoteExistsTtl,
                yaml.string("remote_not_exists_ttl") != null
                    ? parseDuration(
                        yaml.string("remote_not_exists_ttl"),
                        this.remoteNotExistsTtl,
                        "index.remote_not_exists_ttl"
                    )
                    : this.remoteNotExistsTtl,
                yaml.string("local_event_driven") != null
                    ? parseBoolean(yaml.string("local_event_driven"), this.localEventDriven)
                    : this.localEventDriven
            );
        }

        /**
         * Get TTL for positive remote lookups.
         * @return Remote exists TTL
         */
        public Duration remoteExistsTtl() {
            return this.remoteExistsTtl;
        }

        /**
         * Get TTL for negative remote lookups.
         * @return Remote not exists TTL
         */
        public Duration remoteNotExistsTtl() {
            return this.remoteNotExistsTtl;
        }

        /**
         * Check if event-driven invalidation is enabled for local repos.
         * @return True if event-driven
         */
        public boolean localEventDriven() {
            return this.localEventDriven;
        }

        @Override
        public String toString() {
            return String.format(
                "IndexSettings{remoteExistsTtl=%s, remoteNotExistsTtl=%s, localEventDriven=%s}",
                this.remoteExistsTtl, this.remoteNotExistsTtl, this.localEventDriven
            );
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final IndexSettings other = (IndexSettings) obj;
            return Objects.equals(this.remoteExistsTtl, other.remoteExistsTtl)
                && Objects.equals(this.remoteNotExistsTtl, other.remoteNotExistsTtl)
                && this.localEventDriven == other.localEventDriven;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.remoteExistsTtl, this.remoteNotExistsTtl, this.localEventDriven);
        }
    }

    /**
     * Metadata cache settings.
     */
    public static final class MetadataSettings {

        /**
         * Default metadata TTL (5 minutes).
         */
        private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

        /**
         * Default stale serve duration (1 hour).
         */
        private static final Duration DEFAULT_STALE_SERVE = Duration.ofHours(1);

        /**
         * Default background refresh threshold (80% of TTL).
         */
        private static final double DEFAULT_BACKGROUND_REFRESH_AT = 0.8;

        /**
         * Metadata cache TTL.
         */
        private final Duration ttl;

        /**
         * Duration to serve stale while refreshing.
         */
        private final Duration staleServe;

        /**
         * Percentage of TTL at which to trigger background refresh.
         */
        private final double backgroundRefreshAt;

        /**
         * Constructor.
         * @param ttl Cache TTL
         * @param staleServe Stale serve duration
         * @param backgroundRefreshAt Background refresh threshold
         */
        private MetadataSettings(
            final Duration ttl,
            final Duration staleServe,
            final double backgroundRefreshAt
        ) {
            this.ttl = ttl;
            this.staleServe = staleServe;
            this.backgroundRefreshAt = backgroundRefreshAt;
        }

        /**
         * Create default metadata settings.
         * @return Default MetadataSettings
         */
        static MetadataSettings defaults() {
            return new MetadataSettings(
                DEFAULT_TTL,
                DEFAULT_STALE_SERVE,
                DEFAULT_BACKGROUND_REFRESH_AT
            );
        }

        /**
         * Parse from YAML.
         * @param yaml YAML mapping (can be null)
         * @return Parsed MetadataSettings
         */
        static MetadataSettings from(final YamlMapping yaml) {
            if (yaml == null) {
                return defaults();
            }
            return new MetadataSettings(
                parseDuration(yaml.string("ttl"), DEFAULT_TTL, "metadata.ttl"),
                parseDuration(yaml.string("stale_serve"), DEFAULT_STALE_SERVE, "metadata.stale_serve"),
                parseDouble(
                    yaml.string("background_refresh_at"),
                    DEFAULT_BACKGROUND_REFRESH_AT,
                    "metadata.background_refresh_at",
                    0.0,
                    1.0
                )
            );
        }

        /**
         * Merge with overrides.
         * @param yaml Override YAML (can be null)
         * @return Merged MetadataSettings
         */
        MetadataSettings merge(final YamlMapping yaml) {
            if (yaml == null) {
                return this;
            }
            return new MetadataSettings(
                yaml.string("ttl") != null
                    ? parseDuration(yaml.string("ttl"), this.ttl, "metadata.ttl")
                    : this.ttl,
                yaml.string("stale_serve") != null
                    ? parseDuration(yaml.string("stale_serve"), this.staleServe, "metadata.stale_serve")
                    : this.staleServe,
                yaml.string("background_refresh_at") != null
                    ? parseDouble(
                        yaml.string("background_refresh_at"),
                        this.backgroundRefreshAt,
                        "metadata.background_refresh_at",
                        0.0,
                        1.0
                    )
                    : this.backgroundRefreshAt
            );
        }

        /**
         * Get metadata cache TTL.
         * @return Cache TTL
         */
        public Duration ttl() {
            return this.ttl;
        }

        /**
         * Get stale serve duration.
         * @return Stale serve duration
         */
        public Duration staleServe() {
            return this.staleServe;
        }

        /**
         * Get background refresh threshold (0.0-1.0).
         * @return Background refresh at percentage
         */
        public double backgroundRefreshAt() {
            return this.backgroundRefreshAt;
        }

        @Override
        public String toString() {
            return String.format(
                "MetadataSettings{ttl=%s, staleServe=%s, backgroundRefreshAt=%.2f}",
                this.ttl, this.staleServe, this.backgroundRefreshAt
            );
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final MetadataSettings other = (MetadataSettings) obj;
            return Objects.equals(this.ttl, other.ttl)
                && Objects.equals(this.staleServe, other.staleServe)
                && Double.compare(this.backgroundRefreshAt, other.backgroundRefreshAt) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.ttl, this.staleServe, this.backgroundRefreshAt);
        }
    }

    /**
     * Resolution settings for upstream requests.
     */
    public static final class ResolutionSettings {

        /**
         * Default upstream timeout (5 seconds).
         */
        private static final Duration DEFAULT_UPSTREAM_TIMEOUT = Duration.ofSeconds(5);

        /**
         * Default max parallel requests.
         */
        private static final int DEFAULT_MAX_PARALLEL = 10;

        /**
         * Timeout for upstream requests.
         */
        private final Duration upstreamTimeout;

        /**
         * Max parallel upstream requests.
         */
        private final int maxParallel;

        /**
         * Constructor.
         * @param upstreamTimeout Upstream timeout
         * @param maxParallel Max parallel requests
         */
        private ResolutionSettings(
            final Duration upstreamTimeout,
            final int maxParallel
        ) {
            this.upstreamTimeout = upstreamTimeout;
            this.maxParallel = maxParallel;
        }

        /**
         * Create default resolution settings.
         * @return Default ResolutionSettings
         */
        static ResolutionSettings defaults() {
            return new ResolutionSettings(
                DEFAULT_UPSTREAM_TIMEOUT,
                DEFAULT_MAX_PARALLEL
            );
        }

        /**
         * Parse from YAML.
         * @param yaml YAML mapping (can be null)
         * @return Parsed ResolutionSettings
         */
        static ResolutionSettings from(final YamlMapping yaml) {
            if (yaml == null) {
                return defaults();
            }
            return new ResolutionSettings(
                parseDuration(
                    yaml.string("upstream_timeout"),
                    DEFAULT_UPSTREAM_TIMEOUT,
                    "resolution.upstream_timeout"
                ),
                parseInt(
                    yaml.string("max_parallel"),
                    DEFAULT_MAX_PARALLEL,
                    "resolution.max_parallel",
                    true
                )
            );
        }

        /**
         * Merge with overrides.
         * @param yaml Override YAML (can be null)
         * @return Merged ResolutionSettings
         */
        ResolutionSettings merge(final YamlMapping yaml) {
            if (yaml == null) {
                return this;
            }
            return new ResolutionSettings(
                yaml.string("upstream_timeout") != null
                    ? parseDuration(
                        yaml.string("upstream_timeout"),
                        this.upstreamTimeout,
                        "resolution.upstream_timeout"
                    )
                    : this.upstreamTimeout,
                yaml.string("max_parallel") != null
                    ? parseInt(
                        yaml.string("max_parallel"),
                        this.maxParallel,
                        "resolution.max_parallel",
                        true
                    )
                    : this.maxParallel
            );
        }

        /**
         * Get upstream timeout.
         * @return Upstream timeout
         */
        public Duration upstreamTimeout() {
            return this.upstreamTimeout;
        }

        /**
         * Get max parallel requests.
         * @return Max parallel
         */
        public int maxParallel() {
            return this.maxParallel;
        }

        @Override
        public String toString() {
            return String.format(
                "ResolutionSettings{upstreamTimeout=%s, maxParallel=%d}",
                this.upstreamTimeout, this.maxParallel
            );
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final ResolutionSettings other = (ResolutionSettings) obj;
            return Objects.equals(this.upstreamTimeout, other.upstreamTimeout)
                && this.maxParallel == other.maxParallel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.upstreamTimeout, this.maxParallel);
        }
    }

    /**
     * Cache sizing settings.
     */
    public static final class CacheSizing {

        /**
         * Default L1 max entries (10,000).
         */
        private static final int DEFAULT_L1_MAX_ENTRIES = 10_000;

        /**
         * Default L2 max entries (1,000,000).
         */
        private static final int DEFAULT_L2_MAX_ENTRIES = 1_000_000;

        /**
         * L1 (in-memory) max entries.
         */
        private final int l1MaxEntries;

        /**
         * L2 (Valkey) max entries.
         */
        private final int l2MaxEntries;

        /**
         * Constructor.
         * @param l1MaxEntries L1 max entries
         * @param l2MaxEntries L2 max entries
         */
        private CacheSizing(final int l1MaxEntries, final int l2MaxEntries) {
            this.l1MaxEntries = l1MaxEntries;
            this.l2MaxEntries = l2MaxEntries;
        }

        /**
         * Create default cache sizing.
         * @return Default CacheSizing
         */
        static CacheSizing defaults() {
            return new CacheSizing(DEFAULT_L1_MAX_ENTRIES, DEFAULT_L2_MAX_ENTRIES);
        }

        /**
         * Parse from YAML.
         * @param yaml YAML mapping (can be null)
         * @return Parsed CacheSizing
         */
        static CacheSizing from(final YamlMapping yaml) {
            if (yaml == null) {
                return defaults();
            }
            return new CacheSizing(
                parseInt(
                    yaml.string("l1_max_entries"),
                    DEFAULT_L1_MAX_ENTRIES,
                    "cache_sizing.l1_max_entries",
                    true
                ),
                parseInt(
                    yaml.string("l2_max_entries"),
                    DEFAULT_L2_MAX_ENTRIES,
                    "cache_sizing.l2_max_entries",
                    true
                )
            );
        }

        /**
         * Merge with overrides.
         * @param yaml Override YAML (can be null)
         * @return Merged CacheSizing
         */
        CacheSizing merge(final YamlMapping yaml) {
            if (yaml == null) {
                return this;
            }
            return new CacheSizing(
                yaml.string("l1_max_entries") != null
                    ? parseInt(
                        yaml.string("l1_max_entries"),
                        this.l1MaxEntries,
                        "cache_sizing.l1_max_entries",
                        true
                    )
                    : this.l1MaxEntries,
                yaml.string("l2_max_entries") != null
                    ? parseInt(
                        yaml.string("l2_max_entries"),
                        this.l2MaxEntries,
                        "cache_sizing.l2_max_entries",
                        true
                    )
                    : this.l2MaxEntries
            );
        }

        /**
         * Get L1 (in-memory) max entries.
         * @return L1 max entries
         */
        public int l1MaxEntries() {
            return this.l1MaxEntries;
        }

        /**
         * Get L2 (Valkey) max entries.
         * @return L2 max entries
         */
        public int l2MaxEntries() {
            return this.l2MaxEntries;
        }

        @Override
        public String toString() {
            return String.format(
                "CacheSizing{l1MaxEntries=%d, l2MaxEntries=%d}",
                this.l1MaxEntries, this.l2MaxEntries
            );
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final CacheSizing other = (CacheSizing) obj;
            return this.l1MaxEntries == other.l1MaxEntries
                && this.l2MaxEntries == other.l2MaxEntries;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.l1MaxEntries, this.l2MaxEntries);
        }
    }

    /**
     * Parse duration from string (e.g., "24h", "30m", "5s").
     * @param value Duration string
     * @param defaultValue Default if parsing fails
     * @param fieldName Field name for logging
     * @return Parsed duration
     */
    private static Duration parseDuration(
        final String value,
        final Duration defaultValue,
        final String fieldName
    ) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            // Try ISO-8601 duration format first (PT24H)
            final Duration parsed = Duration.parse(value);
            return validateDuration(parsed, defaultValue, fieldName, value);
        } catch (Exception e1) {
            // Try simple format: 24h, 30m, 5s, 7d
            try {
                final String lower = value.toLowerCase().trim();
                Duration parsed;
                if (lower.endsWith("h")) {
                    parsed = Duration.ofHours(
                        Long.parseLong(lower.substring(0, lower.length() - 1))
                    );
                } else if (lower.endsWith("m")) {
                    parsed = Duration.ofMinutes(
                        Long.parseLong(lower.substring(0, lower.length() - 1))
                    );
                } else if (lower.endsWith("s")) {
                    parsed = Duration.ofSeconds(
                        Long.parseLong(lower.substring(0, lower.length() - 1))
                    );
                } else if (lower.endsWith("d")) {
                    parsed = Duration.ofDays(
                        Long.parseLong(lower.substring(0, lower.length() - 1))
                    );
                } else {
                    // Try parsing as seconds
                    parsed = Duration.ofSeconds(Long.parseLong(lower));
                }
                return validateDuration(parsed, defaultValue, fieldName, value);
            } catch (Exception e2) {
                System.err.printf(
                    "[GroupSettings] Failed to parse duration '%s' for '%s', using default: %s%n",
                    value, fieldName, defaultValue
                );
                return defaultValue;
            }
        }
    }

    /**
     * Validate that duration is not negative.
     * @param parsed Parsed duration
     * @param defaultValue Default value to use if invalid
     * @param fieldName Field name for logging
     * @param originalValue Original string value for logging
     * @return Valid duration or default
     */
    private static Duration validateDuration(
        final Duration parsed,
        final Duration defaultValue,
        final String fieldName,
        final String originalValue
    ) {
        if (parsed.isNegative()) {
            System.err.printf(
                "[GroupSettings] Duration '%s' for '%s' is negative, using default: %s%n",
                originalValue, fieldName, defaultValue
            );
            return defaultValue;
        }
        return parsed;
    }

    /**
     * Parse duration from string (overload without field name for backward compatibility).
     * @param value Duration string
     * @param defaultValue Default if parsing fails
     * @return Parsed duration
     */
    private static Duration parseDuration(final String value, final Duration defaultValue) {
        return parseDuration(value, defaultValue, "unknown");
    }

    /**
     * Parse integer from string with validation.
     * @param value Integer string
     * @param defaultValue Default if parsing fails
     * @param fieldName Field name for logging
     * @param requirePositive Whether value must be positive
     * @return Parsed integer
     */
    private static int parseInt(
        final String value,
        final int defaultValue,
        final String fieldName,
        final boolean requirePositive
    ) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            final int parsed = Integer.parseInt(value.trim());
            if (requirePositive && parsed <= 0) {
                System.err.printf(
                    "[GroupSettings] Value '%s' for '%s' must be positive, using default: %d%n",
                    value, fieldName, defaultValue
                );
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            System.err.printf(
                "[GroupSettings] Failed to parse integer '%s' for '%s', using default: %d%n",
                value, fieldName, defaultValue
            );
            return defaultValue;
        }
    }

    /**
     * Parse integer from string (overload without field name for backward compatibility).
     * @param value Integer string
     * @param defaultValue Default if parsing fails
     * @return Parsed integer
     */
    private static int parseInt(final String value, final int defaultValue) {
        return parseInt(value, defaultValue, "unknown", false);
    }

    /**
     * Parse double from string with optional range validation.
     * @param value Double string
     * @param defaultValue Default if parsing fails
     * @param fieldName Field name for logging
     * @param min Minimum allowed value (inclusive)
     * @param max Maximum allowed value (inclusive)
     * @return Parsed double
     */
    private static double parseDouble(
        final String value,
        final double defaultValue,
        final String fieldName,
        final double min,
        final double max
    ) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            final double parsed = Double.parseDouble(value.trim());
            if (parsed < min || parsed > max) {
                System.err.printf(
                    "[GroupSettings] Value '%s' for '%s' out of range [%.2f, %.2f], using default: %.2f%n",
                    value, fieldName, min, max, defaultValue
                );
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            System.err.printf(
                "[GroupSettings] Failed to parse double '%s' for '%s', using default: %.2f%n",
                value, fieldName, defaultValue
            );
            return defaultValue;
        }
    }

    /**
     * Parse double from string (overload without field name for backward compatibility).
     * @param value Double string
     * @param defaultValue Default if parsing fails
     * @return Parsed double
     */
    private static double parseDouble(final String value, final double defaultValue) {
        return parseDouble(value, defaultValue, "unknown", Double.MIN_VALUE, Double.MAX_VALUE);
    }

    /**
     * Parse boolean from string.
     * @param value Boolean string
     * @param defaultValue Default if parsing fails
     * @return Parsed boolean
     */
    private static boolean parseBoolean(final String value, final boolean defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }
}
