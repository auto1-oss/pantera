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
import com.auto1.pantera.auth.signin.InMemorySignInFailureStore;
import com.auto1.pantera.auth.signin.KeyInspector;
import com.auto1.pantera.auth.signin.SignInFailure;
import com.auto1.pantera.auth.signin.SignInFailureStore;
import com.auto1.pantera.auth.signin.SignInFailures;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.settings.policy.SignInReportConfig;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An API key rejected as a Bearer token is reported with its owner, and
 * the answer says why.
 *
 * @since 2.2.15
 */
final class RejectedBearerKeyTest {

    /**
     * Signs tokens of the installation.
     */
    private Algorithm algorithm;

    /**
     * The report.
     */
    private SignInFailures report;

    /**
     * Token check under test.
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
        this.report = new SignInFailures(
            new InMemorySignInFailureStore(), SignInReportConfig::defaults
        );
        this.report.inspectKeysWith(
            new KeyInspector((RSAPublicKey) pair.getPublic(), null)
        );
        this.handler = new UnifiedJwtAuthHandler(
            (RSAPublicKey) pair.getPublic(), null, null, UserEnabledCheck.ALWAYS_ENABLED,
            this.report
        );
        AuthRefusal.take();
    }

    @Test
    void expiredKeyIsReportedWithItsOwnerAndWhereItCameFrom() {
        final PasswordCheck.Outcome outcome = this.handler.check(
            this.token("alice", "api", -60L),
            Headers.from(
                List.of(
                    new Header("X-Pantera-Ctx-Client-Ip", "192.0.2.7"),
                    new Header("User-Agent", "Apache-Maven/3.9.9")
                )
            )
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "the answer names the reason",
            outcome.refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("api_key_expired"))
        );
        final SignInFailure line = this.keys().get(0);
        MatcherAssert.assertThat(
            "the report names the owner, the key, the address, the program and the path",
            List.of(
                line.username(), line.tokenId(), line.clientIp(), line.userAgent(), line.path()
            ),
            new IsEqual<>(
                List.of(
                    "alice", "00000000-0000-0000-0000-000000000001", "192.0.2.7",
                    "Apache-Maven/3.9.9", LoginSource.REPOSITORY
                )
            )
        );
    }

    @Test
    void expiredSessionIsAPlainFailedLogin() {
        final PasswordCheck.Outcome outcome = this.handler.check(
            this.token("alice", "access", -60L), Headers.EMPTY
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "no reason of a key", outcome.refusal().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat("nothing is reported", this.keys().size(), new IsEqual<>(0));
    }

    @Test
    void acceptedKeyIsNotReported() {
        final PasswordCheck.Outcome outcome = this.handler.check(
            this.token("alice", "access", 3600L), Headers.EMPTY
        ).toCompletableFuture().join();
        MatcherAssert.assertThat("the user", outcome.user().isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat("nothing is reported", this.keys().size(), new IsEqual<>(0));
    }

    @Test
    void rejectedKeyOnTheApiIsReportedByItsCaller() {
        final Optional<com.auto1.pantera.auth.signin.RejectedKey> key = this.handler.rejected(
            this.token("alice", "api", -60L), new LoginSource("192.0.2.9", "curl/8.7", "api")
        );
        MatcherAssert.assertThat(
            "what is known of the key",
            key.map(com.auto1.pantera.auth.signin.RejectedKey::reason),
            new IsEqual<>(Optional.of("api_key_expired"))
        );
        MatcherAssert.assertThat(
            "and it is in the report with the path it came by",
            this.keys().get(0).path(), new IsEqual<>("api")
        );
    }

    private List<SignInFailure> keys() {
        return this.report.list(
            new SignInFailureStore.Query(SignInFailure.API_KEY, "", false, "", 10, 0)
        ).items();
    }

    private String token(final String subject, final String type, final long ttl) {
        return JWT.create()
            .withSubject(subject)
            .withClaim("context", "test")
            .withClaim("type", type)
            .withJWTId("00000000-0000-0000-0000-000000000001")
            .withExpiresAt(Instant.now().plusSeconds(ttl))
            .sign(this.algorithm);
    }
}
