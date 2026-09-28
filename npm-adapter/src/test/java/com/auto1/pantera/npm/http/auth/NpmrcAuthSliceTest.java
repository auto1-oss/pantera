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
import com.auto1.pantera.http.headers.ClientBaseUrl;
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
    @Test
    void derivesRegistryFromStampedBaseWhenNoUrlConfigured() throws Exception {
        // 2.2.6: hosted npm no longer requires `url:`. With none configured the
        // emitted .npmrc must follow the base the request actually addressed --
        // this endpoint's dependency on a fixed URL is what used to force every
        // hosted npm repository to pin one hostname.
        final Response response = NpmrcAuthSliceTest.npmrcFor(
            Optional.empty(),
            Headers.from(new Authorization(NpmrcAuthSliceTest.basic()))
                .add(ClientBaseUrl.HEADER, "https://packages.example.com:8443/api/npm/npm-local")
        );
        final String body = new String(response.body().asBytes(), StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            "registry line must follow the addressed base",
            body,
            Matchers.containsString(
                "registry=https://packages.example.com:8443/api/npm/npm-local"
            )
        );
        MatcherAssert.assertThat(
            "auth lines must be keyed by host:port only, never the path",
            body,
            Matchers.containsString("//packages.example.com:8443/:_authToken=")
        );
    }

    @Test
    void derivesRegistryFromHostWhenNothingIsConfiguredOrStamped() throws Exception {
        final Response response = NpmrcAuthSliceTest.npmrcFor(
            Optional.empty(),
            Headers.from(new Authorization(NpmrcAuthSliceTest.basic()))
                .add("Host", "packages.example.com")
        );
        MatcherAssert.assertThat(
            new String(response.body().asBytes(), StandardCharsets.UTF_8),
            Matchers.containsString("registry=http://packages.example.com")
        );
    }

    @Test
    void configuredUrlStillWinsOverTheRequestHost() throws Exception {
        final Response response = NpmrcAuthSliceTest.npmrcFor(
            Optional.of(new URL("https://pinned.example.com/npm_repo")),
            Headers.from(new Authorization(NpmrcAuthSliceTest.basic()))
                .add("Host", "packages.example.com")
        );
        MatcherAssert.assertThat(
            new String(response.body().asBytes(), StandardCharsets.UTF_8),
            Matchers.containsString("registry=https://pinned.example.com/npm_repo")
        );
    }

    @Test
    void npmrcResponseVariesOnHost() throws Exception {
        // The body embeds a Host-derived base, so a shared cache must not
        // cross-serve it between hostnames.
        final Response response = NpmrcAuthSliceTest.npmrcFor(
            Optional.empty(),
            Headers.from(new Authorization(NpmrcAuthSliceTest.basic()))
                .add("Host", "packages.example.com")
        );
        MatcherAssert.assertThat(
            response.headers().single("Vary").getValue(),
            Matchers.equalTo("Host")
        );
    }

    /**
     * Basic-auth header value for the fixture credentials.
     *
     * @return Authorization header value
     */
    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString(
            "testuser:testpass".getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Drive an authenticated {@code GET /.auth} through the slice.
     *
     * @param base Configured {@code url:}, or empty
     * @param headers Request headers
     * @return Response
     */
    private static Response npmrcFor(final Optional<URL> base, final Headers headers) {
        final NpmrcAuthSlice slice = new NpmrcAuthSlice(
            base,
            (user, pass) -> "testuser".equals(user) && "testpass".equals(pass)
                ? Optional.of(new AuthUser("testuser", "test"))
                : Optional.empty(),
            MOCK_TOKENS,
            MOCK_TOKENS.auth()
        );
        return slice.response(
            new RequestLine(RqMethod.GET, "/.auth"), headers, Content.EMPTY
        ).join();
    }
}
