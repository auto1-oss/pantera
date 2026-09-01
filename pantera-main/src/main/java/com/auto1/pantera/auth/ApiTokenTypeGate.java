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
package com.auto1.pantera.auth;

/**
 * Token-purpose scope for the management API ({@code /api/v1/*}).
 *
 * <p>Before 2.2.9 the shared JWT filter applied one generic
 * "authenticated user" gate, so a REFRESH token — long-lived by design and
 * meant only to obtain a new access token — authorized every protected
 * route (including minting API tokens), and an ACCESS/API token could drive
 * {@code /auth/refresh} to mint itself a fresh pair. The purpose claim is
 * verified by {@link UnifiedJwtAuthHandler}; this gate enforces it per route
 * (SecOps jwt-token-confusion).</p>
 *
 * @since 2.2.9
 */
public final class ApiTokenTypeGate {

    /**
     * The only route a REFRESH token may reach.
     */
    private static final String REFRESH_ROUTE = "/auth/refresh";

    private ApiTokenTypeGate() {
    }

    /**
     * Whether a token of the given verified type may reach the route.
     *
     * @param path Request path
     * @param type Verified token purpose
     * @return {@code true} iff the type is in scope for the route
     */
    public static boolean allows(final String path, final TokenType type) {
        final boolean refreshRoute = path != null && path.endsWith(REFRESH_ROUTE);
        if (refreshRoute) {
            return type == TokenType.REFRESH;
        }
        return type == TokenType.ACCESS || type == TokenType.API;
    }
}
