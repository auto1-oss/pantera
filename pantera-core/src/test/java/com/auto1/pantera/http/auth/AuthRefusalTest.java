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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.http.RsStatus;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AuthRefusal} and {@link PasswordCheck}.
 */
final class AuthRefusalTest {

    @Test
    void notedRefusalIsTakenOnce() {
        new AuthRefusal("mfa_required").note();
        MatcherAssert.assertThat(
            "the noted refusal is handed out",
            AuthRefusal.take().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("mfa_required"))
        );
        MatcherAssert.assertThat(
            "taking it clears it",
            AuthRefusal.take().isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void refusalWithoutStatusIsAPlainFailedLogin() {
        final AuthRefusal refusal = new AuthRefusal("mfa_required");
        MatcherAssert.assertThat(
            "the status is 401",
            refusal.status(), new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
        MatcherAssert.assertThat(
            "there is no wait time",
            refusal.retryAfter(), new IsEqual<>(OptionalLong.empty())
        );
    }

    @Test
    void refusalNotedOnAnotherThreadIsNotSeenHere() throws Exception {
        final ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> new AuthRefusal("account_locked").note()).get();
            MatcherAssert.assertThat(
                AuthRefusal.take().isPresent(), new IsEqual<>(false)
            );
        } finally {
            other.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Mfa_Required", "mfa required", "mfa-required", "ünlock"})
    void reasonMustBeALowerCaseToken(final String reason) {
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> new AuthRefusal(reason)
        );
    }

    @Test
    void negativeWaitTimeIsRejected() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new AuthRefusal("login_delayed", RsStatus.TOO_MANY_REQUESTS, -1L, "wait")
        );
    }

    @Test
    void checkReportsTheRefusalTheChainNoted() {
        final PasswordCheck.Outcome outcome = new PasswordCheck(
            (name, pass) -> {
                new AuthRefusal("mfa_required").note();
                return Optional.empty();
            }
        ).now("alice", "secret");
        MatcherAssert.assertThat(
            "the login failed",
            outcome.user().isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the refusal is part of the outcome",
            outcome.refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("mfa_required"))
        );
        MatcherAssert.assertThat(
            "nothing stays on the thread",
            AuthRefusal.take().isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void checkDropsARefusalWhenTheLoginSucceeds() {
        final PasswordCheck.Outcome outcome = new PasswordCheck(
            (name, pass) -> {
                new AuthRefusal("mfa_required").note();
                return Optional.of(new AuthUser(name, "test"));
            }
        ).now("alice", "secret");
        MatcherAssert.assertThat(
            "the login succeeded",
            outcome.user().map(AuthUser::name), new IsEqual<>(Optional.of("alice"))
        );
        MatcherAssert.assertThat(
            "a provider's refusal does not stick to an accepted login",
            outcome.refusal().isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void checkIgnoresARefusalLeftOnTheThreadByEarlierWork() {
        new AuthRefusal("account_locked").note();
        final PasswordCheck.Outcome outcome = new PasswordCheck(
            (name, pass) -> Optional.empty()
        ).now("alice", "secret");
        MatcherAssert.assertThat(
            outcome.refusal().isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void checkClearsTheThreadWhenTheChainThrows() {
        final PasswordCheck check = new PasswordCheck(
            (name, pass) -> {
                new AuthRefusal("mfa_required").note();
                throw new IllegalStateException("provider failed");
            }
        );
        Assertions.assertThrows(
            IllegalStateException.class, () -> check.now("alice", "secret")
        );
        MatcherAssert.assertThat(
            "a failed check leaves nothing for the next request on this thread",
            AuthRefusal.take().isPresent(), new IsEqual<>(false)
        );
    }

    @Test
    void asyncCheckReportsTheRefusal() {
        final CompletableFuture<PasswordCheck.Outcome> outcome = new PasswordCheck(
            (name, pass) -> {
                new AuthRefusal("mfa_required").note();
                return Optional.empty();
            }
        ).run("alice", "secret");
        MatcherAssert.assertThat(
            outcome.join().refusal().map(AuthRefusal::reason),
            new IsEqual<>(Optional.of("mfa_required"))
        );
    }
}
