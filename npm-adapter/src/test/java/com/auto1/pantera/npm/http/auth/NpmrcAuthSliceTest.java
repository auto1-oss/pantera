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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Test for {@link NpmrcAuthSlice}.
 */
class NpmrcAuthSliceTest {

    /**
     * Mock Tokens implementation for testing.
     */
    private static final Tokens MOCK_TOKENS = new Tokens() {
        @Override
        public TokenAuthentication auth() {
            return tkn -> CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public String generate(AuthUser user) {
            return "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.test.jwt.token";
        }
    };

    @Test
    void returnsUnauthorizedWithoutAuth() throws Exception {
        final NpmrcAuthSlice slice = new NpmrcAuthSlice(
            new URL("https://pantera.example.com/npm_repo"),
            (user, pass) -> Optional.empty(),
            MOCK_TOKENS,
            MOCK_TOKENS.auth()
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/.auth"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            response,
            new RsHasStatus(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void generatesNpmrcForGlobalAuth() throws Exception {
        final String username = "testuser";
        final String password = "testpass";
        
        final NpmrcAuthSlice slice = new NpmrcAuthSlice(
            new URL("https://pantera.example.com/npm_repo"),
            (user, pass) -> user.equals(username) && pass.equals(password)
                ? Optional.of(new AuthUser(username, "test"))
                : Optional.empty(),
            MOCK_TOKENS,
            MOCK_TOKENS.auth()
        );

        final String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/.auth"),
            Headers.from(new Authorization(basicAuth)),
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            response,
            new RsHasStatus(RsStatus.OK)
        );

        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );

        MatcherAssert.assertThat(
            "Should contain registry URL",
            body,
            Matchers.containsString("registry=https://pantera.example.com/npm_repo")
        );

        MatcherAssert.assertThat(
            "Should contain auth token",
            body,
            Matchers.containsString("//pantera.example.com/:_authToken=")
        );

        MatcherAssert.assertThat(
            "Should contain username",
            body,
            Matchers.containsString("//pantera.example.com/:username=testuser")
        );

        MatcherAssert.assertThat(
            "Should contain email",
            body,
            Matchers.containsString("//pantera.example.com/:email=testuser@pantera.local")
        );

        MatcherAssert.assertThat(
            "Should contain always-auth",
            body,
            Matchers.containsString("//pantera.example.com/:always-auth=true")
        );
    }

    @Test
    void generatesNpmrcForScopedAuth() throws Exception {
        final String username = "testuser";
        final String password = "testpass";
        
        final NpmrcAuthSlice slice = new NpmrcAuthSlice(
            new URL("https://pantera.example.com/npm_repo"),
            (user, pass) -> user.equals(username) && pass.equals(password)
                ? Optional.of(new AuthUser(username, "test"))
                : Optional.empty(),
            MOCK_TOKENS,
            MOCK_TOKENS.auth()
        );

        final String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/.auth/@mycompany"),
            Headers.from(new Authorization(basicAuth)),
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            response,
            new RsHasStatus(RsStatus.OK)
        );

        final String body = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );

        MatcherAssert.assertThat(
            "Should contain scoped registry",
            body,
            Matchers.containsString("@mycompany:registry=https://pantera.example.com/npm_repo")
        );

        MatcherAssert.assertThat(
            "Should contain auth token",
            body,
            Matchers.containsString("//pantera.example.com/:_authToken=")
        );
    }
}
