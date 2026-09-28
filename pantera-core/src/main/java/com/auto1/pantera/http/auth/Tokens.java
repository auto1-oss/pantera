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

    /**
     * Issue a named, expiring API token for a client that logs in with a
     * password (e.g. {@code npm login}) and stores the result as its
     * registry credential. Implementations with a token store MUST persist
     * it (so it can be listed and revoked) and MUST honour the admin token
     * lifetime policy; such a token is never permanent. The default, for
     * implementations without a token store, is an ordinary access token.
     *
     * @param user Authenticated user
     * @param label Human-readable label shown in the token list
     * @return String token
     */
    default String issueApiToken(final AuthUser user, final String label) {
        return generate(user);
    }

    /**
     * Generate an access + refresh token pair for login/callback.
     * @param user Authenticated user
     * @return Token pair (access token, refresh token, expiresIn)
     */
    default TokenPair generatePair(AuthUser user) {
        throw new UnsupportedOperationException("Token pair generation not supported");
    }

    /**
     * Rotate a refresh token: consume the presented refresh token (by JTI)
     * and issue a successor access + refresh pair. Implementations MUST
     * refuse (return {@code null}) when the presented JTI is not a live
     * refresh token owned by {@code user}, so a replayed or stolen refresh
     * token cannot mint new credentials. The default keeps pre-2.2.9
     * behaviour for implementations without a token store.
     *
     * @param user Authenticated user (subject of the presented token)
     * @param refreshJti JTI of the presented refresh token
     * @return Successor pair, or {@code null} when the presented token was
     *  not acceptable
     */
    default TokenPair rotate(AuthUser user, String refreshJti) {
        return generatePair(user);
    }

    /**
     * Token pair containing both access and refresh tokens.
     */
    record TokenPair(String accessToken, String refreshToken, int expiresIn) {}
}
