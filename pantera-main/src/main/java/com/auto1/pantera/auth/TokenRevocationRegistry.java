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

import com.auto1.pantera.db.dao.UserTokenDao;
import java.util.UUID;

/**
 * Process-wide gate that lets the reflectively-constructed
 * {@code jwt-password} auth provider reach the same revocation, JTI-ownership
 * and enabled-state validators the Bearer path ({@code UnifiedJwtAuthHandler})
 * uses. Wired once at boot by {@code VertxMain}; a no-op singleton until then.
 *
 * <p>Mirrors {@code NegativeCacheRegistry} / {@code FilteredMetadataCacheRegistry}:
 * a static singleton the auth framework can read without threading runtime
 * singletons through the {@code AuthFactory} SPI. When unwired (tests,
 * storage-less boots) it still rejects refresh tokens — a refresh token is
 * never a valid long-lived password — but cannot consult revocation state, so
 * it does not manufacture a stricter answer than the deployment can enforce.
 *
 * @since 2.2.9
 */
public final class TokenRevocationRegistry implements PasswordTokenGate {

    private static final TokenRevocationRegistry INSTANCE = new TokenRevocationRegistry();

    private volatile RevocationBlocklist blocklist;
    private volatile UserTokenDao tokenDao;
    private volatile UserEnabledCheck enabledCheck;

    private TokenRevocationRegistry() {
    }

    /**
     * @return The shared singleton.
     */
    public static TokenRevocationRegistry instance() {
        return INSTANCE;
    }

    /**
     * Wire the runtime validators (from {@code VertxMain}). Any argument may
     * be {@code null} (feature absent for this deployment).
     *
     * @param blocklist Revocation blocklist, or {@code null}
     * @param tokenDao {@code user_tokens} DAO for JTI ownership, or {@code null}
     * @param enabledCheck Per-request enabled-state gate, or {@code null}
     */
    public void install(
        final RevocationBlocklist blocklist,
        final UserTokenDao tokenDao,
        final UserEnabledCheck enabledCheck
    ) {
        this.blocklist = blocklist;
        this.tokenDao = tokenDao;
        this.enabledCheck = enabledCheck;
    }

    /**
     * Clear the wiring (tests).
     */
    public void clear() {
        this.blocklist = null;
        this.tokenDao = null;
        this.enabledCheck = null;
    }

    @Override
    public boolean allows(final TokenType type, final String jti, final String sub) {
        if (type == null || sub == null) {
            return false;
        }
        // A refresh token is for minting access tokens, never a repository
        // credential — reject it as a password regardless of revocation infra.
        if (type == TokenType.REFRESH) {
            return false;
        }
        final RevocationBlocklist bl = this.blocklist;
        if (bl != null && (bl.isRevokedJti(jti) || bl.isRevokedUser(sub))) {
            return false;
        }
        final UserTokenDao dao = this.tokenDao;
        if (dao != null && type == TokenType.API) {
            if (jti == null) {
                return false;
            }
            try {
                if (!dao.isValidForUser(UUID.fromString(jti), sub)) {
                    return false;
                }
            } catch (final IllegalArgumentException ex) {
                // Malformed (non-UUID) JTI — treat as invalid.
                return false;
            }
        }
        final UserEnabledCheck check = this.enabledCheck;
        return check == null || check.isEnabled(sub);
    }
}
