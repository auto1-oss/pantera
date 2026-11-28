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
import com.auto1.pantera.auth.failedlogins.FailedLogins;
import com.auto1.pantera.auth.failedlogins.InMemoryFailedLoginStore;
import com.auto1.pantera.auth.failedlogins.LockedName;
import com.auto1.pantera.auth.signin.InMemorySignInFailureStore;
import com.auto1.pantera.auth.signin.KeyInspector;
import com.auto1.pantera.auth.signin.SignInFailures;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.http.headers.Header;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A rejected API key sent as a Bearer token counts as a failed login of its
 * owner when the policy says so, as one sent as a password does: npm, for
 * one, sends its stored key that way.
 *
 * @since 2.2.15
 */
final class CountedBearerKeysTest {

    /**
     * Request of a package manager on a repository path.
     */
    private static final Headers NPM = Headers.from(
        List.of(
            new Header("X-Pantera-Ctx-Client-Ip", "192.0.2.7"),
            new Header("User-Agent", "npm/11.18.0")
        )
    );

    /**
     * Key pair of the installation.
     */
    private KeyPair pair;

    /**
     * Failed-logins policy, changed by the tests.
     */
    private AtomicReference<FailedLoginsConfig> policy;

    /**
     * Failed logins.
     */
    private FailedLogins logins;

    /**
     * Token check under test.
     */
    private UnifiedJwtAuthHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        this.pair = gen.generateKeyPair();
        final SignInFailures report = new SignInFailures(
            new InMemorySignInFailureStore(), SignInReportConfig::defaults
        );
        report.inspectKeysWith(new KeyInspector((RSAPublicKey) this.pair.getPublic(), null));
        this.policy = new AtomicReference<>(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, true));
        this.logins = new FailedLogins(new InMemoryFailedLoginStore(), this.policy::get);
        this.logins.reportTo(report);
        this.handler = new UnifiedJwtAuthHandler(
            (RSAPublicKey) this.pair.getPublic(), null, null, UserEnabledCheck.ALWAYS_ENABLED,
            report, this.logins
        );
        AuthRefusal.take();
    }

    @Test
    void rejectedBearerKeyIsAFailedLoginOfItsOwner() {
        final PasswordCheck.Outcome outcome = this.check(this.expiredKey());
        MatcherAssert.assertThat(
            "the client is told why the key was rejected",
            outcome.refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("api_key_expired"))
        );
        MatcherAssert.assertThat(
            "it is a failed login of the key's owner",
            this.logins.state("alice").failures(), new IsEqual<>(1)
        );
    }

    @Test
    void thirdRejectedBearerKeyLocksItsOwner() {
        this.check(this.expiredKey());
        this.check(this.expiredKey());
        final Optional<AuthRefusal> third = this.check(this.expiredKey()).refusal();
        MatcherAssert.assertThat(
            "the third is answered as a lock",
            List.of(
                third.map(AuthRefusal::reason).orElse(""),
                third.map(AuthRefusal::status).orElse(RsStatus.OK)
            ),
            new IsEqual<>(List.of("account_locked", RsStatus.FORBIDDEN))
        );
        final LockedName locked = this.logins.lockedNames().get(0);
        MatcherAssert.assertThat(
            "the lock names the owner, the key, the client and the path",
            List.of(
                locked.name(), locked.origin().credential(), locked.origin().clientIp(),
                locked.origin().userAgent(), locked.origin().path()
            ),
            new IsEqual<>(
                List.of("alice", "api_key", "192.0.2.7", "npm/11.18.0", LoginSource.REPOSITORY)
            )
        );
    }

    @Test
    void keyOfALockedOwnerIsRefusedAsLockedAndNotCountedAgain() {
        Optional<AuthRefusal> last = Optional.empty();
        for (int idx = 0; idx < 6; idx += 1) {
            last = this.check(this.expiredKey()).refusal();
        }
        MatcherAssert.assertThat(
            "a tool that repeats the key does not write the count again",
            this.logins.state("alice").failures(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "the lock blocks every credential, so the answer is the lock",
            last.map(AuthRefusal::reason), new IsEqual<>(Optional.of("account_locked"))
        );
    }

    @Test
    void keyOfAnOwnerLockedForPasswordsOnlyIsNotCountedAgain() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, true, false));
        Optional<AuthRefusal> last = Optional.empty();
        for (int idx = 0; idx < 6; idx += 1) {
            last = this.check(this.expiredKey()).refusal();
        }
        MatcherAssert.assertThat(
            "the count stops at the lock",
            this.logins.state("alice").failures(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "the lock does not block keys, so the answer is the key's reason",
            last.map(AuthRefusal::reason), new IsEqual<>(Optional.of("api_key_expired"))
        );
    }

    @Test
    void rejectedBearerKeyIsNotCountedByDefault() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false));
        Optional<AuthRefusal> last = Optional.empty();
        for (int idx = 0; idx < 5; idx += 1) {
            last = this.check(this.expiredKey()).refusal();
        }
        MatcherAssert.assertThat(
            "nothing is counted", this.logins.state("alice").failures(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the client is told why",
            last.map(AuthRefusal::reason), new IsEqual<>(Optional.of("api_key_expired"))
        );
    }

    @Test
    void keyThisInstallationDidNotSignCountsForNobody() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        final KeyPair other = gen.generateKeyPair();
        final String forged = this.key(
            Algorithm.RSA256((RSAPublicKey) other.getPublic(), (RSAPrivateKey) other.getPrivate()),
            "api", 3600L
        );
        for (int idx = 0; idx < 3; idx += 1) {
            this.check(forged);
        }
        MatcherAssert.assertThat(
            "a token that proves no owner is no failed login of the name it claims",
            this.logins.state("alice").failures(), new IsEqual<>(0)
        );
    }

    @Test
    void expiredSessionIsNotCounted() {
        this.check(this.key(this.algorithm(), "access", -60L));
        MatcherAssert.assertThat(
            this.logins.state("alice").failures(), new IsEqual<>(0)
        );
    }

    @Test
    void rejectedKeyOnTheApiCountsToo() {
        final LoginSource api = new LoginSource("192.0.2.9", "curl/8.7", "api");
        this.handler.rejected(this.expiredKey(), api);
        this.handler.rejected(this.expiredKey(), api);
        final Optional<AuthRefusal> third = this.handler.rejected(this.expiredKey(), api);
        MatcherAssert.assertThat(
            "the third locks and says so",
            third.map(AuthRefusal::reason), new IsEqual<>(Optional.of("account_locked"))
        );
        MatcherAssert.assertThat(
            "the lock names the path", this.logins.lockedNames().get(0).origin().path(),
            new IsEqual<>("api")
        );
        MatcherAssert.assertThat(
            "nothing is left noted on the thread",
            AuthRefusal.noted().isPresent(), new IsEqual<>(false)
        );
    }

    private PasswordCheck.Outcome check(final String token) {
        return this.handler.check(token, CountedBearerKeysTest.NPM)
            .toCompletableFuture().join();
    }

    private String expiredKey() {
        return this.key(this.algorithm(), "api", -60L);
    }

    private Algorithm algorithm() {
        return Algorithm.RSA256(
            (RSAPublicKey) this.pair.getPublic(), (RSAPrivateKey) this.pair.getPrivate()
        );
    }

    private String key(final Algorithm signer, final String type, final long ttl) {
        return JWT.create()
            .withSubject("alice")
            .withClaim("context", "test")
            .withClaim("type", type)
            .withJWTId("00000000-0000-0000-0000-000000000001")
            .withExpiresAt(Instant.now().plusSeconds(ttl))
            .sign(signer);
    }
}
