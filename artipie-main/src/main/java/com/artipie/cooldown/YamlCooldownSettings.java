/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import java.util.Locale;

/**
 * Parses {@link CooldownSettings} from Artipie YAML configuration.
 */
public final class YamlCooldownSettings {

    private static final String NODE = "cooldown";
    private static final String KEY_ENABLED = "enabled";
    // New simplified key: accepts duration strings like 1m, 3h, 4d
    private static final String KEY_MIN_AGE = "minimum_allowed_age";
    // Legacy keys kept for backward compatibility
    private static final String KEY_NEWER_BY = "newer_than_cache_by";
    private static final String KEY_FRESH_AGE = "fresh_release_age";


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

        Duration minAge = parseDurationOrDefault(minAgeStr,
            parseDurationOrDefault(freshStr,
                parseDurationOrDefault(newerStr, defaults.minimumAllowedAge())
            )
        );

        return new CooldownSettings(enabled, minAge);
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
}
