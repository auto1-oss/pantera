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
package com.auto1.pantera.auth.signin;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What can be known of a rejected API key.
 *
 * @since 2.2.15
 */
final class KeyInspectorTest {

    /**
     * Now.
     */
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    /**
     * Key pair of this installation.
     */
    private static KeyPair own;

    /**
     * Key pair of another installation.
     */
    private static KeyPair foreign;

    @BeforeAll
    static void keys() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyInspectorTest.own = gen.generateKeyPair();
        KeyInspectorTest.foreign = gen.generateKeyPair();
    }

    @Test
    void expiredKeyStillProvesItsOwner() {
        MatcherAssert.assertThat(
            KeyInspectorTest.inspector(true).inspect(
                KeyInspectorTest.token(
                    KeyInspectorTest.own, "alice", "api", KeyInspectorTest.NOW.minusSeconds(60)
                ),
                "someone-else"
            ),
            new IsEqual<>(
                Optional.of(new RejectedKey("api_key_expired", "alice", "jti-1", true))
            )
        );
    }

    @Test
    void keyThatIsNoLongerStoredIsRevoked() {
        MatcherAssert.assertThat(
            KeyInspectorTest.inspector(false).inspect(
                KeyInspectorTest.token(
                    KeyInspectorTest.own, "alice", "api", KeyInspectorTest.NOW.plusSeconds(3600)
                ),
                "alice"
            ),
            new IsEqual<>(
                Optional.of(new RejectedKey("api_key_revoked", "alice", "jti-1", true))
            )
        );
    }

    @Test
    void validKeyThatWasRefusedForAnotherCauseSaysSo() {
        MatcherAssert.assertThat(
            "its owner is disabled, or it came with another user name",
            KeyInspectorTest.inspector(true).inspect(
                KeyInspectorTest.token(
                    KeyInspectorTest.own, "alice", "api", KeyInspectorTest.NOW.plusSeconds(3600)
                ),
                "bob"
            ),
            new IsEqual<>(
                Optional.of(new RejectedKey("api_key_refused", "alice", "jti-1", true))
            )
        );
    }

    @Test
    void keyOfAnotherInstallationProvesNothing() {
        MatcherAssert.assertThat(
            "it is listed under the name it was sent with, unverified",
            KeyInspectorTest.inspector(true).inspect(
                KeyInspectorTest.token(
                    KeyInspectorTest.foreign, "admin", "api",
                    KeyInspectorTest.NOW.plusSeconds(3600)
                ),
                "alice"
            ),
            new IsEqual<>(Optional.of(new RejectedKey("api_key_unknown", "alice", "", false)))
        );
    }

    @Test
    void foreignKeySentWithoutANameIsListedUnderTheNameItClaims() {
        MatcherAssert.assertThat(
            KeyInspectorTest.inspector(true).inspect(
                KeyInspectorTest.token(
                    KeyInspectorTest.foreign, "admin", "api",
                    KeyInspectorTest.NOW.plusSeconds(3600)
                ),
                ""
            ),
            new IsEqual<>(Optional.of(new RejectedKey("api_key_unknown", "admin", "", false)))
        );
    }

    @Test
    void textThatIsNoTokenIsAnUnknownKey() {
        MatcherAssert.assertThat(
            KeyInspectorTest.inspector(true).inspect("eyJ.not.a-token", "alice"),
            new IsEqual<>(Optional.of(new RejectedKey("api_key_unknown", "alice", "", false)))
        );
    }

    @Test
    void expiredSessionIsNotARejectedApiKey() {
        MatcherAssert.assertThat(
            "a browser session that ran out is not what the report is about",
            KeyInspectorTest.inspector(true).inspect(
                KeyInspectorTest.token(
                    KeyInspectorTest.own, "alice", "access", KeyInspectorTest.NOW.minusSeconds(60)
                ),
                "alice"
            ),
            new IsEqual<>(Optional.empty())
        );
    }

    private static KeyInspector inspector(final boolean stored) {
        return new KeyInspector(
            (RSAPublicKey) KeyInspectorTest.own.getPublic(),
            (jti, owner) -> stored, () -> KeyInspectorTest.NOW
        );
    }

    private static String token(
        final KeyPair pair, final String subject, final String type, final Instant expires
    ) {
        return JWT.create()
            .withSubject(subject)
            .withJWTId("jti-1")
            .withClaim("type", type)
            .withExpiresAt(expires)
            .sign(
                Algorithm.RSA256(
                    (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate()
                )
            );
    }
}
