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

import com.auth0.jwt.JWT;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Tokens;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for refresh-token replay (SecOps token-revocation
 * #23 / jwt-token-confusion refresh facet).
 *
 * <p>Before 2.2.9 {@code /auth/refresh} minted a brand-new access+refresh
 * pair from the presented refresh token's subject and never consumed the
 * presented refresh JTI, so a stolen refresh token stayed valid for its
 * whole TTL no matter how many times the legitimate client had already
 * refreshed — unlimited replay. Rotation must revoke the presented JTI as
 * part of issuing its successor.</p>
 *
 * @since 2.2.9
 */
final class JwtTokensRefreshRotationTest {

    private JwtTokens tokens;
    private InMemoryUserTokenDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        this.dao = new InMemoryUserTokenDao();
        this.tokens = new JwtTokens(
            (RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic(),
            this.dao, null, null
        );
    }

    @Test
    void presentedRefreshTokenIsConsumedByRotation() {
        final AuthUser alice = new AuthUser("alice", "jwt");
        final Tokens.TokenPair first = this.tokens.generatePair(alice);
        final String oldJti = JWT.decode(first.refreshToken()).getId();

        final Tokens.TokenPair rotated = this.tokens.rotate(alice, oldJti);

        MatcherAssert.assertThat(
            "rotation must issue a new refresh token",
            rotated.refreshToken().equals(first.refreshToken()), new IsEqual<>(false)
        );
        final Optional<AuthUser> replay = this.tokens.auth()
            .user(first.refreshToken()).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the presented (old) refresh token must be REJECTED after rotation — replay closed",
            replay.isPresent(), new IsEqual<>(false)
        );
        final Optional<AuthUser> fresh = this.tokens.auth()
            .user(rotated.refreshToken()).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the successor refresh token must be valid",
            fresh.isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void replayedRefreshTokenCannotRotateAgain() {
        final AuthUser alice = new AuthUser("alice", "jwt");
        final Tokens.TokenPair first = this.tokens.generatePair(alice);
        final String oldJti = JWT.decode(first.refreshToken()).getId();
        this.tokens.rotate(alice, oldJti);

        MatcherAssert.assertThat(
            "rotating with an already-consumed refresh JTI must be refused",
            this.tokens.rotate(alice, oldJti) == null, new IsEqual<>(true)
        );
    }

    @Test
    void rotationRequiresJtiOwnership() {
        final AuthUser alice = new AuthUser("alice", "jwt");
        final AuthUser mallory = new AuthUser("mallory", "jwt");
        final Tokens.TokenPair pair = this.tokens.generatePair(alice);
        final String jti = JWT.decode(pair.refreshToken()).getId();

        MatcherAssert.assertThat(
            "a refresh JTI belonging to another user must not rotate",
            this.tokens.rotate(mallory, jti) == null, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the victim's refresh token must remain untouched by the failed attempt",
            this.dao.revoked(UUID.fromString(jti)), new IsEqual<>(false)
        );
    }
}
