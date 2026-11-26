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

import java.time.Instant;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * What every {@link SignInFailureStore} does.
 *
 * @since 2.2.15
 */
abstract class SignInFailureStoreContract {

    /**
     * A time to count from.
     */
    static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    /**
     * A new, empty store.
     *
     * @return Store
     */
    abstract SignInFailureStore store();

    @Test
    void eventOfANewLineIsKeptAsItIs() {
        final SignInFailureStore store = this.store();
        final SignInFailure event = SignInFailureStoreContract.key("alice", "192.0.2.7", 1);
        store.save(List.of(event));
        MatcherAssert.assertThat(
            store.list(SignInFailureStoreContract.all(SignInFailure.API_KEY)),
            new IsEqual<>(new SignInFailureStore.Page(List.of(event), 1))
        );
    }

    @Test
    void laterEventOfTheSameLineAddsItsCountsAndBringsItsTexts() {
        final SignInFailureStore store = this.store();
        store.save(List.of(SignInFailureStoreContract.key("alice", "192.0.2.7", 2)));
        store.save(
            List.of(
                new SignInFailure(
                    SignInFailure.API_KEY, "alice", "192.0.2.7", "key-1", "api_key_revoked",
                    true, "Gradle/8.9", "repository", 3L, 1L,
                    SignInFailureStoreContract.START.plusSeconds(60),
                    SignInFailureStoreContract.START.plusSeconds(90), 0, null, ""
                )
            )
        );
        MatcherAssert.assertThat(
            store.list(SignInFailureStoreContract.all(SignInFailure.API_KEY)).items(),
            new IsEqual<>(
                List.of(
                    new SignInFailure(
                        SignInFailure.API_KEY, "alice", "192.0.2.7", "key-1", "api_key_revoked",
                        true, "Gradle/8.9", "repository", 5L, 1L,
                        SignInFailureStoreContract.START,
                        SignInFailureStoreContract.START.plusSeconds(90), 0, null, ""
                    )
                )
            )
        );
    }

    @Test
    void userAddressKeyAndKindEachMakeALineOfTheirOwn() {
        final SignInFailureStore store = this.store();
        store.save(
            List.of(
                SignInFailureStoreContract.key("alice", "192.0.2.7", 1),
                SignInFailureStoreContract.key("alice", "192.0.2.8", 1),
                SignInFailureStoreContract.key("bob", "192.0.2.7", 1),
                new SignInFailure(
                    SignInFailure.API_KEY, "alice", "192.0.2.7", "key-2", "api_key_expired",
                    true, "", "repository", 1L, 0L, SignInFailureStoreContract.START,
                    SignInFailureStoreContract.START, 0, null, ""
                ),
                new SignInFailure(
                    SignInFailure.PASSWORD, "alice", "192.0.2.7", "", "wrong_credentials",
                    true, "", "ui", 1L, 0L, SignInFailureStoreContract.START,
                    SignInFailureStoreContract.START, 0, null, ""
                )
            )
        );
        MatcherAssert.assertThat(
            "four lines of rejected keys",
            store.list(SignInFailureStoreContract.all(SignInFailure.API_KEY)).total(),
            new IsEqual<>(4)
        );
        MatcherAssert.assertThat(
            "one of failed passwords",
            store.list(SignInFailureStoreContract.all(SignInFailure.PASSWORD)).total(),
            new IsEqual<>(1)
        );
    }

    @Test
    void linesAreListedMostRecentFirstAndInPages() {
        final SignInFailureStore store = this.store();
        for (int idx = 0; idx < 5; idx += 1) {
            store.save(
                List.of(
                    new SignInFailure(
                        SignInFailure.PASSWORD, "user-" + idx, "192.0.2.7", "",
                        "wrong_credentials", true, "", "ui", 1L, 0L,
                        SignInFailureStoreContract.START.plusSeconds(idx),
                        SignInFailureStoreContract.START.plusSeconds(idx), 0, null, ""
                    )
                )
            );
        }
        final SignInFailureStore.Page page = store.list(
            new SignInFailureStore.Query(SignInFailure.PASSWORD, "", false, "", 2, 1)
        );
        MatcherAssert.assertThat(
            "the second and third most recent",
            page.items().stream().map(SignInFailure::username).toList(),
            new IsEqual<>(List.of("user-3", "user-2"))
        );
        MatcherAssert.assertThat("of five", page.total(), new IsEqual<>(5));
    }

    @Test
    void linesAreFoundByAPartOfTheUserNameAndOfTheAddress() {
        final SignInFailureStore store = this.store();
        store.save(
            List.of(
                SignInFailureStoreContract.key("alice@example.com", "192.0.2.7", 1),
                SignInFailureStoreContract.key("alice_100%", "198.51.100.9", 1),
                SignInFailureStoreContract.key("bob@example.com", "192.0.2.8", 1)
            )
        );
        MatcherAssert.assertThat(
            "a part of the name, whatever its case",
            store.list(
                new SignInFailureStore.Query(SignInFailure.API_KEY, "ALICE", false, "", 10, 0)
            ).total(),
            new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the whole name only when asked for exactly that",
            store.list(
                new SignInFailureStore.Query(SignInFailure.API_KEY, "alice", true, "", 10, 0)
            ).total(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "a part of the address",
            store.list(
                new SignInFailureStore.Query(SignInFailure.API_KEY, "", false, "192.0.2.", 10, 0)
            ).total(),
            new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "a percent sign is a character to look for, not a wildcard",
            store.list(
                new SignInFailureStore.Query(SignInFailure.API_KEY, "100%", false, "", 10, 0)
            ).total(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "and so is an underscore",
            store.list(
                new SignInFailureStore.Query(SignInFailure.API_KEY, "e_1", false, "", 10, 0)
            ).total(),
            new IsEqual<>(1)
        );
    }

    @Test
    void limitHitKeepsItsBlockAndAnUnlockItsActor() {
        final SignInFailureStore store = this.store();
        final SignInFailure hit = new SignInFailure(
            SignInFailure.FORM_LIMIT, "alice", "192.0.2.7", "", "form_limit", true,
            "Mozilla/5.0", "ui", 1L, 0L, SignInFailureStoreContract.START,
            SignInFailureStoreContract.START, 5,
            SignInFailureStoreContract.START.plusSeconds(900), ""
        );
        final SignInFailure unlock = new SignInFailure(
            SignInFailure.UNLOCK, "alice", "", "", "unlocked", true, "", "", 1L, 0L,
            SignInFailureStoreContract.START, SignInFailureStoreContract.START, 0, null, "admin"
        );
        store.save(List.of(hit, unlock));
        MatcherAssert.assertThat(
            "the limit hit",
            store.list(SignInFailureStoreContract.all(SignInFailure.FORM_LIMIT)).items(),
            new IsEqual<>(List.of(hit))
        );
        MatcherAssert.assertThat(
            "the unlock",
            store.list(SignInFailureStoreContract.all(SignInFailure.UNLOCK)).items(),
            new IsEqual<>(List.of(unlock))
        );
    }

    @Test
    void linesNotSeenSinceATimeArePurged() {
        final SignInFailureStore store = this.store();
        store.save(
            List.of(
                SignInFailureStoreContract.key("old", "192.0.2.7", 1),
                new SignInFailure(
                    SignInFailure.API_KEY, "recent", "192.0.2.7", "key-1", "api_key_expired",
                    true, "", "repository", 1L, 0L,
                    SignInFailureStoreContract.START.plusSeconds(3600),
                    SignInFailureStoreContract.START.plusSeconds(3600), 0, null, ""
                )
            )
        );
        MatcherAssert.assertThat(
            "one line is removed",
            store.purge(SignInFailureStoreContract.START.plusSeconds(60)), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the recent one stays",
            store.list(SignInFailureStoreContract.all(SignInFailure.API_KEY)).items()
                .stream().map(SignInFailure::username).toList(),
            new IsEqual<>(List.of("recent"))
        );
    }

    @Test
    void overlongTextsAreCutNotRefused() {
        final SignInFailureStore store = this.store();
        store.save(
            List.of(
                new SignInFailure(
                    SignInFailure.PASSWORD, "alice", "192.0.2.7", "", "wrong_credentials",
                    true, "a".repeat(5000), "repository", 1L, 0L,
                    SignInFailureStoreContract.START, SignInFailureStoreContract.START, 0,
                    null, ""
                )
            )
        );
        MatcherAssert.assertThat(
            store.list(SignInFailureStoreContract.all(SignInFailure.PASSWORD)).items().get(0)
                .userAgent().length(),
            new IsEqual<>(512)
        );
    }

    /**
     * A rejected key of a user from an address, first and last seen at
     * {@link #START}.
     *
     * @param user User name
     * @param address Client address
     * @param hits Times rejected
     * @return Event
     */
    static SignInFailure key(final String user, final String address, final long hits) {
        return new SignInFailure(
            SignInFailure.API_KEY, user, address, "key-1", "api_key_expired", true,
            "Apache-Maven/3.9.9", "repository", hits, 0L, SignInFailureStoreContract.START,
            SignInFailureStoreContract.START, 0, null, ""
        );
    }

    private static SignInFailureStore.Query all(final String kind) {
        return new SignInFailureStore.Query(kind, "", false, "", 100, 0);
    }
}
