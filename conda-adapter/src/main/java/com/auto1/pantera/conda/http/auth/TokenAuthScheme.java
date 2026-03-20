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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conda token auth scheme.
 */
public final class TokenAuthScheme implements AuthScheme {

    /**
     * Token authentication prefix.
     */
    public static final String NAME = "token";

    /**
     * Request line pattern.
     */
    private static final Pattern PTRN = Pattern.compile("/t/([^/]*)/.*");

    /**
     * Token authentication.
     */
    private final TokenAuthentication auth;

    /**
     * Ctor.
     * @param auth Token authentication
     */
    public TokenAuthScheme(final TokenAuthentication auth) {
        this.auth = auth;
    }

    @Override
    public CompletionStage<Result> authenticate(
        final Headers headers,
        final RequestLine line) {
        if (line == null) {
            throw new IllegalArgumentException("Request line cannot be null");
        }
        final CompletionStage<Optional<AuthUser>> fut = new RqHeaders(headers, Authorization.NAME)
            .stream()
            .findFirst()
            .map(this::user)
            .orElseGet(
                () -> {
                    final Matcher mtchr = TokenAuthScheme.PTRN.matcher(line.uri().toString());
                    return mtchr.matches()
                        ? this.auth.user(mtchr.group(1))
                        : CompletableFuture.completedFuture(Optional.of(AuthUser.ANONYMOUS));
                });
        return fut.thenApply(user -> AuthScheme.result(user, TokenAuthScheme.NAME));
    }

    /**
     * Obtains user from authorization header or from request line.
     *
     * @param header Authorization header's value
     * @return User, empty if not authenticated
     */
    private CompletionStage<Optional<AuthUser>> user(final String header) {
        final Authorization atz = new Authorization(header);
        if (TokenAuthScheme.NAME.equals(atz.scheme())) {
            return this.auth.user(
                new Authorization.Token(atz.credentials()).token()
            );
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
