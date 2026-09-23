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
import com.auto1.pantera.http.auth.AuthUser;
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
 * Tests for {@link UnifiedJwtAuthHandler}.
 * @since 2.1.0
 */
class UnifiedJwtAuthHandlerTest {

    /**
     * RSA private key for signing test tokens.
     */
    private RSAPrivateKey privateKey;

    /**
     * RSA public key for verification.
     */
    private RSAPublicKey publicKey;

    /**
     * Auth0 algorithm instance used to sign test tokens.
     */
    private Algorithm algorithm;

    /**
     * Handler under test (no DAO, no blocklist).
     */
    private UnifiedJwtAuthHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair kp = gen.generateKeyPair();
        this.privateKey = (RSAPrivateKey) kp.getPrivate();
        this.publicKey = (RSAPublicKey) kp.getPublic();
        this.algorithm = Algorithm.RSA256(this.publicKey, this.privateKey);
        this.handler = new UnifiedJwtAuthHandler(this.publicKey, null, null);
    }

    @Test
    void validAccessTokenReturnsUser() {
        final String username = "alice";
        final String context = "jwt";
        final String token = JWT.create()
            .withSubject(username)
            .withClaim("context", context)
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000001")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        final Optional<AuthUser> result =
            this.handler.user(token).toCompletableFuture().join();
        MatcherAssert.assertThat(
            result.isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            result.get(),
            new IsEqual<>(new AuthUser(username, context))
        );
    }

    @Test
    void tokenWithoutTypeClaimIsRejected() {
        final String token = JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withJWTId("00000000-0000-0000-0000-000000000002")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        MatcherAssert.assertThat(
            this.handler.user(token).toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void tokenWithoutJtiIsRejected() {
        final String token = JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withClaim("type", "access")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        MatcherAssert.assertThat(
            this.handler.user(token).toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void expiredTokenIsRejected() {
        final String token = JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000003")
            .withExpiresAt(Instant.now().minusSeconds(60))
            .sign(this.algorithm);
        MatcherAssert.assertThat(
            this.handler.user(token).toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair other = gen.generateKeyPair();
        final Algorithm otherAlg = Algorithm.RSA256(
            (RSAPublicKey) other.getPublic(),
            (RSAPrivateKey) other.getPrivate()
        );
        final String token = JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000004")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(otherAlg);
        MatcherAssert.assertThat(
            this.handler.user(token).toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void invalidTokenStringIsRejected() {
        MatcherAssert.assertThat(
            this.handler.user("not.a.jwt").toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    // -----------------------------------------------------------
    // Enabled-state gate (added in 2.1.0 to close the "disabled
    // user can still use an existing session/API token" bug)
    // -----------------------------------------------------------

    @Test
    void validTokenIsRejectedWhenUserIsDisabled() {
        // Any technically-valid token must fail validation when the
        // configured UserEnabledCheck says the subject is disabled.
        // This is how admin disable takes effect on existing sessions
        // without waiting for the JWT to expire.
        final UnifiedJwtAuthHandler disabledHandler = new UnifiedJwtAuthHandler(
            this.publicKey, null, null, username -> false
        );
        final String token = JWT.create()
            .withSubject("disabled-user")
            .withClaim("context", "jwt")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000005")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        MatcherAssert.assertThat(
            disabledHandler.user(token).toCompletableFuture().join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void enabledCheckIsNotConsultedForInvalidTokens() {
        // The enabled check must only run AFTER all the cheap static
        // checks (signature, required claims, expiry) have passed.
        // If an expired token consulted the DB on every request we
        // would trivially DoS the DB from any unauthenticated client.
        final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();
        final UnifiedJwtAuthHandler counted = new UnifiedJwtAuthHandler(
            this.publicKey, null, null, username -> {
                calls.incrementAndGet();
                return true;
            }
        );
        final String expired = JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000006")
            .withExpiresAt(Instant.now().minusSeconds(60))
            .sign(this.algorithm);
        counted.user(expired).toCompletableFuture().join();
        MatcherAssert.assertThat(calls.get(), new IsEqual<>(0));
    }

    @Test
    void enabledCheckCalledForValidToken() {
        final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();
        final UnifiedJwtAuthHandler counted = new UnifiedJwtAuthHandler(
            this.publicKey, null, null, username -> {
                calls.incrementAndGet();
                return true;
            }
        );
        final String token = JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000007")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        final Optional<AuthUser> result =
            counted.user(token).toCompletableFuture().join();
        MatcherAssert.assertThat(result.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(calls.get(), new IsEqual<>(1));
    }

    @Test
    void defaultEnabledCheckIsAlwaysEnabled() {
        // The legacy 3-arg ctor must default to ALWAYS_ENABLED so
        // existing tests and deployments that don't wire the check
        // continue to work unchanged.
        final UnifiedJwtAuthHandler legacy = new UnifiedJwtAuthHandler(
            this.publicKey, null, null
        );
        final String token = JWT.create()
            .withSubject("alice")
            .withClaim("context", "jwt")
            .withClaim("type", "access")
            .withJWTId("00000000-0000-0000-0000-000000000008")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
        MatcherAssert.assertThat(
            legacy.user(token).toCompletableFuture().join().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void userRevocationRejectsOldSessionButNotTheLoginAfterIt() {
        // Password change: every session issued before it is revoked, and
        // the user's re-login right afterwards must work (2.2.9 regression:
        // the user-wide entry rejected every token for 7 days).
        final InMemoryBlocklist blocklist = new InMemoryBlocklist();
        final UnifiedJwtAuthHandler guarded =
            new UnifiedJwtAuthHandler(this.publicKey, null, blocklist);
        final String before = this.accessToken("bob", Instant.now().minusSeconds(120));
        blocklist.revokeUser("bob", 3600);
        final String after = this.accessToken("bob", Instant.now());
        MatcherAssert.assertThat(
            "Session issued before the revocation must be rejected",
            guarded.user(before).toCompletableFuture().join().isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Login after the revocation must be accepted",
            guarded.user(after).toCompletableFuture().join().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void sameSecondTokenIssuedBeforeTheRevocationIsRejected() {
        // B45: iat has one-second resolution; a token issued earlier in the
        // same second as the revocation survived it. The millisecond
        // issue-time claim closes the gap without rejecting the re-login.
        final InMemoryBlocklist blocklist = new InMemoryBlocklist();
        final UnifiedJwtAuthHandler guarded =
            new UnifiedJwtAuthHandler(this.publicKey, null, blocklist);
        final Instant revoked = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .plusMillis(500);
        blocklist.put("erin", new UserRevocation(revoked, Instant.now().plusSeconds(3600)));
        MatcherAssert.assertThat(
            "a token issued 300 ms before the revocation must be rejected",
            guarded.user(this.accessToken("erin", revoked.minusMillis(300)))
                .toCompletableFuture().join().isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a token issued 200 ms after the revocation must be accepted",
            guarded.user(this.accessToken("erin", revoked.plusMillis(200)))
                .toCompletableFuture().join().isPresent(),
            new IsEqual<>(true)
        );
    }

    private String accessToken(final String sub, final Instant issuedAt) {
        return JWT.create()
            .withSubject(sub)
            .withClaim("context", "local")
            .withClaim("type", "access")
            .withJWTId(java.util.UUID.randomUUID().toString())
            .withIssuedAt(issuedAt)
            .withClaim("iat_ms", issuedAt.toEpochMilli())
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(this.algorithm);
    }

    /**
     * Blocklist over {@link UserRevocation} without Valkey or a database.
     */
    private static final class InMemoryBlocklist implements RevocationBlocklist {
        private final java.util.Map<String, UserRevocation> users =
            new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public boolean isRevokedJti(final String jti) {
            return false;
        }

        @Override
        public boolean isRevokedUser(final String username, final Instant issuedAt) {
            final UserRevocation rev = this.users.get(username);
            return rev != null && rev.revokes(issuedAt, Instant.now());
        }

        @Override
        public void revokeJti(final String jti, final int ttlSeconds) {
            // not exercised
        }

        @Override
        public void revokeUser(final String username, final int ttlSeconds) {
            final Instant now = Instant.now();
            this.users.put(username, new UserRevocation(now, now.plusSeconds(ttlSeconds)));
        }

        void put(final String username, final UserRevocation rev) {
            this.users.put(username, rev);
        }
    }
}
