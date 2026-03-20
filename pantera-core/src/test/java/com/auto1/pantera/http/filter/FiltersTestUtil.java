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
package com.auto1.pantera.http.filter;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.http.rq.RequestLine;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Util class for filters tests.
 *
 * @since 1.2
 */
@SuppressWarnings("PMD.ProhibitPublicStaticMethods")
public final class FiltersTestUtil {
    /**
     * Ctor.
     */
    private FiltersTestUtil() {
    }

    /**
     * Get request.
     * @param path Request path
     * @return Get request
     */
    public static RequestLine get(final String path) {
        return RequestLine.from(String.format("GET %s HTTP/1.1", path));
    }

    /**
     * Create yaml mapping from string.
     * @param yaml String containing yaml configuration
     * @return Yaml mapping
     */
    public static YamlMapping yaml(final String yaml) {
        try {
            return Yaml.createYamlInput(yaml).readYamlMapping();
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }
}
