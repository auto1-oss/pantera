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
package com.auto1.pantera.hex.tarball;

/**
 * Parses metadata.config file of erlang/elixir tarball's content.
 * Allows to extract 'app' and 'version' values of parsed tuples.
 * Expected following format of erlang tuple-expressions:
 * <pre>
 *  {<<"app">>,<<"decimal">>}.
 *  {<<"version">>,<<"2.0.0">>}.
 *  </pre>
 *
 * @since 0.1
 */
public final class MetadataConfig {
    /**
     * Cleaned content of metadata.config.
     */
    private final String content;

    /**
     * Ctor.
     *
     * @param content Content of metadata.config
     */
    public MetadataConfig(final byte[] content) {
        this.content = MetadataConfig.format(content);
    }

    /**
     * Get value of app.
     *
     * @return App name
     */
    public String app() {
        return this.value("app");
    }

    /**
     * Get value of version.
     *
     * @return App version
     */
    public String version() {
        return this.value("version");
    }

    /**
     * Format original Erlang tuples to simple string.
     *
     * @param original Original content of metadata.config
     * @return Formatted string
     */
    private static String format(final byte[] original) {
        return new String(original)
            .replace("\"", "")
            .replace("<<", "")
            .replace(">>", "")
            .replace("{", "")
            .replace("}", "");
    }

    /**
     * Get value by name from metadata.config.
     *
     * @param name Name of value
     * @return Value by name
     */
    private String value(final String name) {
        String value = "";
        final String[] strings = this.content.split("\\.\n");
        for (final String str : strings) {
            final String[] split = str.split(",");
            if (name.equals(split[0])) {
                value = split[1];
                break;
            }
        }
        return value;
    }
}
