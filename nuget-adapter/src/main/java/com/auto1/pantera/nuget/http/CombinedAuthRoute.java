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
import com.auto1.pantera.http.auth.CombinedAuthzSlice;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.TokenAuthentication;

/**
 * Route supporting combined basic and bearer token authentication.
 *
 * @since 1.18
 */
final class CombinedAuthRoute implements Route {

    /**
     * Origin route.
     */
    private final Route origin;

    /**
     * Operation access control.
     */
    private final OperationControl control;

    /**
     * Basic authentication.
     */
    private final Authentication basicAuth;

    /**
     * Token authentication.
     */
    private final TokenAuthentication tokenAuth;

    /**
     * Ctor.
     *
     * @param origin Origin route.
     * @param control Operation access control.
     * @param basicAuth Basic authentication mechanism.
     * @param tokenAuth Token authentication mechanism.
     */
    CombinedAuthRoute(
        final Route origin,
        final OperationControl control,
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth
    ) {
        this.origin = origin;
        this.control = control;
        this.basicAuth = basicAuth;
        this.tokenAuth = tokenAuth;
    }

    @Override
    public String path() {
        return this.origin.path();
    }

    @Override
    public Resource resource(final String path) {
        return new ResourceFromSlice(
            path,
            new CombinedAuthzSlice(
                new SliceFromResource(this.origin.resource(path)),
                this.basicAuth,
                this.tokenAuth,
                this.control
            )
        );
    }
}

