/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
