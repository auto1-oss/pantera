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

import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LockedOut} and {@link CountedKeys}: a locked user can
 * use no credential, and a rejected API key counts as a failed login when
 * the policy says so.
 */
final class LockedOutTest {

    /**
     * Policy in force.
     */
    private AtomicReference<FailedLoginsConfig> policy;

    /**
     * Failed logins.
     */
    private FailedLogins logins;

    /**
     * Calls that reached the wrapped chain.
     */
    private AtomicInteger calls;

    @BeforeEach
    void setUp() {
        this.policy = new AtomicReference<>(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false));
        this.logins = new FailedLogins(new InMemoryFailedLoginStore(), this.policy::get);
        this.calls = new AtomicInteger();
    }

    @Test
    void lockedUserIsRefusedWithoutAskingTheChain() {
        this.lock("alice");
        final PasswordCheck.Outcome outcome = new PasswordCheck(
            new LockedOut(this.chain(true), this.logins)
        ).now("alice", "any credential");
        final AuthRefusal refusal = outcome.refusal().orElseThrow();
        MatcherAssert.assertThat(
            "the login is refused", outcome.user().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "with 403", refusal.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "as locked", refusal.reason(), new IsEqual<>("account_locked")
        );
        MatcherAssert.assertThat(
            "and nothing behind the gate is asked", this.calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void anotherSpellingOfALockedNameIsRefusedToo() {
        this.lock("alice@example.com");
        MatcherAssert.assertThat(
            new PasswordCheck(new LockedOut(this.chain(true), this.logins))
                .now(" Alice@Example.COM", "any").refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
    }

    @Test
    void userWhoIsNotLockedPasses() {
        this.logins.failed("alice", LoginOrigin.UNKNOWN);
        MatcherAssert.assertThat(
            new PasswordCheck(new LockedOut(this.chain(true), this.logins))
                .now("alice", "any").user().map(AuthUser::name),
            new IsEqual<>(Optional.of("alice"))
        );
    }

    @Test
    void unlockedUserPassesAgain() {
        this.lock("alice");
        this.logins.unlock("alice", "admin");
        MatcherAssert.assertThat(
            new PasswordCheck(new LockedOut(this.chain(true), this.logins))
                .now("alice", "any").user().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void rejectedKeyIsNotCountedByDefault() {
        final Authentication keys = new CountedKeys(this.chain(false), this.logins);
        for (int idx = 0; idx < 5; idx += 1) {
            new PasswordCheck(keys).now("alice", "stale key");
        }
        MatcherAssert.assertThat(
            this.logins.state("alice").failures(), new IsEqual<>(0)
        );
    }

    @Test
    void rejectedKeyIsCountedWhenThePolicySaysSo() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, true));
        final Authentication keys = new CountedKeys(this.chain(false), this.logins);
        new PasswordCheck(keys).now("alice", "stale key");
        new PasswordCheck(keys).now("alice", "stale key");
        MatcherAssert.assertThat(
            "two rejected keys are two failures",
            this.logins.state("alice").failures(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the third locks and says so",
            new PasswordCheck(keys).now("alice", "stale key").refusal()
                .map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
        MatcherAssert.assertThat(
            "it is recorded as a key", this.logins.lockedNames().get(0).origin().credential(),
            new IsEqual<>("api_key")
        );
    }

    @Test
    void rejectedKeyOfANameLockedForPasswordsOnlyIsNotCountedAgain() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, true, false));
        final Authentication keys = new LockedOut(
            new CountedKeys(this.chain(false), this.logins), this.logins
        );
        for (int idx = 0; idx < 6; idx += 1) {
            new PasswordCheck(keys).now("alice", "stale key");
        }
        MatcherAssert.assertThat(
            "a tool that repeats the key does not write the count again after the lock",
            this.logins.state("alice").failures(), new IsEqual<>(3)
        );
    }

    @Test
    void acceptedKeyDoesNotForgetFailedLogins() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, true));
        this.logins.failed("alice", LoginOrigin.UNKNOWN);
        new PasswordCheck(new CountedKeys(this.chain(true), this.logins)).now("alice", "key");
        MatcherAssert.assertThat(
            "a build with a valid key must not clear the count of an attack on its user",
            this.logins.state("alice").failures(), new IsEqual<>(1)
        );
    }

    private void lock(final String name) {
        for (int idx = 0; idx < 3; idx += 1) {
            this.logins.failed(name, LoginOrigin.UNKNOWN);
        }
    }

    /**
     * Chain that counts its calls.
     * @param accepts Whether it accepts every login
     * @return Chain
     */
    private Authentication chain(final boolean accepts) {
        return (name, pass) -> {
            this.calls.incrementAndGet();
            final Optional<AuthUser> res;
            if (accepts) {
                res = Optional.of(new AuthUser(name.strip().toLowerCase(java.util.Locale.ROOT), "test"));
            } else {
                res = Optional.empty();
            }
            return res;
        };
    }
}
