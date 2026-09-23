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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link OAuthLoginSlice} -- the legacy {@code npm login} PUT.
 */
final class OAuthLoginSliceTest {

    /**
     * Path npm sends the couch login to.
     */
    private static final String PATH = "/-/user/org.couchdb.user:alice";

    @Test
    void issuesAnApiTokenForValidCredentials() {
        final Response response = OAuthLoginSliceTest.login(
            "{\"_id\":\"org.couchdb.user:alice\",\"name\":\"alice\","
                + "\"password\":\"secret\",\"type\":\"user\",\"roles\":[]}"
        );
        MatcherAssert.assertThat(
            "valid credentials are answered 201",
            response.status(), new IsEqual<>(RsStatus.CREATED)
        );
        final JsonObject body = Json.createReader(
            new StringReader(response.body().asString())
        ).readObject();
        MatcherAssert.assertThat(
            "the token is the API token issued for the authenticated user",
            body.getString("token"), new IsEqual<>("api:alice:npm login")
        );
        MatcherAssert.assertThat(
            "ok is true",
            body.getBoolean("ok"), new IsEqual<>(true)
        );
    }

    @Test
    void rejectsAWrongPassword() {
        MatcherAssert.assertThat(
            OAuthLoginSliceTest.login("{\"name\":\"alice\",\"password\":\"wrong\"}").status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void rejectsABodyWithoutCredentials() {
        MatcherAssert.assertThat(
            OAuthLoginSliceTest.login("{\"hostname\":\"laptop\"}").status(),
            new IsEqual<>(RsStatus.BAD_REQUEST)
        );
    }

    /**
     * Send the couch login PUT with no Authorization header.
     * @param body Request body
     * @return Response
     */
    private static Response login(final String body) {
        final Authentication auth = new Authentication() {
            @Override
            public Optional<AuthUser> user(final String name, final String pass) {
                final Optional<AuthUser> user;
                if ("alice".equals(name) && "secret".equals(pass)) {
                    user = Optional.of(new AuthUser("alice", "local"));
                } else {
                    user = Optional.empty();
                }
                return user;
            }
        };
        final Tokens tokens = new Tokens() {
            @Override
            public TokenAuthentication auth() {
                return token -> CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public String generate(final AuthUser user) {
                return "access:" + user.name();
            }

            @Override
            public String issueApiToken(final AuthUser user, final String label) {
                return String.format("api:%s:%s", user.name(), label);
            }
        };
        return new OAuthLoginSlice(auth, tokens).response(
            new RequestLine(RqMethod.PUT, OAuthLoginSliceTest.PATH),
            Headers.EMPTY,
            new Content.From(body.getBytes(StandardCharsets.UTF_8))
        ).join();
    }
}
