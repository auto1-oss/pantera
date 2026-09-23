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
import com.auto1.pantera.http.auth.TokenAuthentication;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsInstanceOf;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link JwtTokens}.
 * @since 2.1.0
 */
class JwtTokensTest {

    /**
     * RSA private key for signing test tokens.
     */
    private RSAPrivateKey privateKey;

    /**
     * RSA public key for verification.
     */
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        this.privateKey = (RSAPrivateKey) kp.getPrivate();
        this.publicKey = (RSAPublicKey) kp.getPublic();
    }

    @Test
    void returnsAuth() {
        MatcherAssert.assertThat(
            new JwtTokens(this.privateKey, this.publicKey, null, null, null).auth(),
            new IsInstanceOf(UnifiedJwtAuthHandler.class)
        );
    }

    @Test
    void userRevocationRejectsEveryEarlierTokenEvenInTheSameSecond() {
        // B45: a session issued in the same second as a revocation (password
        // change, admin revoke) survived it and could mint API tokens.
        final java.util.Map<String, UserRevocation> revs =
            new java.util.concurrent.ConcurrentHashMap<>();
        final RevocationBlocklist blocklist = new RevocationBlocklist() {
            @Override
            public boolean isRevokedJti(final String jti) {
                return false;
            }

            @Override
            public boolean isRevokedUser(final String username, final java.time.Instant issued) {
                final UserRevocation rev = revs.get(username);
                return rev != null && rev.revokes(issued, java.time.Instant.now());
            }

            @Override
            public void revokeJti(final String jti, final int ttl) {
                // not exercised
            }

            @Override
            public void revokeUser(final String username, final int ttl) {
                final java.time.Instant now = java.time.Instant.now();
                revs.put(username, new UserRevocation(now, now.plusSeconds(ttl)));
            }
        };
        final JwtTokens tokens =
            new JwtTokens(this.privateKey, this.publicKey, null, null, blocklist);
        final AuthUser alice = new AuthUser("alice", "local");
        final String before = tokens.generate(alice);
        blocklist.revokeUser("alice", 3600);
        final String after = tokens.generate(alice);
        MatcherAssert.assertThat(
            "the session issued before the revocation must be rejected",
            tokens.auth().user(before).toCompletableFuture().join().isPresent(),
            new org.hamcrest.core.IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the login right after the revocation must be accepted",
            tokens.auth().user(after).toCompletableFuture().join().isPresent(),
            new org.hamcrest.core.IsEqual<>(true)
        );
    }

    @Test
    void generatesToken() {
        MatcherAssert.assertThat(
            new JwtTokens(this.privateKey, this.publicKey, null, null, null)
                .generate(new AuthUser("Oleg", "test")),
            new IsNot<>(Matchers.emptyString())
        );
    }

}
