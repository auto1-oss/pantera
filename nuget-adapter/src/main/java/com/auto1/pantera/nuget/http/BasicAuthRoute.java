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

import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;

/**
 * Route supporting basic authentication.
 *
 * @since 0.2
 */
final class BasicAuthRoute implements Route {

    /**
     * Origin route.
     */
    private final Route origin;

    /**
     * Operation access control.
     */
    private final OperationControl control;

    /**
     * Authentication.
     */
    private final Authentication auth;

    /**
     * Ctor.
     *
     * @param origin Origin route.
     * @param control Operation access control.
     * @param auth Authentication mechanism.
     */
    BasicAuthRoute(final Route origin, final OperationControl control, final Authentication auth) {
        this.origin = origin;
        this.auth = auth;
        this.control = control;
    }

    @Override
    public String path() {
        return this.origin.path();
    }

    @Override
    public Resource resource(final String path) {
        return new ResourceFromSlice(
            path,
            new BasicAuthzSlice(
                new SliceFromResource(this.origin.resource(path)),
                this.auth,
                this.control
            )
        );
    }
}
