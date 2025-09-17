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
    private static final String KEY_NEWER = "newer_than_cache_hours";
    private static final String KEY_FRESH = "fresh_release_hours";

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
        final Duration newer = Duration.ofHours(
            positiveOrDefault(node.integer(KEY_NEWER), defaults.newerThanCache().toHours())
        );
        final Duration fresh = Duration.ofHours(
            positiveOrDefault(node.integer(KEY_FRESH), defaults.freshRelease().toHours())
        );
        return new CooldownSettings(enabled, newer, fresh);
    }

    private static long positiveOrDefault(final Integer value, final long fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value.longValue();
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
