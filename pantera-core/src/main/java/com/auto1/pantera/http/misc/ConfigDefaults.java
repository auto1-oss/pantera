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
package com.auto1.pantera.http.misc;

import java.util.Locale;

/**
 * Centralized configuration defaults with environment variable overrides.
 * Values are read with precedence: env var > system property > default.
 *
 * @since 1.20.13
 */
public final class ConfigDefaults {

    private ConfigDefaults() {
    }

    /**
     * Read a configuration value.
     * @param envVar Environment variable name
     * @param defaultValue Default value if not set
     * @return Configured value or default
     */
    public static String get(final String envVar, final String defaultValue) {
        final String env = System.getenv(envVar);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        final String prop = System.getProperty(envVar.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (prop != null && !prop.isEmpty()) {
            return prop;
        }
        return defaultValue;
    }

    /**
     * Read an integer configuration value.
     * @param envVar Environment variable name
     * @param defaultValue Default value
     * @return Configured value or default
     */
    public static int getInt(final String envVar, final int defaultValue) {
        try {
            return Integer.parseInt(get(envVar, String.valueOf(defaultValue)));
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Read a long configuration value.
     * @param envVar Environment variable name
     * @param defaultValue Default value
     * @return Configured value or default
     */
    public static long getLong(final String envVar, final long defaultValue) {
        try {
            return Long.parseLong(get(envVar, String.valueOf(defaultValue)));
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Read a boolean configuration value.
     * Accepts case-insensitive {@code "true"}, {@code "1"}, {@code "yes"} as true;
     * case-insensitive {@code "false"}, {@code "0"}, {@code "no"} as false;
     * empty / missing / unrecognized returns the fallback.
     * @param envVar Environment variable name
     * @param defaultValue Default value if not set or unrecognized
     * @return Configured value or default
     */
    public static boolean getBoolean(final String envVar, final boolean defaultValue) {
        final String raw = get(envVar, "");
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        final String val = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(val) || "1".equals(val) || "yes".equals(val)) {
            return true;
        }
        if ("false".equals(val) || "0".equals(val) || "no".equals(val)) {
            return false;
        }
        return defaultValue;
    }
}
