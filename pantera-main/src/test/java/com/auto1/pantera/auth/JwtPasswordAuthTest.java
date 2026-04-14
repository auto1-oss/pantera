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

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JwtPasswordAuth} against the production RS256 key pair.
 *
 * <p>Exercises the same wiring {@link JwtPasswordAuthFactory} assembles at
 * runtime: an RSA key pair loaded from PEM fixtures, a Vert.x {@link JWTAuth}
 * configured with {@code RS256} keys, and a {@link JwtPasswordAuth} that
 * verifies tokens against it. Tokens are minted here via the same
 * {@code JWTAuth} instance so a sign/verify key mismatch — the class of bug
 * that broke UI-generated API tokens in 2.1.0/2.1.1 — cannot hide.
 *
 * @since 1.20.7
 */
class JwtPasswordAuthTest {

    private static final String FIXTURES = "auth/rsa/";

    private static Vertx vertx;

    /** Vert.x provider configured with the RSA key pair; used to mint and verify. */
    private JWTAuth jwtProvider;

    /** JwtPasswordAuth under test. */
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
    void setUp() throws Exception {
        this.jwtProvider = JWTAuth.create(
            vertx,
            new JWTAuthOptions()
                .addPubSecKey(
                    new PubSecKeyOptions()
                        .setAlgorithm("RS256")
                        .setBuffer(readResource("pub-2048.pem"))
                )
                .addPubSecKey(
                    new PubSecKeyOptions()
                        .setAlgorithm("RS256")
                        .setBuffer(readResource("priv-2048-pkcs8.pem"))
                )
        );
        this.auth = new JwtPasswordAuth(this.jwtProvider);
    }

    @Test
    void authenticatesWithValidJwtAsPassword() {
        final String username = "testuser@example.com";
        final String token = mint(username, "okta");
        final Optional<AuthUser> result = this.auth.user(username, token);
        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        MatcherAssert.assertThat(result.get().name(), Matchers.is(username));
        MatcherAssert.assertThat(result.get().authContext(), Matchers.is("okta"));
    }

    @Test
    void rejectsWhenUsernameDoesNotMatchTokenSubject() {
        final String token = mint("realuser@example.com", "test");
        final Optional<AuthUser> result = this.auth.user("fakeuser@example.com", token);
        MatcherAssert.assertThat(result.isPresent(), Matchers.is(false));
    }

    @Test
    void allowsMismatchedUsernameWhenMatchingDisabled() {
        final JwtPasswordAuth noMatchAuth = new JwtPasswordAuth(this.jwtProvider, false);
        final String tokenSubject = "realuser@example.com";
        final String token = mint(tokenSubject, "test");
        final Optional<AuthUser> result = noMatchAuth.user("anyuser@example.com", token);
        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        MatcherAssert.assertThat(result.get().name(), Matchers.is(tokenSubject));
    }

    @Test
    void returnsEmptyForNonJwtPassword() {
        MatcherAssert.assertThat(
            this.auth.user("user", "regular-password").isPresent(),
            Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForNullPassword() {
        MatcherAssert.assertThat(
            this.auth.user("user", null).isPresent(), Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForEmptyPassword() {
        MatcherAssert.assertThat(
            this.auth.user("user", "").isPresent(), Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForInvalidJwt() {
        final String invalid = "eyJhbGciOiJSUzI1NiJ9.invalid.signature";
        MatcherAssert.assertThat(
            this.auth.user("user", invalid).isPresent(), Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForJwtSignedByDifferentKey() throws Exception {
        // 4096-bit fixture key pair — different key material than the 2048-bit
        // pair the auth-under-test verifies against.
        final JWTAuth otherProvider = JWTAuth.create(
            vertx,
            new JWTAuthOptions()
                .addPubSecKey(
                    new PubSecKeyOptions()
                        .setAlgorithm("RS256")
                        .setBuffer(readResource("pub-4096.pem"))
                )
                .addPubSecKey(
                    new PubSecKeyOptions()
                        .setAlgorithm("RS256")
                        .setBuffer(readResource("priv-4096-pkcs8.pem"))
                )
        );
        final String token = otherProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, "user")
                .put(AuthTokenRest.CONTEXT, "test"),
            new JWTOptions().setAlgorithm("RS256")
        );
        MatcherAssert.assertThat(
            "A token signed by an unrelated RSA key pair must not verify",
            this.auth.user("user", token).isPresent(), Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForExpiredJwt() throws Exception {
        final String token = this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, "user")
                .put(AuthTokenRest.CONTEXT, "test"),
            new JWTOptions().setAlgorithm("RS256").setExpiresInSeconds(1)
        );
        Thread.sleep(2_000L);
        MatcherAssert.assertThat(
            this.auth.user("user", token).isPresent(), Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForJwtWithoutSubClaim() {
        final String token = this.jwtProvider.generateToken(
            new JsonObject().put(AuthTokenRest.CONTEXT, "test"),
            new JWTOptions().setAlgorithm("RS256")
        );
        MatcherAssert.assertThat(
            this.auth.user("user", token).isPresent(), Matchers.is(false)
        );
    }

    @Test
    void returnsEmptyForPasswordThatLooksLikeJwtButIsNot() {
        final String fake = "eyJhbGciOiJub25lIn0.not-base64.fake";
        MatcherAssert.assertThat(
            this.auth.user("user", fake).isPresent(), Matchers.is(false)
        );
    }

    @Test
    void toStringIncludesClassName() {
        MatcherAssert.assertThat(
            this.auth.toString(), Matchers.containsString("JwtPasswordAuth")
        );
    }

    @Test
    void handlesSpecialCharactersInUsername() {
        final String username = "user+tag@example.com";
        final String token = mint(username, "test");
        final Optional<AuthUser> result = this.auth.user(username, token);
        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        MatcherAssert.assertThat(result.get().name(), Matchers.is(username));
    }

    @Test
    void defaultContextWhenNotInToken() {
        final String username = "testuser";
        final String token = this.jwtProvider.generateToken(
            new JsonObject().put(AuthTokenRest.SUB, username),
            new JWTOptions().setAlgorithm("RS256")
        );
        final Optional<AuthUser> result = this.auth.user(username, token);
        MatcherAssert.assertThat(result.isPresent(), Matchers.is(true));
        MatcherAssert.assertThat(
            result.get().authContext(), Matchers.is("jwt-password")
        );
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String mint(final String subject, final String context) {
        return this.jwtProvider.generateToken(
            new JsonObject()
                .put(AuthTokenRest.SUB, subject)
                .put(AuthTokenRest.CONTEXT, context),
            new JWTOptions().setAlgorithm("RS256")
        );
    }

    private static String readResource(final String name) throws Exception {
        final URL url = JwtPasswordAuthTest.class.getClassLoader()
            .getResource(FIXTURES + name);
        if (url == null) {
            throw new IllegalStateException("Missing test fixture: " + FIXTURES + name);
        }
        return Files.readString(Path.of(URI.create(url.toString())));
    }
}
