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
package com.auto1.pantera.http.auth;

/**
 * Authentication tokens: generate token and provide authentication mechanism.
 * @since 1.2
 */
public interface Tokens {

    /**
     * Provide authentication mechanism.
     * @return Implementation of {@link TokenAuthentication}
     */
    TokenAuthentication auth();

    /**
     * Generate token for provided user.
     * @param user User to issue token for
     * @return String token
     */
    String generate(AuthUser user);

    /**
     * Generate token for provided user with explicit permanence control.
     * @param user User to issue token for
     * @param permanent If true, generate a non-expiring token regardless of global settings
     * @return String token
     */
    default String generate(AuthUser user, boolean permanent) {
        return generate(user);
    }
}
