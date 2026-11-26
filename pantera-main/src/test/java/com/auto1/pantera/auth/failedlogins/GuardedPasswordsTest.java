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
import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.http.auth.PasswordCheck;
import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link GuardedPasswords}: failed password logins are counted
 * per user name, a delayed or locked login reaches no provider, and the
 * client is told why.
 */
final class GuardedPasswordsTest {

    /**
     * The password the provider accepts.
     */
    private static final String RIGHT = "right password";

    /**
     * Clock of the store.
     */
    private AtomicReference<Instant> clock;

    /**
     * Policy in force.
     */
    private AtomicReference<FailedLoginsConfig> policy;

    /**
     * Counts of failed logins.
     */
    private FailedLogins logins;

    /**
     * The password provider behind the guard.
     */
    private Provider provider;

    /**
     * The guard under test.
     */
    private Authentication guard;

    @BeforeEach
    void setUp() {
        this.clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        this.policy = new AtomicReference<>(FailedLoginsConfig.defaults());
        this.logins = new FailedLogins(
            new InMemoryFailedLoginStore(this.clock::get), this.policy::get
        );
        this.provider = new Provider();
        this.guard = new GuardedPasswords(this.provider, this.logins);
    }

    @Test
    void wrongPasswordIsAPlainFailedLoginAndIsCounted() {
        final PasswordCheck.Outcome outcome = this.login("alice", "wrong");
        MatcherAssert.assertThat(
            "the login fails", outcome.user().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "as wrong credentials, with no reason of its own",
            outcome.refusal().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and one failure is counted", this.failures("alice"), new IsEqual<>(1)
        );
    }

    @Test
    void loginAfterTheSecondFailureIsDelayedAndReachesNoProvider() {
        this.login("alice", "wrong");
        this.login("alice", "wrong");
        final PasswordCheck.Outcome outcome = this.login("alice", GuardedPasswordsTest.RIGHT);
        final AuthRefusal refusal = outcome.refusal().orElseThrow();
        MatcherAssert.assertThat(
            "even the right password is refused during the delay",
            outcome.user().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "with 403, as Artifactory answers a suspended login",
            refusal.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "the reason for the access record",
            refusal.reason(), new IsEqual<>("login_delayed")
        );
        MatcherAssert.assertThat(
            "and the seconds to wait", refusal.retryAfter(), new IsEqual<>(OptionalLong.of(4L))
        );
        MatcherAssert.assertThat(
            "the provider saw only the two failed logins",
            this.provider.calls.get(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "a refused login is not counted", this.failures("alice"), new IsEqual<>(2)
        );
    }

    @Test
    void loginIsCheckedAgainWhenTheDelayIsOver() {
        this.login("alice", "wrong");
        this.login("alice", "wrong");
        this.clock.set(this.clock.get().plusSeconds(4L));
        final PasswordCheck.Outcome outcome = this.login("alice", GuardedPasswordsTest.RIGHT);
        MatcherAssert.assertThat(
            "the right password signs in", outcome.user().isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and the count starts again", this.failures("alice"), new IsEqual<>(0)
        );
    }

    @Test
    void thirdFailureLocksAndSaysSo() {
        this.login("alice", "wrong");
        this.login("alice", "wrong");
        this.clock.set(this.clock.get().plusSeconds(4L));
        final AuthRefusal refusal = this.login("alice", "wrong").refusal().orElseThrow();
        MatcherAssert.assertThat(
            "the failure that locks is answered 403",
            refusal.status(), new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "with the reason", refusal.reason(), new IsEqual<>("account_locked")
        );
        MatcherAssert.assertThat(
            "and the text", refusal.message(), new IsEqual<>("Account locked")
        );
    }

    @Test
    void lockedNameReachesNoProviderEvenWithTheRightPassword() {
        this.lock("alice");
        final int before = this.provider.calls.get();
        final PasswordCheck.Outcome outcome = this.login("alice", GuardedPasswordsTest.RIGHT);
        MatcherAssert.assertThat(
            "the login is refused", outcome.user().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "as locked",
            outcome.refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
        MatcherAssert.assertThat(
            "and no provider is asked", this.provider.calls.get(), new IsEqual<>(before)
        );
    }

    @Test
    void successForgetsTheFailures() {
        this.login("alice", "wrong");
        this.login("alice", GuardedPasswordsTest.RIGHT);
        MatcherAssert.assertThat(
            this.failures("alice"), new IsEqual<>(0)
        );
    }

    @Test
    void successOfOneNameLeavesTheOthersAlone() {
        this.login("alice", "wrong");
        this.login("bob", GuardedPasswordsTest.RIGHT);
        MatcherAssert.assertThat(
            this.failures("alice"), new IsEqual<>(1)
        );
    }

    @Test
    void spellingsOfOneNameShareTheCount() {
        this.login("Alice@Example.com", "wrong");
        this.login("alice@example.com", "wrong");
        MatcherAssert.assertThat(
            "the second spelling is the second failure: the third is delayed",
            this.login("ALICE@EXAMPLE.COM", "wrong").refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("login_delayed"))
        );
    }

    @Test
    void loginRefusedByAProviderForItsOwnReasonIsNotCounted() {
        this.provider.refusal.set(new AuthRefusal("mfa_required"));
        for (int idx = 0; idx < 5; idx += 1) {
            this.login("alice", GuardedPasswordsTest.RIGHT);
        }
        MatcherAssert.assertThat(
            "a correct password that needs a second factor is no failed login",
            this.failures("alice"), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "and the provider's reason reaches the client",
            this.login("alice", GuardedPasswordsTest.RIGHT).refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("mfa_required"))
        );
    }

    @Test
    void outageOfAProviderLocksNobody() {
        this.provider.refusal.set(
            new AuthRefusal(
                "identity_provider_unavailable", RsStatus.SERVICE_UNAVAILABLE, 0L,
                "Sign-in is temporarily unavailable"
            )
        );
        for (int idx = 0; idx < 10; idx += 1) {
            this.login("alice", GuardedPasswordsTest.RIGHT);
        }
        MatcherAssert.assertThat(
            this.failures("alice"), new IsEqual<>(0)
        );
    }

    @Test
    void withoutLockTheDelayGrowsAndNobodyIsLocked() {
        this.policy.set(new FailedLoginsConfig(2, 1, 60, 0, 0, 0, false));
        for (int idx = 0; idx < 8; idx += 1) {
            this.login("alice", "wrong");
            this.clock.set(this.clock.get().plusSeconds(120L));
        }
        this.clock.set(this.clock.get().minusSeconds(120L));
        MatcherAssert.assertThat(
            "after eight failures the wait is the longest delay",
            this.login("alice", "wrong").refusal().orElseThrow().retryAfter(),
            new IsEqual<>(OptionalLong.of(60L))
        );
    }

    @Test
    void withDelayAndLockOffEveryLoginIsChecked() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 0, 0, 0, false));
        for (int idx = 0; idx < 20; idx += 1) {
            this.login("alice", "wrong");
        }
        MatcherAssert.assertThat(
            "the provider is asked every time",
            this.provider.calls.get(), new IsEqual<>(20)
        );
        MatcherAssert.assertThat(
            "and the right password still signs in",
            this.login("alice", GuardedPasswordsTest.RIGHT).user().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void changedPolicyAppliesToTheNextLogin() {
        this.login("alice", "wrong");
        this.login("alice", "wrong");
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 0, 0, 0, false));
        MatcherAssert.assertThat(
            "with delays switched off the login is checked at once",
            this.login("alice", GuardedPasswordsTest.RIGHT).user().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void unlockLetsTheNextLoginThrough() {
        this.lock("alice");
        MatcherAssert.assertThat(
            "the unlock removes a lock", this.logins.unlock("alice", "admin"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and the user signs in",
            this.login("alice", GuardedPasswordsTest.RIGHT).user().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void lockEndsByItselfWhenAutomaticUnlockIsOn() {
        this.policy.set(new FailedLoginsConfig(2, 1, 60, 3, 30, 0, false));
        this.lock("alice");
        this.clock.set(this.clock.get().plusSeconds(30L * 60L - 1L));
        MatcherAssert.assertThat(
            "one second before the time the user is locked",
            this.login("alice", GuardedPasswordsTest.RIGHT).refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("account_locked"))
        );
        this.clock.set(this.clock.get().plusSeconds(1L));
        MatcherAssert.assertThat(
            "at the time the user signs in",
            this.login("alice", GuardedPasswordsTest.RIGHT).user().isPresent(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and nothing is left of the lock",
            this.logins.lockedNames().size(), new IsEqual<>(0)
        );
    }

    @Test
    void nameThatCannotBeAUserIsNotGuarded() {
        final String name = "a".repeat(300);
        for (int idx = 0; idx < 5; idx += 1) {
            this.login(name, "wrong");
        }
        MatcherAssert.assertThat(
            "every login is handed to the provider, which knows no such user",
            this.provider.calls.get(), new IsEqual<>(5)
        );
    }

    @Test
    void loginAcceptedOnTheFormIsCheckedAgainForARepositoryRequest() {
        // The providers behind the guard may take a password on the
        // sign-in form only: an answer given there is not handed to a
        // repository request that follows it.
        new PasswordCheck(this.guard, new LoginSource("10.0.0.1", "Mozilla/5.0", "ui"))
            .now("jane", GuardedPasswordsTest.RIGHT);
        new PasswordCheck(
            this.guard, new LoginSource("10.0.0.1", "Apache-Maven/3.9", LoginSource.REPOSITORY)
        ).now("jane", GuardedPasswordsTest.RIGHT);
        new PasswordCheck(
            this.guard, new LoginSource("10.0.0.1", "Apache-Maven/3.9", LoginSource.REPOSITORY)
        ).now("jane", GuardedPasswordsTest.RIGHT);
        MatcherAssert.assertThat(
            "once for the form, once for the repository requests",
            this.provider.calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void failureRecordsWhereItCameFrom() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 1, 0, 0, false));
        new PasswordCheck(
            this.guard, new LoginSource("192.0.2.7", "Apache-Maven/3.9.9", "repository")
        ).now("alice", "wrong");
        MatcherAssert.assertThat(
            this.logins.lockedNames().get(0).origin(),
            new IsEqual<>(
                new LoginOrigin("192.0.2.7", "Apache-Maven/3.9.9", "repository", "password")
            )
        );
    }

    @Test
    @Timeout(60)
    void loginsWithOneRightPasswordAtOnceAskTheProviderOnce() throws Exception {
        final int threads = 12;
        this.provider.gate = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<PasswordCheck.Outcome>> all = new ArrayList<>(threads);
            for (int idx = 0; idx < threads; idx += 1) {
                all.add(pool.submit(() -> this.login("alice", GuardedPasswordsTest.RIGHT)));
            }
            this.provider.entered.await(30, TimeUnit.SECONDS);
            this.provider.gate.countDown();
            int accepted = 0;
            for (final Future<PasswordCheck.Outcome> one : all) {
                if (one.get(30, TimeUnit.SECONDS).user().isPresent()) {
                    accepted += 1;
                }
            }
            MatcherAssert.assertThat(
                "every login is accepted", accepted, new IsEqual<>(threads)
            );
            MatcherAssert.assertThat(
                "and the provider checked the password once",
                this.provider.calls.get(), new IsEqual<>(1)
            );
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(60)
    void guessesAtOnceReachTheProviderNoMoreOftenThanTheLockLimit() throws Exception {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false));
        final int threads = 24;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<PasswordCheck.Outcome>> all = new ArrayList<>(threads);
            for (int idx = 0; idx < threads; idx += 1) {
                final String guess = "guess-" + idx;
                all.add(
                    pool.submit(
                        () -> {
                            start.await();
                            return this.login("alice", guess);
                        }
                    )
                );
            }
            start.countDown();
            for (final Future<PasswordCheck.Outcome> one : all) {
                one.get(30, TimeUnit.SECONDS);
            }
            MatcherAssert.assertThat(
                "the provider, and the directory behind it, saw three guesses",
                this.provider.calls.get(), new IsEqual<>(3)
            );
        } finally {
            pool.shutdownNow();
        }
    }

    private PasswordCheck.Outcome login(final String name, final String pass) {
        return new PasswordCheck(this.guard).now(name, pass);
    }

    private int failures(final String name) {
        return this.logins.state(name).failures();
    }

    private void lock(final String name) {
        this.login(name, "wrong");
        this.login(name, "wrong");
        this.clock.set(this.clock.get().plusSeconds(4L));
        this.login(name, "wrong");
    }

    /**
     * Password provider that counts its calls. It accepts one password,
     * and can refuse every login for a reason of its own.
     */
    private static final class Provider implements Authentication {

        /**
         * Number of calls.
         */
        private final AtomicInteger calls = new AtomicInteger();

        /**
         * Reason the provider refuses every login for, if any.
         */
        private final AtomicReference<AuthRefusal> refusal = new AtomicReference<>();

        /**
         * Released when the first call has arrived.
         */
        private final CountDownLatch entered = new CountDownLatch(1);

        /**
         * When set, a call waits for it before it answers.
         */
        private volatile CountDownLatch gate;

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            this.calls.incrementAndGet();
            this.entered.countDown();
            final CountDownLatch wait = this.gate;
            if (wait != null) {
                try {
                    wait.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            Optional<AuthUser> res = Optional.empty();
            final AuthRefusal why = this.refusal.get();
            if (why != null) {
                why.note();
            } else if (GuardedPasswordsTest.RIGHT.equals(pass)) {
                res = Optional.of(new AuthUser(name, "test"));
            }
            return res;
        }
    }
}
