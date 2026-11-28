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

import com.auto1.pantera.settings.policy.FailedLoginsConfig;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FailedLogins}: who is locked, as every node sees it.
 */
final class FailedLoginsTest {

    /**
     * Clock of the store.
     */
    private AtomicReference<Instant> clock;

    /**
     * Policy in force.
     */
    private AtomicReference<FailedLoginsConfig> policy;

    /**
     * Store shared by the nodes.
     */
    private FailedLoginStore store;

    @BeforeEach
    void setUp() {
        this.clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        this.policy = new AtomicReference<>(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false));
        this.store = new InMemoryFailedLoginStore(this.clock::get);
    }

    @Test
    void nodeThatLocksKnowsItAtOnce() {
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        MatcherAssert.assertThat(
            node.locked("alice"), new IsEqual<>(true)
        );
    }

    @Test
    void anotherNodeLearnsOfALockWhenItLoadsTheLockedNames() {
        final FailedLogins first = this.node();
        final FailedLogins second = this.node();
        FailedLoginsTest.lock(first, "alice");
        MatcherAssert.assertThat(
            "before it loads, the other node does not know",
            second.locked("alice"), new IsEqual<>(false)
        );
        second.refresh();
        MatcherAssert.assertThat(
            "after it loads, it does", second.locked("alice"), new IsEqual<>(true)
        );
    }

    @Test
    void lockIsInForceAfterARestart() {
        FailedLoginsTest.lock(this.node(), "alice");
        final FailedLogins restarted = this.node();
        restarted.refresh();
        MatcherAssert.assertThat(
            restarted.locked("alice"), new IsEqual<>(true)
        );
    }

    @Test
    void anotherNodeLearnsOfAnUnlockWhenItLoads() {
        final FailedLogins first = this.node();
        final FailedLogins second = this.node();
        FailedLoginsTest.lock(first, "alice");
        second.refresh();
        first.unlock("alice", "admin");
        second.refresh();
        MatcherAssert.assertThat(
            second.locked("alice"), new IsEqual<>(false)
        );
    }

    @Test
    void lockRemovedFromTheStoreByHandIsPickedUp() {
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        this.store.forget("alice");
        node.refresh();
        MatcherAssert.assertThat(
            "a lock an operator removed in the database ends at the next load",
            node.locked("alice"), new IsEqual<>(false)
        );
    }

    @Test
    void expiredLockIsNotInForceAndNotListed() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 30, 0, false));
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        this.clock.set(this.clock.get().plusSeconds(30L * 60L));
        node.refresh();
        MatcherAssert.assertThat(
            "the lock is not in force", node.locked("alice"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "and not listed", node.lockedNames().size(), new IsEqual<>(0)
        );
    }

    @Test
    void switchingTheLockOffUnlocksEveryoneAtTheNextLoad() {
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        FailedLoginsTest.lock(node, "bob");
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 0, 0, 0, false));
        node.refresh();
        MatcherAssert.assertThat(
            "nobody is locked",
            List.of(node.locked("alice"), node.passwordLocked("bob")),
            new IsEqual<>(List.of(false, false))
        );
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false));
        node.refresh();
        MatcherAssert.assertThat(
            "and switching the lock on again brings no old lock back",
            node.lockedNames().size(), new IsEqual<>(0)
        );
    }

    @Test
    void detailsOfANameTellItsStateAndWhereTheLastFailureCameFrom() {
        final FailedLogins node = this.node();
        node.failed("alice", new LoginOrigin("192.0.2.7", "curl/8", "repository", "password"));
        final LockedName details = node.details("Alice").orElseThrow();
        MatcherAssert.assertThat("count", details.failures(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "not locked", details.lockedAt() == null, new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "origin", details.origin().clientIp(), new IsEqual<>("192.0.2.7")
        );
        MatcherAssert.assertThat(
            "a name without failures has no details",
            node.details("bob").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void unlockAllUnlocksEveryNameAndTellsWhich() {
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        FailedLoginsTest.lock(node, "bob");
        MatcherAssert.assertThat(
            "both names are handed back",
            node.unlockAll("admin").size(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "and neither is locked",
            List.of(node.locked("alice"), node.locked("bob")),
            new IsEqual<>(List.of(false, false))
        );
    }

    @Test
    void unlockOfANameThatIsNotLockedClearsItsCount() {
        final FailedLogins node = this.node();
        node.failed("alice", LoginOrigin.UNKNOWN);
        MatcherAssert.assertThat(
            "no lock was removed", node.unlock("alice", "admin"), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the count is gone", node.state("alice").failures(), new IsEqual<>(0)
        );
    }

    @Test
    void lockLimitedToPasswordsLeavesOtherCredentialsAlone() {
        this.policy.set(new FailedLoginsConfig(0, 1, 60, 3, 0, 0, false, false));
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        MatcherAssert.assertThat(
            "the user's password sign-in is locked",
            node.passwordLocked("alice"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "keys, sessions and the provider button are not",
            node.locked("alice"), new IsEqual<>(false)
        );
    }

    @Test
    void lockOfEveryCredentialIsTheDefault() {
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        MatcherAssert.assertThat(
            List.of(node.passwordLocked("alice"), node.locked("alice")),
            new IsEqual<>(List.of(true, true))
        );
    }

    @Test
    void uncountableNameIsNeverLocked() {
        final FailedLogins node = this.node();
        MatcherAssert.assertThat(
            List.of(node.locked(null), node.locked(""), node.locked("a".repeat(300))),
            new IsEqual<>(List.of(false, false, false))
        );
    }

    @Test
    void nameWithAFailureIsPendingUntilALoginSucceeds() {
        final FailedLogins node = this.node();
        MatcherAssert.assertThat(
            "nothing is pending for a name without failures",
            node.pending("alice"), new IsEqual<>(false)
        );
        node.failed("Alice", LoginOrigin.UNKNOWN);
        MatcherAssert.assertThat(
            "one failure makes the next login one to check, not to remember",
            node.pending("alice"), new IsEqual<>(true)
        );
        node.succeeded("alice");
        MatcherAssert.assertThat(
            "a success ends it", node.pending("alice"), new IsEqual<>(false)
        );
    }

    @Test
    void lockedNameIsPending() {
        final FailedLogins node = this.node();
        FailedLoginsTest.lock(node, "alice");
        MatcherAssert.assertThat(node.pending("alice"), new IsEqual<>(true));
    }

    @Test
    void failureCountedOnAnotherNodeIsPendingHereAfterALoad() {
        final FailedLogins first = this.node();
        final FailedLogins second = this.node();
        first.failed("alice", LoginOrigin.UNKNOWN);
        second.refresh();
        MatcherAssert.assertThat(
            "the other node knows of the failure",
            second.pending("alice"), new IsEqual<>(true)
        );
        first.succeeded("alice");
        second.refresh();
        MatcherAssert.assertThat(
            "and of its end", second.pending("alice"), new IsEqual<>(false)
        );
    }

    @Test
    void unlockEndsWhatIsPending() {
        final FailedLogins node = this.node();
        node.failed("alice", LoginOrigin.UNKNOWN);
        node.unlock("alice", "admin");
        MatcherAssert.assertThat(node.pending("alice"), new IsEqual<>(false));
    }

    @Test
    void failedPasswordLoginsRefusalsAndUnlocksGoToTheReport() {
        final com.auto1.pantera.auth.signin.SignInFailures report =
            new com.auto1.pantera.auth.signin.SignInFailures(
                new com.auto1.pantera.auth.signin.InMemorySignInFailureStore(),
                com.auto1.pantera.settings.policy.SignInReportConfig::defaults
            );
        final FailedLogins node = this.node();
        node.reportTo(report);
        final LoginOrigin maven =
            new LoginOrigin("192.0.2.7", "Apache-Maven/3.9.9", "repository", "password");
        node.failed("Alice", maven);
        node.failed("alice", maven);
        node.failed("alice", new LoginOrigin("192.0.2.7", "IntelliJ", "repository", "api_key"));
        // The refusal is noted where the login is checked: there the
        // thread knows where the login came from.
        new com.auto1.pantera.http.auth.PasswordCheck(
            (name, pass) -> {
                node.refuse(Admission.LOCKED, name);
                return java.util.Optional.empty();
            },
            new com.auto1.pantera.http.auth.LoginSource(
                "192.0.2.7", "Apache-Maven/3.9.9", "repository"
            )
        ).now("alice", "any");
        node.unlock("alice", "admin");
        final com.auto1.pantera.auth.signin.SignInFailure line = report.list(
            new com.auto1.pantera.auth.signin.SignInFailureStore.Query(
                com.auto1.pantera.auth.signin.SignInFailure.PASSWORD, "alice", true, "", 10, 0
            )
        ).items().get(0);
        MatcherAssert.assertThat(
            "the two failed password logins are counted, the rejected key is not one",
            line.hits(), new IsEqual<>(2L)
        );
        MatcherAssert.assertThat(
            "the login refused during the lock is counted apart",
            line.refused(), new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "with the address the failures came from",
            line.clientIp(), new IsEqual<>("192.0.2.7")
        );
        MatcherAssert.assertThat(
            "and the unlock names the administrator",
            report.list(
                new com.auto1.pantera.auth.signin.SignInFailureStore.Query(
                    com.auto1.pantera.auth.signin.SignInFailure.UNLOCK, "alice", true, "", 10, 0
                )
            ).items().get(0).actor(),
            new IsEqual<>("admin")
        );
    }

    private FailedLogins node() {
        return new FailedLogins(this.store, this.policy::get);
    }

    private static void lock(final FailedLogins node, final String name) {
        for (int idx = 0; idx < 3; idx += 1) {
            node.failed(name, LoginOrigin.UNKNOWN);
        }
    }
}
