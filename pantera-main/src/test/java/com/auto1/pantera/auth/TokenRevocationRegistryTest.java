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

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenRevocationRegistry}'s password-usability logic —
 * the gate the {@code jwt-password} provider consults (SecOps #40 / token
 * confusion).
 *
 * @since 2.2.9
 */
final class TokenRevocationRegistryTest {

    @AfterEach
    void tearDown() {
        TokenRevocationRegistry.instance().clear();
    }

    @Test
    void refreshTokenIsNeverUsableAsPassword() {
        TokenRevocationRegistry.instance().clear();
        MatcherAssert.assertThat(
            "a refresh token must never be usable as a password, even unwired",
            TokenRevocationRegistry.instance().allows(TokenType.REFRESH, "j", "alice"),
            new IsEqual<>(false)
        );
    }

    @Test
    void blocklistedJtiIsRejected() {
        TokenRevocationRegistry.instance().install(
            new StubBlocklist("BAD", null), null, null
        );
        MatcherAssert.assertThat(
            "a token whose JTI is blocklisted must be rejected",
            TokenRevocationRegistry.instance().allows(TokenType.ACCESS, "BAD", "alice"),
            new IsEqual<>(false)
        );
    }

    @Test
    void blocklistedUserIsRejected() {
        TokenRevocationRegistry.instance().install(
            new StubBlocklist(null, "alice"), null, null
        );
        MatcherAssert.assertThat(
            "a token for a blocklisted user must be rejected",
            TokenRevocationRegistry.instance().allows(TokenType.ACCESS, "j", "alice"),
            new IsEqual<>(false)
        );
    }

    @Test
    void disabledUserIsRejected() {
        TokenRevocationRegistry.instance().install(null, null, user -> false);
        MatcherAssert.assertThat(
            "a token for a disabled user must be rejected",
            TokenRevocationRegistry.instance().allows(TokenType.ACCESS, "j", "alice"),
            new IsEqual<>(false)
        );
    }

    @Test
    void validAccessTokenIsAllowed() {
        TokenRevocationRegistry.instance().install(
            new StubBlocklist(null, null), null, user -> true
        );
        MatcherAssert.assertThat(
            "a valid, non-revoked access token for an enabled user is allowed",
            TokenRevocationRegistry.instance().allows(TokenType.ACCESS, "j", "alice"),
            new IsEqual<>(true)
        );
    }

    @Test
    void nullTypeIsRejected() {
        TokenRevocationRegistry.instance().clear();
        MatcherAssert.assertThat(
            "a token with an unrecognised/absent type must be rejected",
            TokenRevocationRegistry.instance().allows(null, "j", "alice"),
            new IsEqual<>(false)
        );
    }

    /**
     * Blocklist stub matching one JTI and/or one username.
     */
    private static final class StubBlocklist implements RevocationBlocklist {
        private final String badJti;
        private final String badUser;

        StubBlocklist(final String badJti, final String badUser) {
            this.badJti = badJti;
            this.badUser = badUser;
        }

        @Override
        public boolean isRevokedJti(final String jti) {
            return this.badJti != null && this.badJti.equals(jti);
        }

        @Override
        public boolean isRevokedUser(final String username) {
            return this.badUser != null && this.badUser.equals(username);
        }

        @Override
        public void revokeJti(final String jti, final int ttlSeconds) {
        }

        @Override
        public void revokeUser(final String username, final int ttlSeconds) {
        }
    }
}
