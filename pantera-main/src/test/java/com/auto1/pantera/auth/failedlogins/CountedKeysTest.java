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
package com.auto1.pantera.auth.failedlogins;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auto1.pantera.auth.signin.InMemorySignInFailureStore;
import com.auto1.pantera.auth.signin.KeyInspector;
import com.auto1.pantera.auth.signin.SignInFailure;
import com.auto1.pantera.auth.signin.SignInFailureStore;
import com.auto1.pantera.auth.signin.SignInFailures;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import com.auto1.pantera.settings.policy.SignInReportConfig;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A rejected API key is reported with what is known of it, the client is
 * told why, and it counts towards the lock when the policy says so.
 *
 * @since 2.2.15
 */
final class CountedKeysTest {

    /**
     * A repository client.
     */
    private static final LoginSource IDE =
        new LoginSource("192.0.2.7", "IntelliJ IDEA/2026.2", LoginSource.REPOSITORY);

    /**
     * Key pair of the installation.
     */
    private static KeyPair pair;

    /**
     * The policy.
     */
    private AtomicReference<FailedLoginsConfig> policy;

    /**
     * Failed logins.
     */
    private FailedLogins logins;

    /**
     * The report.
     */
    private SignInFailures report;

    @BeforeAll
    static void keys() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        CountedKeysTest.pair = gen.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        this.policy = new AtomicReference<>(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false));
        this.logins = new FailedLogins(new InMemoryFailedLoginStore(), this.policy::get);
        this.report = new SignInFailures(
            new InMemorySignInFailureStore(), SignInReportConfig::defaults
        );
        this.report.inspectKeysWith(
            new KeyInspector((RSAPublicKey) CountedKeysTest.pair.getPublic(), (jti, owner) -> true)
        );
        this.logins.reportTo(this.report);
    }

    @Test
    void rejectedKeyIsReportedAndTheClientIsToldWhy() {
        final PasswordCheck.Outcome outcome = this.check(
            (name, key) -> Optional.empty(), CountedKeysTest.expired("alice")
        );
        MatcherAssert.assertThat(
            "the answer is a 401 that names the reason",
            outcome.refusal().map(why -> why.status() + " " + why.reason()),
            new IsEqual<>(Optional.of(RsStatus.UNAUTHORIZED + " api_key_expired"))
        );
        final SignInFailure line = this.reported().get(0);
        MatcherAssert.assertThat(
            "the report names the owner, the key and where the request came from",
            List.of(
                line.username(), line.tokenId(), line.reason(), line.clientIp(),
                line.userAgent(), String.valueOf(line.verified())
            ),
            new IsEqual<>(
                List.of(
                    "alice", "jti-1", "api_key_expired", "192.0.2.7", "IntelliJ IDEA/2026.2",
                    "true"
                )
            )
        );
    }

    @Test
    void rejectedKeyIsNotAFailedLoginUnlessThePolicySaysSo() {
        this.check((name, key) -> Optional.empty(), CountedKeysTest.expired("alice"));
        MatcherAssert.assertThat(
            this.logins.state("alice").failures(), new IsEqual<>(0)
        );
    }

    @Test
    void rejectedKeysLockWhenThePolicyCountsThemAndTheLockIsWhatTheClientIsTold() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, true));
        PasswordCheck.Outcome last = null;
        for (int idx = 0; idx < 3; idx += 1) {
            last = this.check((name, key) -> Optional.empty(), CountedKeysTest.expired("alice"));
        }
        MatcherAssert.assertThat(
            "the third rejected key locks, and the answer says locked",
            last.refusal().map(AuthRefusal::reason), new IsEqual<>(Optional.of("account_locked"))
        );
        MatcherAssert.assertThat(
            "all three are in the report as rejected keys",
            this.reported().get(0).hits(), new IsEqual<>(3L)
        );
    }

    @Test
    void keyRefusedBecauseItsUserIsLockedIsNotARejectedKey() {
        final PasswordCheck.Outcome outcome = this.check(
            (name, key) -> {
                new AuthRefusal("account_locked", RsStatus.FORBIDDEN, 0L, "Account locked").note();
                return Optional.empty();
            },
            CountedKeysTest.expired("alice")
        );
        MatcherAssert.assertThat(
            "the lock stays the reason",
            outcome.refusal().map(AuthRefusal::reason), new IsEqual<>(Optional.of("account_locked"))
        );
        MatcherAssert.assertThat("nothing is reported", this.reported().size(), new IsEqual<>(0));
    }

    @Test
    void acceptedKeyIsNotReported() {
        final PasswordCheck.Outcome outcome = this.check(
            (name, key) -> Optional.of(new AuthUser(name, "jwt-password")),
            CountedKeysTest.expired("alice")
        );
        MatcherAssert.assertThat("the user", outcome.user().isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat("nothing is reported", this.reported().size(), new IsEqual<>(0));
    }

    private PasswordCheck.Outcome check(
        final com.auto1.pantera.http.auth.Authentication provider, final String key
    ) {
        return new PasswordCheck(
            new CountedKeys(provider, this.logins, this.report), CountedKeysTest.IDE
        ).now("alice", key);
    }

    private List<SignInFailure> reported() {
        return this.report.list(
            new SignInFailureStore.Query(SignInFailure.API_KEY, "", false, "", 10, 0)
        ).items();
    }

    private static String expired(final String owner) {
        return JWT.create()
            .withSubject(owner)
            .withJWTId("jti-1")
            .withClaim("type", "api")
            .withExpiresAt(Instant.now().minusSeconds(60))
            .sign(
                Algorithm.RSA256(
                    (RSAPublicKey) CountedKeysTest.pair.getPublic(),
                    (RSAPrivateKey) CountedKeysTest.pair.getPrivate()
                )
            );
    }
}
