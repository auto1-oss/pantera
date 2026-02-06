/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.misc;

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
        final String prop = System.getProperty(envVar.toLowerCase().replace('_', '.'));
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
}
