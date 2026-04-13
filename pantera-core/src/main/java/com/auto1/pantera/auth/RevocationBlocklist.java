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
     * Check if all tokens for a user have been revoked.
     * @param username Username to check
     * @return True if the user's tokens are revoked
     */
    boolean isRevokedUser(String username);

    /**
     * Revoke a specific token by JTI.
     * @param jti JWT ID claim value
     * @param ttlSeconds Time-to-live in seconds for the revocation entry
     */
    void revokeJti(String jti, int ttlSeconds);

    /**
     * Revoke all tokens for a user.
     * @param username Username whose tokens should be revoked
     * @param ttlSeconds Time-to-live in seconds for the revocation entry
     */
    void revokeUser(String username, int ttlSeconds);
}
