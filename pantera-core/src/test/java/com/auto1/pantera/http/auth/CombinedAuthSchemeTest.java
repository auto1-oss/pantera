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

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Authorization;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link CombinedAuthScheme}.
 *
 * <p>Regression guard for the 2.2.x incident where {@code docker login}
 * with an API token as the Basic password was rejected: registry clients
 * can only submit credentials via Basic (the challenge carries no token
 * endpoint), and the authoritative DB password check refused the JWT
 * string before any token-aware provider could validate it. The scheme
 * must therefore validate token-shaped Basic passwords as JWTs first.
 *
 * @since 2.2.1
 */
final class CombinedAuthSchemeTest {

    /**
     * A structurally JWT-shaped API token.
     */
    private static final String TOKEN =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJjaS1kb2NrZXItcmVhZCJ9.c2lnbmF0dXJl";

    /**
     * Request line — the scheme never inspects it.
     */
    private static final String LINE = "GET http://not/used HTTP/1.1";

    @Test
    void basicWithApiTokenPasswordAuthenticatesAsTokenSubject() {
        final String username = "ci-docker-read";
        final AtomicInteger passwordChecks = new AtomicInteger(0);
        final AuthScheme.Result result = new CombinedAuthScheme(
            (user, pass) -> {
                passwordChecks.incrementAndGet();
                return Optional.empty();
            },
            token -> CompletableFuture.completedFuture(
                Optional.of(new AuthUser(username, "token")).filter(u -> TOKEN.equals(token))
            )
        ).authenticate(
            Headers.from(new Authorization.Basic(username, TOKEN)),
            com.auto1.pantera.http.rq.RequestLine.from(LINE)
        ).toCompletableFuture().join();
        Assertions.assertSame(
            AuthScheme.AuthStatus.AUTHENTICATED, result.status(),
            "a valid API token sent as the Basic password must authenticate"
        );
        MatcherAssert.assertThat(
            "the authenticated identity must be the token subject",
            result.user().name(),
            new IsEqual<>(username)
        );
        MatcherAssert.assertThat(
            "the password chain must never see the token, or an authoritative "
                + "provider could reject it and re-introduce the docker login regression",
            passwordChecks.get(),
            new IsEqual<>(0)
        );
    }

    @Test
    void basicWithForeignUsersTokenIsNotAccepted() {
        final AuthScheme.Result result = new CombinedAuthScheme(
            (user, pass) -> Optional.empty(),
            token -> CompletableFuture.completedFuture(
                Optional.of(new AuthUser("somebody-else", "token"))
            )
        ).authenticate(
            Headers.from(new Authorization.Basic("alice", TOKEN)),
            com.auto1.pantera.http.rq.RequestLine.from(LINE)
        ).toCompletableFuture().join();
        Assertions.assertSame(
            AuthScheme.AuthStatus.FAILED, result.status(),
            "a token belonging to another user must not authenticate the claimed username"
        );
    }

    @Test
    void basicWithJwtLookalikePasswordStillChecksPasswordChain() {
        final String username = "alice";
        final AuthScheme.Result result = new CombinedAuthScheme(
            (user, pass) -> Optional.of(new AuthUser(username, "test"))
                .filter(u -> username.equals(user) && TOKEN.equals(pass)),
            token -> CompletableFuture.completedFuture(Optional.empty())
        ).authenticate(
            Headers.from(new Authorization.Basic(username, TOKEN)),
            com.auto1.pantera.http.rq.RequestLine.from(LINE)
        ).toCompletableFuture().join();
        Assertions.assertSame(
            AuthScheme.AuthStatus.AUTHENTICATED, result.status(),
            "a real password that merely looks like a JWT must keep working"
        );
    }

    @Test
    void basicWithFailingTokenValidatorFallsBackToPasswordChain() {
        final String username = "alice";
        final AuthScheme.Result result = new CombinedAuthScheme(
            (user, pass) -> Optional.of(new AuthUser(username, "test"))
                .filter(u -> TOKEN.equals(pass)),
            token -> CompletableFuture.failedFuture(new IllegalStateException("boom"))
        ).authenticate(
            Headers.from(new Authorization.Basic(username, TOKEN)),
            com.auto1.pantera.http.rq.RequestLine.from(LINE)
        ).toCompletableFuture().join();
        Assertions.assertSame(
            AuthScheme.AuthStatus.AUTHENTICATED, result.status(),
            "a token-validator failure must degrade to the password check, not to a 500"
        );
    }

    @Test
    void basicWithRegularPasswordNeverConsultsTokenAuth() {
        final String username = "alice";
        final AtomicInteger tokenChecks = new AtomicInteger(0);
        final AuthScheme.Result result = new CombinedAuthScheme(
            (user, pass) -> Optional.of(new AuthUser(username, "test"))
                .filter(u -> "letmein".equals(pass)),
            token -> {
                tokenChecks.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
        ).authenticate(
            Headers.from(new Authorization.Basic(username, "letmein")),
            com.auto1.pantera.http.rq.RequestLine.from(LINE)
        ).toCompletableFuture().join();
        Assertions.assertSame(
            AuthScheme.AuthStatus.AUTHENTICATED, result.status(),
            "ordinary password authentication must be unaffected"
        );
        MatcherAssert.assertThat(
            "ordinary passwords must not be sent to the token validator",
            tokenChecks.get(),
            new IsEqual<>(0)
        );
    }

    @Test
    void bearerTokenStillAuthenticates() {
        final AuthScheme.Result result = new CombinedAuthScheme(
            (user, pass) -> Optional.empty(),
            token -> CompletableFuture.completedFuture(
                Optional.of(new AuthUser("ci-docker-read", "token")).filter(t -> TOKEN.equals(token))
            )
        ).authenticate(
            Headers.from(new Authorization.Bearer(TOKEN)),
            com.auto1.pantera.http.rq.RequestLine.from(LINE)
        ).toCompletableFuture().join();
        Assertions.assertSame(
            AuthScheme.AuthStatus.AUTHENTICATED, result.status(),
            "Bearer authentication must be unaffected"
        );
    }
}
