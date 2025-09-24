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
    private static final String KEY_NEWER_MIN = "newer_than_cache_minutes";
    private static final String KEY_FRESH_MIN = "fresh_release_minutes";
    // Backward compatibility
    private static final String KEY_NEWER_HOURS = "newer_than_cache_hours";
    private static final String KEY_FRESH_HOURS = "fresh_release_hours";

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
        // Prefer minutes keys; if not present, fallback to hours
        final Integer newerMinRaw = node.integer(KEY_NEWER_MIN);
        final Integer freshMinRaw = node.integer(KEY_FRESH_MIN);
        final Integer newerHoursRaw = node.integer(KEY_NEWER_HOURS);
        final Integer freshHoursRaw = node.integer(KEY_FRESH_HOURS);
        final long newerMin = newerMinRaw != null && newerMinRaw > 0
            ? newerMinRaw.longValue()
            : (newerHoursRaw != null && newerHoursRaw > 0
                ? Duration.ofHours(newerHoursRaw.longValue()).toMinutes()
                : defaults.newerThanCache().toMinutes());
        final long freshMin = freshMinRaw != null && freshMinRaw > 0
            ? freshMinRaw.longValue()
            : (freshHoursRaw != null && freshHoursRaw > 0
                ? Duration.ofHours(freshHoursRaw.longValue()).toMinutes()
                : defaults.freshRelease().toMinutes());
        final Duration newer = Duration.ofMinutes(newerMin);
        final Duration fresh = Duration.ofMinutes(freshMin);
        return new CooldownSettings(enabled, newer, fresh);
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
}
