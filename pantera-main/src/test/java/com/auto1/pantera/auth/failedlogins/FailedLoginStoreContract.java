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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What every {@link FailedLoginStore} does, whether it keeps the counts in
 * memory or in the database. The rules that depend on time are tested with
 * {@link LoginState}; these tests need no clock.
 */
abstract class FailedLoginStoreContract {

    /**
     * Default policy: lock at the third failure.
     */
    private static final FailedLoginsConfig POLICY = FailedLoginsConfig.defaults();

    /**
     * Where the test logins come from.
     */
    private static final LoginOrigin ORIGIN =
        new LoginOrigin("192.0.2.7", "curl/8.7.1", "repository", "password");

    /**
     * An empty store.
     * @return Store under test
     */
    abstract FailedLoginStore store();

    @Test
    void nameWithoutFailuresHasNoState() {
        MatcherAssert.assertThat(
            this.store().read("nobody").state(), new IsEqual<>(LoginState.NONE)
        );
    }

    @Test
    void failureIsCountedAndKept() {
        final FailedLoginStore store = this.store();
        final LoginState after = store.fail(
            "alice", FailedLoginStoreContract.POLICY, FailedLoginStoreContract.ORIGIN
        ).state();
        MatcherAssert.assertThat(
            "the failure is counted", after.failures(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "and read back as it was written",
            store.read("alice").state(), new IsEqual<>(after)
        );
    }

    @Test
    void thirdFailureLocksAndTheNameIsListed() {
        final FailedLoginStore store = this.store();
        FailedLoginStoreContract.fail(store, "alice", 3);
        MatcherAssert.assertThat(
            "the name is locked", store.read("alice").state().locked(), new IsEqual<>(true)
        );
        final List<LockedName> locked = store.locked();
        MatcherAssert.assertThat(
            "it is the one locked name", locked.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "listed with its count", locked.get(0).failures(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "and where the last failure came from",
            locked.get(0).origin(), new IsEqual<>(FailedLoginStoreContract.ORIGIN)
        );
    }

    @Test
    void entryOfANameCarriesItsStateAndOrigin() {
        final FailedLoginStore store = this.store();
        FailedLoginStoreContract.fail(store, "alice", 2);
        final LockedName entry = store.entry(" ALICE ").orElseThrow();
        MatcherAssert.assertThat("count", entry.failures(), new IsEqual<>(2));
        MatcherAssert.assertThat("no lock", entry.lockedAt() == null, new IsEqual<>(true));
        MatcherAssert.assertThat(
            "origin", entry.origin(), new IsEqual<>(FailedLoginStoreContract.ORIGIN)
        );
        MatcherAssert.assertThat(
            "an unknown name has no entry",
            store.entry("nobody").isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void namesWithFailuresAreListedUntilTheyAreForgotten() {
        final FailedLoginStore store = this.store();
        FailedLoginStoreContract.fail(store, "alice", 1);
        FailedLoginStoreContract.fail(store, "Bob", 3);
        MatcherAssert.assertThat(
            "a name with a failure, locked or not, is listed in lower case",
            new java.util.TreeSet<>(store.counted()),
            new IsEqual<>(new java.util.TreeSet<>(java.util.List.of("alice", "bob")))
        );
        store.forget("alice");
        MatcherAssert.assertThat(
            "and no longer once its failures are forgotten",
            store.counted(), new IsEqual<>(java.util.List.of("bob"))
        );
    }

    @Test
    void nameThatIsNotLockedIsNotListed() {
        final FailedLoginStore store = this.store();
        FailedLoginStoreContract.fail(store, "alice", 2);
        MatcherAssert.assertThat(
            store.locked().size(), new IsEqual<>(0)
        );
    }

    @Test
    void forgettingANameRemovesItsFailuresAndItsLock() {
        final FailedLoginStore store = this.store();
        FailedLoginStoreContract.fail(store, "alice", 3);
        MatcherAssert.assertThat(
            "the state that was there is handed back",
            store.forget("alice").locked(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "nothing is left", store.read("alice").state(), new IsEqual<>(LoginState.NONE)
        );
        MatcherAssert.assertThat(
            "and no lock is listed", store.locked().size(), new IsEqual<>(0)
        );
    }

    @Test
    void forgettingAnUnknownNameChangesNothing() {
        MatcherAssert.assertThat(
            this.store().forget("nobody"), new IsEqual<>(LoginState.NONE)
        );
    }

    @Test
    void lettersCaseAndSurroundingSpacesDoNotMakeAnotherName() {
        final FailedLoginStore store = this.store();
        store.fail(
            "Alice@Example.COM", FailedLoginStoreContract.POLICY,
            FailedLoginStoreContract.ORIGIN
        );
        store.fail(
            " alice@example.com ", FailedLoginStoreContract.POLICY,
            FailedLoginStoreContract.ORIGIN
        );
        MatcherAssert.assertThat(
            "both spellings are one account at the directory, and one count here",
            store.read("ALICE@example.com").state().failures(), new IsEqual<>(2)
        );
    }

    @Test
    void unlockingAllRemovesEveryLockAndNamesThem() {
        final FailedLoginStore store = this.store();
        FailedLoginStoreContract.fail(store, "alice", 3);
        FailedLoginStoreContract.fail(store, "bob", 3);
        FailedLoginStoreContract.fail(store, "carol", 1);
        final List<String> unlocked = new ArrayList<>(store.unlockAll());
        unlocked.sort(String::compareTo);
        MatcherAssert.assertThat(
            "the locked names are handed back", unlocked, new IsEqual<>(List.of("alice", "bob"))
        );
        MatcherAssert.assertThat(
            "no lock is left", store.locked().size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "a name that was not locked keeps its count",
            store.read("carol").state().failures(), new IsEqual<>(1)
        );
    }

    @Test
    void nameTooLongForAUserIsNotStored() {
        final FailedLoginStore store = this.store();
        final String name = "a".repeat(256);
        MatcherAssert.assertThat(
            "no failure is counted for it",
            store.fail(name, FailedLoginStoreContract.POLICY, FailedLoginStoreContract.ORIGIN)
                .state(),
            new IsEqual<>(LoginState.NONE)
        );
        MatcherAssert.assertThat(
            "and none is read", store.read(name).state(), new IsEqual<>(LoginState.NONE)
        );
    }

    @Test
    void missingNameIsNotStored() {
        final FailedLoginStore store = this.store();
        MatcherAssert.assertThat(
            "no name",
            store.fail(null, FailedLoginStoreContract.POLICY, FailedLoginStoreContract.ORIGIN)
                .state(),
            new IsEqual<>(LoginState.NONE)
        );
        MatcherAssert.assertThat(
            "an empty name",
            store.fail("  ", FailedLoginStoreContract.POLICY, FailedLoginStoreContract.ORIGIN)
                .state(),
            new IsEqual<>(LoginState.NONE)
        );
    }

    @Test
    void overlongOriginIsCutNotRefused() {
        final FailedLoginStore store = this.store();
        store.fail(
            "alice", new FailedLoginsConfig(2, 1, 60, 1, 0, 0, false),
            new LoginOrigin("192.0.2.7", "x".repeat(5000), "repository", "password")
        );
        MatcherAssert.assertThat(
            store.locked().get(0).origin().userAgent().length(), new IsEqual<>(512)
        );
    }

    @Test
    @Timeout(60)
    void failuresFromManyThreadsAreAllCounted() throws Exception {
        final FailedLoginStore store = this.store();
        final FailedLoginsConfig nolock = new FailedLoginsConfig(0, 1, 60, 0, 0, 0, false);
        final int threads = 16;
        final int each = 5;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<?>> done = new ArrayList<>(threads);
            for (int idx = 0; idx < threads; idx += 1) {
                done.add(
                    pool.submit(
                        () -> {
                            start.await();
                            for (int cnt = 0; cnt < each; cnt += 1) {
                                store.fail("alice", nolock, FailedLoginStoreContract.ORIGIN);
                            }
                            return null;
                        }
                    )
                );
            }
            start.countDown();
            for (final Future<?> one : done) {
                one.get(50, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        MatcherAssert.assertThat(
            store.read("alice").state().failures(), new IsEqual<>(threads * each)
        );
    }

    private static void fail(final FailedLoginStore store, final String name, final int times) {
        for (int idx = 0; idx < times; idx += 1) {
            store.fail(name, FailedLoginStoreContract.POLICY, FailedLoginStoreContract.ORIGIN);
        }
    }
}
