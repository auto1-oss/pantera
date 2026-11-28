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

import com.auto1.pantera.http.auth.LoginSource;
import com.auto1.pantera.settings.policy.SignInReportConfig;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sign-in failures are noted in memory and written once per flush, one
 * row per line.
 *
 * @since 2.2.15
 */
final class SignInFailuresTest {

    /**
     * A repository client.
     */
    private static final LoginSource MAVEN =
        new LoginSource("192.0.2.7", "Apache-Maven/3.9.9", LoginSource.REPOSITORY);

    /**
     * Time.
     */
    private AtomicReference<Instant> clock;

    /**
     * The policy.
     */
    private AtomicReference<SignInReportConfig> policy;

    /**
     * Whether the store refuses to save.
     */
    private AtomicBoolean broken;

    /**
     * The store.
     */
    private SignInFailureStore store;

    /**
     * The report.
     */
    private SignInFailures report;

    @BeforeEach
    void setUp() {
        this.clock = new AtomicReference<>(Instant.parse("2026-01-01T10:00:00Z"));
        this.policy = new AtomicReference<>(SignInReportConfig.defaults());
        this.broken = new AtomicBoolean(false);
        final InMemorySignInFailureStore memory = new InMemorySignInFailureStore();
        this.store = new SignInFailureStore() {
            @Override
            public void save(final Collection<SignInFailure> events) {
                if (SignInFailuresTest.this.broken.get()) {
                    throw new IllegalStateException("database is down");
                }
                memory.save(events);
            }

            @Override
            public Page list(final Query query) {
                return memory.list(query);
            }

            @Override
            public int purge(final Instant before) {
                return memory.purge(before);
            }
        };
        this.report = new SignInFailures(this.store, this.policy::get, this.clock::get);
    }

    @Test
    void requestsOfOneClientWithOneKeyBecomeOneRowAtTheFlush() {
        final RejectedKey key = new RejectedKey("api_key_expired", "Alice", "key-1", true);
        for (int idx = 0; idx < 48; idx += 1) {
            this.report.rejectedKey(key, SignInFailuresTest.MAVEN);
            this.clock.set(this.clock.get().plusSeconds(1));
        }
        MatcherAssert.assertThat(
            "nothing is written on the request path",
            this.store.list(SignInFailuresTest.all(SignInFailure.API_KEY)).total(),
            new IsEqual<>(0)
        );
        this.report.flush();
        MatcherAssert.assertThat(
            "one row holds all of them",
            this.store.list(SignInFailuresTest.all(SignInFailure.API_KEY)).items(),
            new IsEqual<>(
                List.of(
                    new SignInFailure(
                        SignInFailure.API_KEY, "alice", "192.0.2.7", "key-1", "api_key_expired",
                        true, "Apache-Maven/3.9.9", "repository", 48L, 0L,
                        Instant.parse("2026-01-01T10:00:00Z"),
                        Instant.parse("2026-01-01T10:00:47Z"), 0, null, ""
                    )
                )
            )
        );
    }

    @Test
    void failedAndRefusedPasswordLoginsShareALine() {
        this.report.failedPassword("alice", SignInFailuresTest.MAVEN);
        this.report.failedPassword("alice", SignInFailuresTest.MAVEN);
        this.report.refusedPassword("alice", SignInFailuresTest.MAVEN, "account_locked");
        final SignInFailure line = this.report
            .list(SignInFailuresTest.all(SignInFailure.PASSWORD)).items().get(0);
        MatcherAssert.assertThat("two failed logins", line.hits(), new IsEqual<>(2L));
        MatcherAssert.assertThat("one refused without a check", line.refused(), new IsEqual<>(1L));
        MatcherAssert.assertThat(
            "the last one says why", line.reason(), new IsEqual<>("account_locked")
        );
    }

    @Test
    void listingShowsWhatWasNotedAMomentAgo() {
        this.report.failedPassword("alice", SignInFailuresTest.MAVEN);
        MatcherAssert.assertThat(
            this.report.list(SignInFailuresTest.all(SignInFailure.PASSWORD)).total(),
            new IsEqual<>(1)
        );
    }

    @Test
    void secondFactorLineCarriesTheCountAndTheEndOfThePause() {
        final Instant end = Instant.parse("2026-01-01T10:15:00Z");
        final LoginSource form = new LoginSource("192.0.2.9", "Mozilla/5.0", "ui");
        this.report.unconfirmedSecondFactor("alice", form, 5, end);
        this.report.refusedPaused("alice", form, 5, end);
        final SignInFailure line = this.report
            .list(SignInFailuresTest.all(SignInFailure.SECOND_FACTOR)).items().get(0);
        MatcherAssert.assertThat(
            "unconfirmed, refused during the pause, the count, and the end of the pause",
            List.of(
                String.valueOf(line.hits()), String.valueOf(line.refused()),
                String.valueOf(line.failures()), String.valueOf(line.blockedUntil())
            ),
            new IsEqual<>(List.of("1", "1", "5", "2026-01-01T10:15:00Z"))
        );
    }

    @Test
    void secondFactorIsNotAFailedPassword() {
        this.report.unconfirmedSecondFactor(
            "alice", new LoginSource("192.0.2.9", "Mozilla/5.0", "ui"), 1, null
        );
        MatcherAssert.assertThat(
            this.report.list(SignInFailuresTest.all(SignInFailure.PASSWORD)).total(),
            new IsEqual<>(0)
        );
    }

    @Test
    void unlockKeepsWhoUnlocked() {
        this.report.unlocked("Alice", "admin");
        MatcherAssert.assertThat(
            this.report.list(SignInFailuresTest.all(SignInFailure.UNLOCK)).items().get(0).actor(),
            new IsEqual<>("admin")
        );
    }

    @Test
    void nothingIsNotedWhileTheReportIsOff() {
        this.policy.set(new SignInReportConfig(false, 90));
        this.report.failedPassword("alice", SignInFailuresTest.MAVEN);
        this.report.rejectedKey(
            new RejectedKey("api_key_expired", "alice", "key-1", true), SignInFailuresTest.MAVEN
        );
        this.policy.set(SignInReportConfig.defaults());
        MatcherAssert.assertThat(
            "passwords",
            this.report.list(SignInFailuresTest.all(SignInFailure.PASSWORD)).total(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "keys",
            this.report.list(SignInFailuresTest.all(SignInFailure.API_KEY)).total(),
            new IsEqual<>(0)
        );
    }

    @Test
    void floodOfNamesDoesNotGrowTheMemory() {
        for (int idx = 0; idx < SignInFailures.MAX_PENDING + 500; idx += 1) {
            this.report.failedPassword("user-" + idx, SignInFailuresTest.MAVEN);
        }
        MatcherAssert.assertThat(
            "what does not fit is counted, not kept",
            this.report.dropped(), new IsEqual<>(500L)
        );
        MatcherAssert.assertThat(
            "an event of a line that is already noted is still counted",
            this.noteAgain("user-0"), new IsEqual<>(2L)
        );
    }

    @Test
    void eventsSurviveAFlushThatFails() {
        this.report.failedPassword("alice", SignInFailuresTest.MAVEN);
        this.broken.set(true);
        this.report.flush();
        this.report.failedPassword("alice", SignInFailuresTest.MAVEN);
        this.broken.set(false);
        this.report.flush();
        MatcherAssert.assertThat(
            this.store.list(SignInFailuresTest.all(SignInFailure.PASSWORD)).items().get(0).hits(),
            new IsEqual<>(2L)
        );
    }

    @Test
    void linesOlderThanTheRetentionAreRemovedAtAFlush() {
        this.policy.set(new SignInReportConfig(true, 30));
        this.report.failedPassword("old", SignInFailuresTest.MAVEN);
        this.report.flush();
        this.clock.set(this.clock.get().plusSeconds(31L * 86_400L));
        this.report.failedPassword("recent", SignInFailuresTest.MAVEN);
        this.report.flush();
        MatcherAssert.assertThat(
            this.store.list(SignInFailuresTest.all(SignInFailure.PASSWORD)).items()
                .stream().map(SignInFailure::username).toList(),
            new IsEqual<>(List.of("recent"))
        );
    }

    @Test
    void nameTooLongToBeAUserIsNotNoted() {
        this.report.failedPassword("a".repeat(300), SignInFailuresTest.MAVEN);
        MatcherAssert.assertThat(
            this.report.list(SignInFailuresTest.all(SignInFailure.PASSWORD)).total(),
            new IsEqual<>(0)
        );
    }

    private long noteAgain(final String name) {
        this.report.failedPassword(name, SignInFailuresTest.MAVEN);
        return this.report.list(
            new SignInFailureStore.Query(SignInFailure.PASSWORD, name, true, "", 1, 0)
        ).items().get(0).hits();
    }

    private static SignInFailureStore.Query all(final String kind) {
        return new SignInFailureStore.Query(kind, "", false, "", 100, 0);
    }
}
