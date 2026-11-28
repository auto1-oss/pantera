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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ApiKeyOnlyAuth}: a credential that has the form of an
 * API key is checked by the key provider alone, whatever its answer, and
 * never reaches a password provider (which may forward it to an external
 * identity provider as if it were a password).
 */
final class ApiKeyOnlyAuthTest {

    /**
     * A credential with the form of an API key: three non-empty
     * dot-separated parts, the first one starting with {@code eyJ}.
     */
    private static final String KEY =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl";

    @Test
    void rejectedKeyReachesNoPasswordProvider() {
        final Recording keys = new Recording(false);
        final Recording passwords = new Recording(true);
        final Optional<AuthUser> user =
            new ApiKeyOnlyAuth(keys, passwords).user("alice", ApiKeyOnlyAuthTest.KEY);
        MatcherAssert.assertThat(
            "a key the key provider rejects is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the key provider is asked once",
            keys.calls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "no password provider sees the rejected key",
            passwords.calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void acceptedKeyReachesNoPasswordProvider() {
        final Recording keys = new Recording(true);
        final Recording passwords = new Recording(true);
        final Optional<AuthUser> user =
            new ApiKeyOnlyAuth(keys, passwords).user("alice", ApiKeyOnlyAuthTest.KEY);
        MatcherAssert.assertThat(
            "the key provider's user is the answer",
            user.map(AuthUser::name), new IsEqual<>(Optional.of("alice"))
        );
        MatcherAssert.assertThat(
            "no password provider is asked for an accepted key",
            passwords.calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void passwordReachesNoKeyProvider() {
        final Recording keys = new Recording(true);
        final Recording passwords = new Recording(true);
        final Optional<AuthUser> user =
            new ApiKeyOnlyAuth(keys, passwords).user("alice", "correct horse");
        MatcherAssert.assertThat(
            "a password is answered by the password providers",
            user.map(AuthUser::name), new IsEqual<>(Optional.of("alice"))
        );
        MatcherAssert.assertThat(
            "the password providers are asked once",
            passwords.calls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the key provider is not asked for a password",
            keys.calls.get(), new IsEqual<>(0)
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "",
        "eyJ",
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9",
        "eyJhbGciOiJSUzI1NiJ9..c2lnbmF0dXJl",
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.",
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2ln.extra",
        "EYJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl",
        " eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl",
        "p.a.ss"
    })
    void credentialWithoutTheKeyFormIsAPassword(final String credential) {
        final Recording keys = new Recording(true);
        final Recording passwords = new Recording(false);
        new ApiKeyOnlyAuth(keys, passwords).user("alice", credential);
        MatcherAssert.assertThat(
            "the password providers are asked",
            passwords.calls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the key provider is not asked",
            keys.calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void keyIsRejectedByAChainWithoutKeyProvider() {
        final Recording passwords = new Recording(true);
        final Optional<AuthUser> user =
            new ApiKeyOnlyAuth(passwords).user("alice", ApiKeyOnlyAuthTest.KEY);
        MatcherAssert.assertThat(
            "without a key provider a key is a failed login",
            user.isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "no password provider sees the key",
            passwords.calls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void keyIsCheckedForTheNameItWasSentWith() {
        final Recording keys = new Recording(true);
        new ApiKeyOnlyAuth(keys, new Recording(true)).user("bob", ApiKeyOnlyAuthTest.KEY);
        MatcherAssert.assertThat(
            keys.lastName, new IsEqual<>("bob")
        );
    }

    /**
     * Provider that counts its calls and gives one fixed answer.
     */
    private static final class Recording implements Authentication {

        /**
         * Number of calls.
         */
        private final AtomicInteger calls = new AtomicInteger();

        /**
         * Whether credentials are accepted.
         */
        private final boolean accepts;

        /**
         * User name of the last call.
         */
        private volatile String lastName;

        Recording(final boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            this.calls.incrementAndGet();
            this.lastName = name;
            final Optional<AuthUser> res;
            if (this.accepts) {
                res = Optional.of(new AuthUser(name, "test"));
            } else {
                res = Optional.empty();
            }
            return res;
        }
    }
}
