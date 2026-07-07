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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link CombinedAuthzSlice}.
 * @since 1.18
 */
class CombinedAuthzSliceTest {

    @Test
    void allowsBasicAuth() {
        final TestAuth basicAuth = new TestAuth("user", "pass");
        final TestTokenAuth tokenAuth = new TestTokenAuth("token123", "tokenuser");
        final Policy<?> policy = Policy.FREE;
        final TestSlice origin = new TestSlice();
        
        final CombinedAuthzSlice slice = new CombinedAuthzSlice(
            origin, basicAuth, tokenAuth, new OperationControl(policy, new AdapterBasicPermission("test", Action.Standard.READ))
        );
        
        final Headers headers = Headers.from(
            new Authorization.Basic("user", "pass")
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test"), headers, Content.EMPTY
        ).toCompletableFuture().join();
        
        MatcherAssert.assertThat(response.status().code(), Matchers.is(200));
        MatcherAssert.assertThat(origin.wasCalled(), Matchers.is(true));
    }

    @Test
    void allowsBearerAuth() {
        final TestAuth basicAuth = new TestAuth("user", "pass");
        final TestTokenAuth tokenAuth = new TestTokenAuth("token123", "tokenuser");
        final Policy<?> policy = Policy.FREE;
        final TestSlice origin = new TestSlice();
        
        final CombinedAuthzSlice slice = new CombinedAuthzSlice(
            origin, basicAuth, tokenAuth, new OperationControl(policy, new AdapterBasicPermission("test", Action.Standard.READ))
        );
        
        final Headers headers = Headers.from(
            new Authorization.Bearer("token123")
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test"), headers, Content.EMPTY
        ).toCompletableFuture().join();
        
        MatcherAssert.assertThat(response.status().code(), Matchers.is(200));
        MatcherAssert.assertThat(origin.wasCalled(), Matchers.is(true));
    }

    /**
     * Regression: package managers submit API tokens as the Basic
     * password ({@code mvn}/{@code npm}/{@code pip} credential files).
     * A valid token bound to the claimed username must authenticate even
     * though the password chain would reject the token string.
     */
    @Test
    void allowsApiTokenAsBasicPassword() {
        final String token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl";
        final TestAuth basicAuth = new TestAuth("user", "pass");
        final TestTokenAuth tokenAuth = new TestTokenAuth(token, "user");
        final TestSlice origin = new TestSlice();
        final CombinedAuthzSlice slice = new CombinedAuthzSlice(
            origin, basicAuth, tokenAuth,
            new OperationControl(
                Policy.FREE, new AdapterBasicPermission("test", Action.Standard.READ)
            )
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test"),
            Headers.from(new Authorization.Basic("user", token)),
            Content.EMPTY
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "a valid API token as the Basic password must authenticate",
            response.status().code(),
            Matchers.is(200)
        );
        MatcherAssert.assertThat(
            "the request must reach the origin slice",
            origin.wasCalled(),
            Matchers.is(true)
        );
    }

    /**
     * A token belonging to a different user must not authenticate the
     * claimed username via the Basic-password path.
     */
    @Test
    void deniesForeignApiTokenAsBasicPassword() {
        final String token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJvdGhlciJ9.c2lnbmF0dXJl";
        final TestAuth basicAuth = new TestAuth("user", "pass");
        final TestTokenAuth tokenAuth = new TestTokenAuth(token, "somebody-else");
        final TestSlice origin = new TestSlice();
        final CombinedAuthzSlice slice = new CombinedAuthzSlice(
            origin, basicAuth, tokenAuth,
            new OperationControl(
                Policy.FREE, new AdapterBasicPermission("test", Action.Standard.READ)
            )
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test"),
            Headers.from(new Authorization.Basic("user", token)),
            Content.EMPTY
        ).toCompletableFuture().join();
        MatcherAssert.assertThat(
            "another user's token must not authenticate this username",
            response.status().code(),
            Matchers.is(401)
        );
        MatcherAssert.assertThat(
            "the request must not reach the origin slice",
            origin.wasCalled(),
            Matchers.is(false)
        );
    }

    @Test
    void deniesInvalidBasicAuth() {
        final TestAuth basicAuth = new TestAuth("user", "pass");
        final TestTokenAuth tokenAuth = new TestTokenAuth("token123", "tokenuser");
        final Policy<?> policy = Policy.FREE;
        final TestSlice origin = new TestSlice();
        
        final CombinedAuthzSlice slice = new CombinedAuthzSlice(
            origin, basicAuth, tokenAuth, new OperationControl(policy, new AdapterBasicPermission("test", Action.Standard.READ))
        );
        
        final Headers headers = Headers.from(
            new Authorization.Basic("user", "wrongpass")
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test"), headers, Content.EMPTY
        ).toCompletableFuture().join();
        
        MatcherAssert.assertThat(response.status().code(), Matchers.is(401));
        MatcherAssert.assertThat(origin.wasCalled(), Matchers.is(false));
    }

    @Test
    void deniesInvalidBearerAuth() {
        final TestAuth basicAuth = new TestAuth("user", "pass");
        final TestTokenAuth tokenAuth = new TestTokenAuth("token123", "tokenuser");
        final Policy<?> policy = Policy.FREE;
        final TestSlice origin = new TestSlice();
        
        final CombinedAuthzSlice slice = new CombinedAuthzSlice(
            origin, basicAuth, tokenAuth, new OperationControl(policy, new AdapterBasicPermission("test", Action.Standard.READ))
        );
        
        final Headers headers = Headers.from(
            new Authorization.Bearer("invalidtoken")
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test"), headers, Content.EMPTY
        ).toCompletableFuture().join();
        
        MatcherAssert.assertThat(response.status().code(), Matchers.is(401));
        MatcherAssert.assertThat(origin.wasCalled(), Matchers.is(false));
    }

    @Test
    void allowsWithFreePolicy() {
        final TestAuth basicAuth = new TestAuth("user", "pass");
        final TestTokenAuth tokenAuth = new TestTokenAuth("token123", "tokenuser");
        final Policy<?> policy = Policy.FREE;
        final TestSlice origin = new TestSlice();
        
        final CombinedAuthzSlice slice = new CombinedAuthzSlice(
            origin, basicAuth, tokenAuth, new OperationControl(policy, new AdapterBasicPermission("test", Action.Standard.READ))
        );
        
        final Headers headers = Headers.from(
            new Authorization.Basic("user", "pass")
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test"), headers, Content.EMPTY
        ).toCompletableFuture().join();
        
        MatcherAssert.assertThat(response.status().code(), Matchers.is(200));
        MatcherAssert.assertThat(origin.wasCalled(), Matchers.is(true));
    }

    /**
     * Test authentication implementation.
     */
    private static final class TestAuth implements Authentication {
        private final String username;
        private final String password;

        TestAuth(final String username, final String password) {
            this.username = username;
            this.password = password;
        }

        @Override
        public Optional<AuthUser> user(final String name, final String pass) {
            if (this.username.equals(name) && this.password.equals(pass)) {
                return Optional.of(new AuthUser(name, "test"));
            }
            return Optional.empty();
        }
    }

    /**
     * Test token authentication implementation.
     */
    private static final class TestTokenAuth implements TokenAuthentication {
        private final String token;
        private final String username;

        TestTokenAuth(final String token, final String username) {
            this.token = token;
            this.username = username;
        }

        @Override
        public CompletionStage<Optional<AuthUser>> user(final String token) {
            if (this.token.equals(token)) {
                return CompletableFuture.completedFuture(Optional.of(new AuthUser(this.username, "test")));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }


    /**
     * Test slice implementation.
     */
    private static final class TestSlice implements Slice {
        private boolean called;

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.called = true;
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        }

        boolean wasCalled() {
            return this.called;
        }
    }
}
