/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.conda.http.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.auto1.pantera.http.rq.RequestLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link TokenAuthScheme}.
 * @since 0.5
 */
class TokenAuthSchemeTest {

    /**
     * Test token.
     */
    private static final String TKN = "abc123";

    @Test
    void canAuthorizeByHeader() {
        Assertions.assertSame(
            new TokenAuthScheme(new TestTokenAuth()).authenticate(
                Headers.from(new Authorization.Token(TokenAuthSchemeTest.TKN)),
                RequestLine.from("GET /not/used HTTP/1.1")
            ).toCompletableFuture().join().status(),
            AuthScheme.AuthStatus.AUTHENTICATED
        );
    }

    @Test
    void canAuthorizeByRqLine() {
        Assertions.assertSame(
            new TokenAuthScheme(new TestTokenAuth()).authenticate(
                Headers.EMPTY,
                RequestLine.from(String.format("GET /t/%s/my-repo/repodata.json HTTP/1.1", TokenAuthSchemeTest.TKN))
            ).toCompletableFuture().join().status(),
            AuthScheme.AuthStatus.AUTHENTICATED
        );
    }

    @Test
    void doesAuthorizeAsAnonymousIfTokenIsNotPresent() {
        final AuthScheme.Result result = new TokenAuthScheme(new TestTokenAuth()).authenticate(
            Headers.EMPTY,
            RequestLine.from("GET /any HTTP/1.1")
        ).toCompletableFuture().join();
        Assertions.assertSame(
            result.status(),
            AuthScheme.AuthStatus.NO_CREDENTIALS
        );
        Assertions.assertTrue(result.user().isAnonymous());
    }

    @Test
    void doesNotAuthorizeByWrongTokenInHeader() {
        Assertions.assertSame(
            new TokenAuthScheme(new TestTokenAuth()).authenticate(
                Headers.from(new Authorization.Token("098xyz")),
                RequestLine.from("GET /ignored HTTP/1.1")
            ).toCompletableFuture().join().status(),
            AuthScheme.AuthStatus.FAILED
        );
    }

    @Test
    void doesNotAuthorizeByWrongTokenInRqLine() {
        Assertions.assertSame(
            new TokenAuthScheme(new TestTokenAuth()).authenticate(
                Headers.EMPTY,
                RequestLine.from("GET /t/any/my-conda/repodata.json HTTP/1.1")
            ).toCompletableFuture().join().status(),
            AuthScheme.AuthStatus.FAILED
        );
    }

    /**
     * Test token auth.
     * @since 0.5
     */
    private static final class TestTokenAuth implements TokenAuthentication {

        @Override
        public CompletionStage<Optional<AuthUser>> user(final String token) {
            Optional<AuthUser> res = Optional.empty();
            if (token.equals(TokenAuthSchemeTest.TKN)) {
                res = Optional.of(new AuthUser("Alice", "test"));
            }
            return CompletableFuture.completedFuture(res);
        }
    }

}
