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
package com.auto1.pantera.http.rq;

import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * URI query parameters. See <a href="https://tools.ietf.org/html/rfc3986#section-3.4">RFC</a>.
 */
public final class RqParams {

    private final List<NameValuePair> params;

    /**
     * @param uri Request URI.
     */
    public RqParams(URI uri) {
        params = new URIBuilder(uri).getQueryParams();
    }

    /**
     * Get value for parameter value by name.
     * Empty {@link Optional} is returned if parameter not found.
     * First value is returned if multiple parameters with same name present in the query.
     *
     * @param name Parameter name.
     * @return Parameter value.
     */
    public Optional<String> value(String name) {
        return params.stream()
            .filter(p -> Objects.equals(name, p.getName()))
            .map(NameValuePair::getValue)
            .findFirst();
    }

    /**
     * Get values for parameter value by name.
     * Empty {@link List} is returned if parameter not found.
     * Return List with all founded values if parameters with same name present in query
     *
     * @param name Parameter name.
     * @return List of Parameter values
     */
    public List<String> values(String name) {
        return params.stream()
            .filter(p -> Objects.equals(name, p.getName()))
            .map(NameValuePair::getValue)
            .toList();
    }
}
