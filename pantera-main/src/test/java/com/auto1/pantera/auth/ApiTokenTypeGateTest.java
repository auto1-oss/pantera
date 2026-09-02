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
import com.auth0.jwt.algorithms.Algorithm;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression tests for JWT token-type confusion on the management
 * API Bearer path (SecOps jwt-token-confusion).
 *
 * <p>Before 2.2.9 {@code UnifiedJwtAuthHandler.validate()} discarded the
 * verified {@code type} claim and the {@code /api/v1/*} filter applied one
 * generic authenticated-user gate, so a long-lived REFRESH token was
 * accepted as a Bearer credential on every protected route (and an ACCESS
 * token could drive {@code /auth/refresh}). The verified type must survive
 * validation and the route gate must enforce it.</p>
 *
 * @since 2.2.9
 */
final class ApiTokenTypeGateTest {

    private Algorithm algorithm;
    private UnifiedJwtAuthHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        this.algorithm = Algorithm.RSA256(
            (RSAPublicKey) kp.getPublic(), (RSAPrivateKey) kp.getPrivate()
        );
        this.handler = new UnifiedJwtAuthHandler((RSAPublicKey) kp.getPublic(), null, null);
    }

    @Test
    void validatedTokenCarriesItsVerifiedType() {
        final Optional<UnifiedJwtAuthHandler.ValidatedToken> refresh =
            this.handler.validated(this.token("refresh"));
        MatcherAssert.assertThat(
            "a refresh token must validate with type REFRESH preserved",
            refresh.map(UnifiedJwtAuthHandler.ValidatedToken::type).orElse(null),
            new IsEqual<>(TokenType.REFRESH)
        );
        MatcherAssert.assertThat(
            "the verified JTI must be preserved for rotation",
            refresh.map(UnifiedJwtAuthHandler.ValidatedToken::jti).orElse(null),
            new IsEqual<>("00000000-0000-0000-0000-00000000000a")
        );
    }

    @Test
    void refreshTokenIsRejectedOnOrdinaryApiRoutes() {
        MatcherAssert.assertThat(
            "a REFRESH token must NOT authorize an ordinary /api/v1 route",
            ApiTokenTypeGate.allows("/api/v1/repositories", TokenType.REFRESH),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a REFRESH token must NOT mint API tokens",
            ApiTokenTypeGate.allows("/api/v1/auth/token/generate", TokenType.REFRESH),
            new IsEqual<>(false)
        );
    }

    @Test
    void onlyRefreshTokensMayRefresh() {
        MatcherAssert.assertThat(
            "an ACCESS token must not drive /auth/refresh",
            ApiTokenTypeGate.allows("/api/v1/auth/refresh", TokenType.ACCESS),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "an API token must not drive /auth/refresh",
            ApiTokenTypeGate.allows("/api/v1/auth/refresh", TokenType.API),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a REFRESH token is exactly what /auth/refresh accepts",
            ApiTokenTypeGate.allows("/api/v1/auth/refresh", TokenType.REFRESH),
            new IsEqual<>(true)
        );
    }

    @Test
    void accessAndApiTokensAuthorizeOrdinaryRoutes() {
        MatcherAssert.assertThat(
            "an ACCESS token authorizes ordinary routes",
            ApiTokenTypeGate.allows("/api/v1/repositories", TokenType.ACCESS),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "an API token authorizes ordinary routes",
            ApiTokenTypeGate.allows("/api/v1/repositories", TokenType.API),
            new IsEqual<>(true)
        );
    }

    private String token(final String type) {
        return JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withClaim("type", type)
            .withJWTId("00000000-0000-0000-0000-00000000000a")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
    }
}
