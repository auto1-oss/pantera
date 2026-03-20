/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.cooldown;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cooldown.CooldownSettings.RepoTypeConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@link CooldownSettings} from Pantera YAML configuration.
 */
public final class YamlCooldownSettings {

    private static final String NODE = "cooldown";
    private static final String KEY_ENABLED = "enabled";
    // New simplified key: accepts duration strings like 1m, 3h, 4d
    private static final String KEY_MIN_AGE = "minimum_allowed_age";
    // Legacy keys kept for backward compatibility
    private static final String KEY_NEWER_BY = "newer_than_cache_by";
    private static final String KEY_FRESH_AGE = "fresh_release_age";
    // Per-repo-type configuration
    private static final String KEY_REPO_TYPES = "repo_types";


    private YamlCooldownSettings() {
        // Utility class
    }

    /**
     * Read settings from meta section.
     *
     * @param meta Meta section of artipie.yml
     * @return Cooldown settings (defaults when absent)
     */
    public static CooldownSettings fromMeta(final YamlMapping meta) {
        final CooldownSettings defaults = CooldownSettings.defaults();
        if (meta == null) {
            return defaults;
        }
        final YamlMapping node = meta.yamlMapping(NODE);
        if (node == null) {
            return defaults;
        }
        final boolean enabled = parseBool(node.string(KEY_ENABLED), defaults.enabled());
        // New key takes precedence
        final String minAgeStr = node.string(KEY_MIN_AGE);
        // Backward compatibility: prefer fresh_release_age, then newer_than_cache_by
        final String freshStr = node.string(KEY_FRESH_AGE);
        final String newerStr = node.string(KEY_NEWER_BY);

        final Duration minAge = parseDurationOrDefault(minAgeStr,
            parseDurationOrDefault(freshStr,
                parseDurationOrDefault(newerStr, defaults.minimumAllowedAge())
            )
        );

        // Parse per-repo-type overrides
        final Map<String, RepoTypeConfig> repoTypeOverrides = new HashMap<>();
        final YamlMapping repoTypes = node.yamlMapping(KEY_REPO_TYPES);
        if (repoTypes != null) {
            for (final var entry : repoTypes.keys()) {
                final String repoType = entry.asScalar().value().toLowerCase();
                final YamlMapping repoConfig = repoTypes.yamlMapping(entry.asScalar().value());
                if (repoConfig != null) {
                    final boolean repoEnabled = parseBool(
                        repoConfig.string(KEY_ENABLED),
                        enabled  // Inherit global if not specified
                    );
                    final Duration repoMinAge = parseDurationOrDefault(
                        repoConfig.string(KEY_MIN_AGE),
                        minAge  // Inherit global if not specified
                    );
                    repoTypeOverrides.put(repoType, new RepoTypeConfig(repoEnabled, repoMinAge));
                }
            }
        }

        return new CooldownSettings(enabled, minAge, repoTypeOverrides);
    }

    private static boolean parseBool(final String value, final boolean fallback) {
        if (value == null) {
            return fallback;
        }
        final String normalized = value.trim().toLowerCase(Locale.US);
        if ("true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    /**
     * Parses duration strings like "1m", "3h", "4d". Returns fallback when null/invalid.
     * Supported units: m (minutes), h (hours), d (days).
     *
     * @param value String value
     * @param fallback Fallback duration
     * @return Parsed duration or fallback
     */
    private static Duration parseDurationOrDefault(final String value, final Duration fallback) {
        if (value == null) {
            return fallback;
        }
        final String val = value.trim().toLowerCase(Locale.US);
        if (val.isEmpty()) {
            return fallback;
        }
        // Accept formats like 15m, 3h, 4d (optionally with spaces, e.g. "15 m")
        final String digits = val.replaceAll("[^0-9]", "");
        final String unit = val.replaceAll("[0-9\\s]", "");
        if (digits.isEmpty() || unit.isEmpty()) {
            return fallback;
        }
        try {
            final long amount = Long.parseLong(digits);
            return switch (unit) {
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> fallback;
            };
        } catch (final NumberFormatException err) {
            return fallback;
        }
    }

    /**
     * Example YAML configuration with per-repo-type overrides:
     * <pre>
     * meta:
     *   cooldown:
     *     # Global defaults
     *     enabled: true
     *     minimum_allowed_age: 24h
     *     
     *     # Per-repo-type overrides
     *     repo_types:
     *       maven:
     *         enabled: true
     *         minimum_allowed_age: 48h  # Maven needs 48 hours
     *       npm:
     *         enabled: true
     *         minimum_allowed_age: 12h  # NPM needs only 12 hours
     *       docker:
     *         enabled: false            # Docker cooldown disabled
     *       pypi:
     *         minimum_allowed_age: 72h  # PyPI 72 hours, inherits global enabled
     * </pre>
     */
}
