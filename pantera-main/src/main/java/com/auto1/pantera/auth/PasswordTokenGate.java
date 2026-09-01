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
 * Gate deciding whether an already signature-verified JWT may be used as a
 * Basic-auth repository password right now.
 *
 * <p>A valid signature is necessary but not sufficient: the token must also
 * survive revocation (blocklist / {@code user_tokens} JTI ownership), the
 * subject must still be enabled, and the token type must be one that is
 * legitimately usable as a long-lived credential. Bearer-token validation
 * ({@code UnifiedJwtAuthHandler}) already enforces all of this; before 2.2.9
 * the {@code jwt-password} Basic path did not, so a revoked API token — or a
 * refresh token — kept authorizing (SecOps finding #40 / token confusion).
 *
 * @since 2.2.9
 */
@FunctionalInterface
public interface PasswordTokenGate {

    /**
     * @param type Token type claim (access / refresh / api), may be {@code null}
     * @param jti Token id claim, may be {@code null}
     * @param sub Token subject (username)
     * @return {@code true} iff the token may authorize as a password now
     */
    boolean allows(TokenType type, String jti, String sub);
}
