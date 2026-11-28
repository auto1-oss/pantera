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

import com.auto1.pantera.http.auth.AuthRefusal;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.metrics.MicrometerMetrics;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The login delay, the account lock and the pause after unconfirmed second
 * factors are counted: refused logins by reason, locks, unlocks by cause,
 * unconfirmed second factors and pauses.
 *
 * @since 2.2.15
 */
final class SignInMetricsTest {

    /**
     * Clock of the store.
     */
    private AtomicReference<Instant> clock;

    /**
     * Policy in force.
     */
    private AtomicReference<FailedLoginsConfig> policy;

    /**
     * Failed logins.
     */
    private FailedLogins logins;

    /**
     * Password providers behind the guard: they reject every login.
     */
    private Authentication guard;

    @BeforeAll
    static void metrics() {
        MicrometerMetrics.initialize(new SimpleMeterRegistry());
    }

    @BeforeEach
    void setUp() {
        this.clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        this.policy = new AtomicReference<>(FailedLoginsConfig.defaults());
        this.logins = new FailedLogins(
            new InMemoryFailedLoginStore(this.clock::get), this.policy::get
        );
        this.guard = new GuardedPasswords((name, pass) -> Optional.<AuthUser>empty(), this.logins);
    }

    @Test
    void delayedLoginIsCountedAsRefused() {
        final double before = SignInMetricsTest.refused("login_delayed");
        this.login();
        this.login();
        this.login();
        MatcherAssert.assertThat(
            "the third login, sent during the delay, is one refused login",
            SignInMetricsTest.refused("login_delayed") - before, new IsEqual<>(1.0)
        );
    }

    @Test
    void lockIsCountedOnceAndEveryLoginItRefuses() {
        final double locks = SignInMetricsTest.count("pantera.auth.account.locks");
        final double refused = SignInMetricsTest.refused("account_locked");
        this.login();
        this.login();
        this.clock.set(this.clock.get().plusSeconds(4L));
        this.login();
        this.login();
        this.login();
        MatcherAssert.assertThat(
            "one lock", SignInMetricsTest.count("pantera.auth.account.locks") - locks,
            new IsEqual<>(1.0)
        );
        MatcherAssert.assertThat(
            "the login that locked and the two after it were refused as locked",
            SignInMetricsTest.refused("account_locked") - refused, new IsEqual<>(3.0)
        );
    }

    @Test
    void unconfirmedSecondFactorsAndThePauseAreCounted() {
        this.policy.set(FailedLoginsConfig.defaults().withSecondFactor(2, 15));
        final Authentication denied = new GuardedPasswords(
            (name, pass) -> {
                new AuthRefusal(AuthRefusal.SECOND_FACTOR_FAILED).note();
                return Optional.<AuthUser>empty();
            },
            this.logins
        );
        final double failures = SignInMetricsTest.count("pantera.auth.second_factor.failures");
        final double pauses = SignInMetricsTest.count("pantera.auth.sign_in.pauses");
        final double refused = SignInMetricsTest.refused("sign_in_paused");
        for (int idx = 0; idx < 3; idx += 1) {
            new PasswordCheck(denied).now("alice", "right");
        }
        MatcherAssert.assertThat(
            "two unconfirmed second factors, one pause, and the sign-in that reached it"
                + " and the one after it refused as paused",
            java.util.List.of(
                SignInMetricsTest.count("pantera.auth.second_factor.failures") - failures,
                SignInMetricsTest.count("pantera.auth.sign_in.pauses") - pauses,
                SignInMetricsTest.refused("sign_in_paused") - refused
            ),
            new IsEqual<>(java.util.List.of(2.0, 1.0, 2.0))
        );
    }

    @Test
    void lockedCredentialOfAnyKindIsCountedAsRefused() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 1, 0, 0, false));
        this.login();
        final double refused = SignInMetricsTest.refused("account_locked");
        new PasswordCheck(new LockedOut(this.guard, this.logins)).now("alice", "any");
        MatcherAssert.assertThat(
            SignInMetricsTest.refused("account_locked") - refused, new IsEqual<>(1.0)
        );
    }

    @Test
    void unlocksAreCountedByCause() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 1, 30, 0, false));
        final double admin = SignInMetricsTest.unlocks("admin");
        final double auto = SignInMetricsTest.unlocks("automatic");
        final double off = SignInMetricsTest.unlocks("lock_off");
        this.logins.failed("alice", LoginOrigin.UNKNOWN);
        this.logins.unlock("alice", "root");
        this.logins.unlock("alice", "root");
        this.logins.failed("bob", LoginOrigin.UNKNOWN);
        this.clock.set(this.clock.get().plusSeconds(30L * 60L));
        this.logins.admission("bob");
        this.logins.failed("carol", LoginOrigin.UNKNOWN);
        this.logins.failed("dave", LoginOrigin.UNKNOWN);
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 0, 0, 0, false));
        this.logins.refresh();
        MatcherAssert.assertThat(
            "an admin's unlock of a locked user, once",
            SignInMetricsTest.unlocks("admin") - admin, new IsEqual<>(1.0)
        );
        MatcherAssert.assertThat(
            "a lock that ended by itself",
            SignInMetricsTest.unlocks("automatic") - auto, new IsEqual<>(1.0)
        );
        MatcherAssert.assertThat(
            "two locks released by switching the lock off",
            SignInMetricsTest.unlocks("lock_off") - off, new IsEqual<>(2.0)
        );
    }

    private void login() {
        new PasswordCheck(this.guard).now("alice", "wrong");
    }

    private static double refused(final String reason) {
        final Counter counter = MicrometerMetrics.getInstance().getRegistry()
            .find("pantera.auth.login.refused").tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static double unlocks(final String cause) {
        final Counter counter = MicrometerMetrics.getInstance().getRegistry()
            .find("pantera.auth.account.unlocks").tag("cause", cause).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static double count(final String name) {
        final Counter counter = MicrometerMetrics.getInstance().getRegistry().find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
