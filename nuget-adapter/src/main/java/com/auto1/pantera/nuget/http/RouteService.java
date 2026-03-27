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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.nuget.http.index.Service;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;

/**
 * Service that is located by {@link Route}.
 *
 * @since 0.1
 */
@SuppressWarnings("deprecation")
final class RouteService implements Service {

    /**
     * Base URL for the route.
     */
    private final URL base;

    /**
     * Route for the service.
     */
    private final Route route;

    /**
     * Service type.
     */
    private final String stype;

    /**
     * Ctor.
     *
     * @param base Base URL for the route.
     * @param route Route for the service.
     * @param stype Service type.
     */
    RouteService(final URL base, final Route route, final String stype) {
        this.base = base;
        this.route = route;
        this.stype = stype;
    }

    @Override
    public String url() {
        final String path = String.format("%s%s", this.base.getPath(), this.route.path());
        final String file = Optional.ofNullable(this.base.getQuery())
            .map(query -> String.format("%s?%s", path, this.base.getQuery()))
            .orElse(path);
        try {
            return new URL(this.base.getProtocol(), this.base.getHost(), this.base.getPort(), file)
                .toString();
        } catch (final MalformedURLException ex) {
            throw new IllegalStateException(
                String.format("Failed to build URL from base: '%s'", this.base),
                ex
            );
        }
    }

    @Override
    public String type() {
        return this.stype;
    }
}
