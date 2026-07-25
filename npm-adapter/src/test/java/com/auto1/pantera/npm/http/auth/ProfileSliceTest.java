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
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.model.User;
import com.auto1.pantera.npm.repository.UserRepository;
import java.io.StringReader;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ProfileSlice} — {@code npm profile get}.
 */
final class ProfileSliceTest {

    @Test
    void returnsUsernameOnlyWithoutAUserRepository() throws Exception {
        final JsonObject body = Json.createReader(
            new StringReader(
                new ProfileSlice().response(
                    new RequestLine(RqMethod.GET, "/-/npm/v1/user"),
                    loginHeaders("alice"), Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        MatcherAssert.assertThat(body.getString("name"), new IsEqual<>("alice"));
        MatcherAssert.assertThat(
            "no email is fabricated when there is no local user record",
            body.containsKey("email"),
            new IsEqual<>(false)
        );
    }

    @Test
    void enrichesWithEmailWhenAUserRepositoryHasIt() throws Exception {
        final UserRepository users = new UserRepository() {
            @Override
            public CompletableFuture<Boolean> exists(final String username) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<User> save(final User user) {
                return CompletableFuture.completedFuture(user);
            }

            @Override
            public CompletableFuture<Optional<User>> findByUsername(final String username) {
                return CompletableFuture.completedFuture(
                    Optional.of(new User("id-1", username, "hash", "alice@example.com", Instant.now()))
                );
            }

            @Override
            public CompletableFuture<Optional<User>> authenticate(
                final String username, final String password
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        final JsonObject body = Json.createReader(
            new StringReader(
                new ProfileSlice(users).response(
                    new RequestLine(RqMethod.GET, "/-/npm/v1/user"),
                    loginHeaders("alice"), Content.EMPTY
                ).join().body().asString()
            )
        ).readObject();
        MatcherAssert.assertThat(body.getString("email"), new IsEqual<>("alice@example.com"));
    }

    @Test
    void putIsAnHonestNoOpNotAnError() {
        MatcherAssert.assertThat(
            new ProfileSlice().response(
                new RequestLine(RqMethod.PUT, "/-/npm/v1/user"),
                loginHeaders("alice"), new Content.From("{\"fullname\":\"Alice\"}".getBytes())
            ).join().status(),
            new IsEqual<>(RsStatus.OK)
        );
    }

    private static Headers loginHeaders(final String username) {
        final Headers headers = new Headers();
        headers.add(AuthzSlice.LOGIN_HDR, username);
        return headers;
    }
}
