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
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.PasswordCheck;
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
 * A locked user's token is refused as locked, on every path that checks a
 * token: a Bearer token of a repository request, an API key sent as a
 * password, a session of the management API.
 *
 * @since 2.2.15
 */
final class TokenOfLockedUserTest {

    /**
     * Signing algorithm.
     */
    private Algorithm algorithm;

    /**
     * Handler of an installation in which alice is locked.
     */
    private UnifiedJwtAuthHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair pair = gen.generateKeyPair();
        this.algorithm = Algorithm.RSA256(
            (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate()
        );
        this.handler = new UnifiedJwtAuthHandler(
            (RSAPublicKey) pair.getPublic(), null, null,
            new UserEnabledCheck() {
                @Override
                public boolean isEnabled(final String username) {
                    return true;
                }

                @Override
                public boolean isLocked(final String username) {
                    return "alice".equals(username);
                }
            }
        );
        AuthRefusal.take();
    }

    @Test
    void tokenOfALockedUserIsRefusedAsLocked() {
        final PasswordCheck.Outcome outcome =
            this.handler.check(this.token("alice", 3600L)).toCompletableFuture().join();
        final AuthRefusal refusal = outcome.refusal().orElseThrow();
        MatcherAssert.assertThat(
            "the token signs nobody in", outcome.user().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the answer is 403", refusal.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the reason is the lock", refusal.reason(), new IsEqual<>("account_locked")
        );
        MatcherAssert.assertThat(
            "and the text says so", refusal.message(), new IsEqual<>("Account locked")
        );
    }

    @Test
    void lockedUsersTokenOpensNoRepositoryAndNoApiSession() {
        final String token = this.token("alice", 3600L);
        MatcherAssert.assertThat(
            "repository path",
            this.handler.user(token).toCompletableFuture().join().isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "management API path",
            this.handler.validated(token).isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the API can tell the lock from an invalid token",
            this.handler.ownerLocked(token), new IsEqual<>(true)
        );
    }

    @Test
    void tokenOfAnotherUserIsAccepted() {
        final PasswordCheck.Outcome outcome =
            this.handler.check(this.token("bob", 3600L)).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "bob signs in",
            outcome.user().map(AuthUser::name), new IsEqual<>(Optional.of("bob"))
        );
        MatcherAssert.assertThat(
            "bob's token is not a locked one",
            this.handler.ownerLocked(this.token("bob", 3600L)), new IsEqual<>(false)
        );
    }

    @Test
    void tokenThatCannotBeVerifiedIsAPlainFailedLogin() {
        MatcherAssert.assertThat(
            "an expired token of a locked user says nothing about the lock",
            this.handler.check(this.token("alice", -60L)).toCompletableFuture().join()
                .refusal().isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "nor does a string that is no token",
            this.handler.check("not-a-token").toCompletableFuture().join()
                .refusal().isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and neither names a locked owner",
            this.handler.ownerLocked("not-a-token") || this.handler.ownerLocked(this.token("alice", -60L)),
            new IsEqual<>(false)
        );
    }

    @Test
    void checkLeavesNothingOnTheCallingThread() {
        this.handler.check(this.token("alice", 3600L)).toCompletableFuture().join();
        this.handler.validated(this.token("alice", 3600L));
        MatcherAssert.assertThat(
            AuthRefusal.take().isPresent(), new IsEqual<>(false)
        );
    }

    private String token(final String subject, final long ttl) {
        return JWT.create()
            .withSubject(subject)
            .withClaim("context", "test")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000001")
            .withExpiresAt(Instant.now().plusSeconds(ttl))
            .sign(this.algorithm);
    }
}
