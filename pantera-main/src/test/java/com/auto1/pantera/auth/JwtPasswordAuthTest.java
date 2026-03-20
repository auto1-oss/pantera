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

import com.auto1.pantera.api.AuthTokenRest;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.settings.JwtSettings;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Tests for {@link JwtPasswordAuth}.
 * Verifies that JWT tokens can be used as passwords in Basic Authentication.
 *
 * @since 1.20.7
 */
class JwtPasswordAuthTest {

    /**
     * Shared Vertx instance.
     */
    private static Vertx vertx;

    /**
     * JWT secret for testing.
     */
    private static final String SECRET = "test-secret-key-for-jwt-password-auth";

    /**
     * JWT provider for generating test tokens.
     */
    private JWTAuth jwtProvider;

    /**
     * JwtPasswordAuth under test.
     */
    private JwtPasswordAuth auth;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    @BeforeEach
    void setUp() {
        this.jwtProvider = JWTAuth.create(
            vertx,
            new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer(SECRET)
            )
        );
        this.auth = new JwtPasswordAuth(this.jwtProvider);
    }

    @Test
    void authenticatesWithValidJwtAsPassword() {
        final String username = "testuser@example.com";
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, username)
                .put(AuthTokenRest.CONTEXT, "okta")
        );
        final Optional<AuthUser> result = this.auth.user(username, token);
        MatcherAssert.assertThat(
            "Should authenticate with valid JWT as password",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Username should match token subject",
            result.get().name(),
            Matchers.is(username)
        );
        MatcherAssert.assertThat(
            "Auth context should be from token",
            result.get().authContext(),
            Matchers.is("okta")
        );
    }

    @Test
    void rejectsWhenUsernameDoesNotMatchTokenSubject() {
        final String tokenSubject = "realuser@example.com";
        final String providedUsername = "fakeuser@example.com";
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, tokenSubject)
                .put(AuthTokenRest.CONTEXT, "test")
        );
        final Optional<AuthUser> result = this.auth.user(providedUsername, token);
        MatcherAssert.assertThat(
            "Should reject when username doesn't match token subject",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void allowsMismatchedUsernameWhenMatchingDisabled() {
        final JwtPasswordAuth noMatchAuth = new JwtPasswordAuth(this.jwtProvider, false);
        final String tokenSubject = "realuser@example.com";
        final String providedUsername = "anyuser@example.com";
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, tokenSubject)
                .put(AuthTokenRest.CONTEXT, "test")
        );
        final Optional<AuthUser> result = noMatchAuth.user(providedUsername, token);
        MatcherAssert.assertThat(
            "Should allow when username matching is disabled",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Username should be from token subject, not provided username",
            result.get().name(),
            Matchers.is(tokenSubject)
        );
    }

    @Test
    void returnsEmptyForNonJwtPassword() {
        final Optional<AuthUser> result = this.auth.user("user", "regular-password");
        MatcherAssert.assertThat(
            "Should return empty for non-JWT password",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForNullPassword() {
        final Optional<AuthUser> result = this.auth.user("user", null);
        MatcherAssert.assertThat(
            "Should return empty for null password",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForEmptyPassword() {
        final Optional<AuthUser> result = this.auth.user("user", "");
        MatcherAssert.assertThat(
            "Should return empty for empty password",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForInvalidJwt() {
        final String invalidToken = "eyJhbGciOiJIUzI1NiJ9.invalid.signature";
        final Optional<AuthUser> result = this.auth.user("user", invalidToken);
        MatcherAssert.assertThat(
            "Should return empty for invalid JWT",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForJwtWithWrongSecret() {
        // Create JWT with different secret
        final JWTAuth otherProvider = JWTAuth.create(
            vertx,
            new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer("different-secret")
            )
        );
        final String token = otherProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, "user")
                .put(AuthTokenRest.CONTEXT, "test")
        );
        final Optional<AuthUser> result = this.auth.user("user", token);
        MatcherAssert.assertThat(
            "Should reject JWT signed with different secret",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForExpiredJwt() throws Exception {
        // Generate token that expires in 1 second
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, "user")
                .put(AuthTokenRest.CONTEXT, "test"),
            new JWTOptions().setExpiresInSeconds(1)
        );
        // Wait for token to expire
        Thread.sleep(2000);
        final Optional<AuthUser> result = this.auth.user("user", token);
        MatcherAssert.assertThat(
            "Should reject expired JWT",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForJwtWithoutSubClaim() {
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.CONTEXT, "test")
            // Missing 'sub' claim
        );
        final Optional<AuthUser> result = this.auth.user("user", token);
        MatcherAssert.assertThat(
            "Should reject JWT without 'sub' claim",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForPasswordThatLooksLikeJwtButIsNot() {
        // Starts with "eyJ" but is not valid JWT
        final String fakeJwt = "eyJhbGciOiJub25lIn0.not-base64.fake";
        final Optional<AuthUser> result = this.auth.user("user", fakeJwt);
        MatcherAssert.assertThat(
            "Should return empty for fake JWT-like password",
            result.isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void toStringIncludesClassName() {
        MatcherAssert.assertThat(
            this.auth.toString(),
            Matchers.containsString("JwtPasswordAuth")
        );
    }

    @Test
    void canBeCreatedFromSecret() {
        final JwtPasswordAuth fromSecret = JwtPasswordAuth.fromSecret(vertx, SECRET);
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, "testuser")
                .put(AuthTokenRest.CONTEXT, "test")
        );
        final Optional<AuthUser> result = fromSecret.user("testuser", token);
        MatcherAssert.assertThat(
            "JwtPasswordAuth created from secret should validate tokens",
            result.isPresent(),
            Matchers.is(true)
        );
    }

    @Test
    void handlesSpecialCharactersInUsername() {
        final String username = "user+tag@example.com";
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, username)
                .put(AuthTokenRest.CONTEXT, "test")
        );
        final Optional<AuthUser> result = this.auth.user(username, token);
        MatcherAssert.assertThat(
            "Should handle special characters in username",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            result.get().name(),
            Matchers.is(username)
        );
    }

    @Test
    void defaultContextWhenNotInToken() {
        final String username = "testuser";
        // Create token without explicit context
        final JwtPasswordAuth noContextAuth = new JwtPasswordAuth(this.jwtProvider, true);
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, username)
            // No CONTEXT field
        );
        final Optional<AuthUser> result = noContextAuth.user(username, token);
        MatcherAssert.assertThat(
            "Should authenticate even without context",
            result.isPresent(),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            "Should use default context when not in token",
            result.get().authContext(),
            Matchers.is("jwt-password")
        );
    }
}
