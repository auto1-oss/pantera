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
package com.auto1.pantera.cooldown;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.config.CooldownSettings.RepoTypeConfig;
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
    private static final String KEY_REPO_NAMES = "repo_names";
    private static final String KEY_SNAPSHOTS = "snapshots";


    private YamlCooldownSettings() {
        // Utility class
    }

    /**
     * Read settings from meta section.
     *
     * @param meta Meta section of pantera.yml
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
                final String repoType = entry.asScalar().value().toLowerCase(Locale.ROOT);
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

        // Parse per-repo-name overrides (highest priority).
        final Map<String, RepoTypeConfig> repoNameOverrides = new HashMap<>();
        final Map<String, CooldownSettings.SnapshotPolicy> repoNameSnapshots = new HashMap<>();
        final YamlMapping repoNames = node.yamlMapping(KEY_REPO_NAMES);
        if (repoNames != null) {
            for (final var entry : repoNames.keys()) {
                final String repoName = entry.asScalar().value();
                final YamlMapping repoConfig = repoNames.yamlMapping(repoName);
                if (repoConfig == null) {
                    continue;
                }
                final boolean repoEnabled = parseBool(
                    repoConfig.string(KEY_ENABLED), enabled
                );
                final Duration repoMinAge = parseDurationOrDefault(
                    repoConfig.string(KEY_MIN_AGE), minAge
                );
                repoNameOverrides.put(repoName, new RepoTypeConfig(repoEnabled, repoMinAge));
                final CooldownSettings.SnapshotPolicy snap = parseSnapshotPolicy(
                    repoConfig.yamlMapping(KEY_SNAPSHOTS)
                );
                if (!snap.isInherit()) {
                    repoNameSnapshots.put(repoName, snap);
                }
            }
        }

        final CooldownSettings settings = new CooldownSettings(
            enabled, minAge, repoTypeOverrides, repoNameOverrides
        );
        settings.setSnapshotPolicy(parseSnapshotPolicy(node.yamlMapping(KEY_SNAPSHOTS)));
        for (final var entry : repoNameSnapshots.entrySet()) {
            settings.setRepoNameSnapshotOverride(entry.getKey(), entry.getValue());
        }
        return settings;
    }

    /**
     * Parse a {@code snapshots:} sub-mapping into a SNAPSHOT policy. Either
     * key may be absent — absence means "inherit from the next-higher tier".
     *
     * @param mapping YAML sub-mapping (may be null)
     * @return Policy (never null; defaults to {@link CooldownSettings.SnapshotPolicy#inherit()})
     */
    private static CooldownSettings.SnapshotPolicy parseSnapshotPolicy(final YamlMapping mapping) {
        if (mapping == null) {
            return CooldownSettings.SnapshotPolicy.inherit();
        }
        final String enabledStr = mapping.string(KEY_ENABLED);
        final Boolean enabled;
        if (enabledStr == null) {
            enabled = null;
        } else {
            // parseBool returns the fallback when the value is unparseable; we
            // explicitly want null in that case so the next tier wins, hence
            // the two-step check.
            enabled = parseBool(enabledStr, true);
        }
        final Duration minAge = parseDurationOrNull(mapping.string(KEY_MIN_AGE));
        return CooldownSettings.SnapshotPolicy.of(enabled, minAge);
    }

    /**
     * Parse a duration string, returning null (not a fallback) when absent
     * or unparseable. Used by SNAPSHOT-policy parsing where absence must
     * propagate as "inherit" rather than be flattened into a default.
     */
    private static Duration parseDurationOrNull(final String value) {
        if (value == null) {
            return null;
        }
        final Duration parsed = parseDurationOrDefault(value, null);
        return parsed;
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
