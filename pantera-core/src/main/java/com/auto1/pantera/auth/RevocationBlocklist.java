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

import java.time.Instant;

/**
 * Interface for token revocation blocklist.
 * Used by UnifiedJwtAuthHandler to reject access tokens immediately.
 * @since 2.1.0
 */
public interface RevocationBlocklist {

    /**
     * Check if a token JTI has been revoked.
     * @param jti JWT ID claim value
     * @return True if the JTI is revoked
     */
    boolean isRevokedJti(String jti);

    /**
     * Check if a user-wide revocation covers a token issued at {@code issuedAt}.
     * Only tokens issued before the revocation are covered, so the user can
     * sign in again afterwards (see {@link UserRevocation#revokes}).
     * @param username Token subject
     * @param issuedAt Token {@code iat}; {@code null} counts as covered
     * @return True if the token is revoked
     */
    boolean isRevokedUser(String username, Instant issuedAt);

    /**
     * Revoke a specific token by JTI.
     * @param jti JWT ID claim value
     * @param ttlSeconds Time-to-live in seconds for the revocation entry
     */
    void revokeJti(String jti, int ttlSeconds);

    /**
     * Revoke every token issued to a user up to now.
     * @param username Username whose tokens should be revoked
     * @param ttlSeconds Time-to-live in seconds for the revocation entry
     */
    void revokeUser(String username, int ttlSeconds);
}
