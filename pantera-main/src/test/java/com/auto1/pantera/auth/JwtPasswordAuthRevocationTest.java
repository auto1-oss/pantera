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

import com.auto1.pantera.http.auth.AuthUser;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for SecOps finding #40 (token-revocation): a JWT
 * used as a Basic-auth repository password must be subjected to the SAME
 * revocation / token-type checks as a Bearer token.
 *
 * <p>Before 2.2.9 {@link JwtPasswordAuth} only verified the RS256 signature
 * and (optionally) the subject/username match — it never consulted the
 * revocation blocklist, the {@code user_tokens} JTI ownership record, or the
 * enabled-state gate, and it accepted any token type. So a revoked or
 * explicitly-blocklisted API token (even a permanent one) kept authorizing
 * repository reads/uploads/deletes as a password, and a refresh token was
 * usable as a repository credential (type confusion).</p>
 *
 * <p>These tests mint real RS256 tokens and inject a {@link PasswordTokenGate}
 * standing in for the runtime revocation registry; the gate must actually be
 * consulted before {@code JwtPasswordAuth} returns a principal.</p>
 *
 * @since 2.2.9
 */
final class JwtPasswordAuthRevocationTest {

    private Vertx vertx;
    private JWTAuth jwtAuth;

    @BeforeEach
    void setUp() throws Exception {
        this.vertx = Vertx.vertx();
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair pair = gen.generateKeyPair();
        final String publicPem = "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n";
        final String privatePem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        this.jwtAuth = JWTAuth.create(this.vertx, new JWTAuthOptions()
            .addPubSecKey(new PubSecKeyOptions().setAlgorithm("RS256").setBuffer(publicPem))
            .addPubSecKey(new PubSecKeyOptions().setAlgorithm("RS256").setBuffer(privatePem)));
    }

    @AfterEach
    void tearDown() {
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    private String mint(final String sub, final String type, final String jti) {
        return this.jwtAuth.generateToken(
            new JsonObject().put("sub", sub).put("type", type)
                .put("jti", jti).put("context", "test"),
            new JWTOptions().setAlgorithm("RS256")
        );
    }

    @Test
    void revokedApiTokenIsRejectedAsPassword() {
        // Gate that has "REVOKED" blocklisted — mirrors a token the admin
        // revoked, whose signature still verifies.
        final PasswordTokenGate gate = (type, jti, sub) -> !"REVOKED".equals(jti);
        final JwtPasswordAuth auth = new JwtPasswordAuth(this.jwtAuth, true, gate);
        final Optional<AuthUser> result = auth.user("alice", this.mint("alice", "api", "REVOKED"));
        MatcherAssert.assertThat(
            "a revoked API token must not authorize as a Basic-auth password",
            result.isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void validApiTokenIsStillAccepted() {
        final PasswordTokenGate gate = (type, jti, sub) -> true;
        final JwtPasswordAuth auth = new JwtPasswordAuth(this.jwtAuth, true, gate);
        final Optional<AuthUser> result = auth.user("alice", this.mint("alice", "api", "OK"));
        MatcherAssert.assertThat(
            "a valid, non-revoked API token must still authorize",
            result.isPresent(), new IsEqual<>(true)
        );
    }

    @Test
    void gateRejectionDeniesEvenAValidSignature() {
        // A gate that rejects everything (e.g. user disabled) must veto a
        // perfectly-signed token — proving the gate is actually consulted.
        final PasswordTokenGate deny = (type, jti, sub) -> false;
        final JwtPasswordAuth auth = new JwtPasswordAuth(this.jwtAuth, true, deny);
        final Optional<AuthUser> result = auth.user("alice", this.mint("alice", "api", "OK"));
        MatcherAssert.assertThat(
            "the revocation gate must be able to deny a validly-signed token",
            result.isPresent(), new IsEqual<>(false)
        );
    }
}
