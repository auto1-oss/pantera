/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Authentication scheme that supports both Basic and Bearer token authentication.
 * @since 1.18
 */
public final class CombinedAuthScheme implements AuthScheme {

    /**
     * Basic authentication.
     */
    private final Authentication basicAuth;

    /**
     * Token authentication.
     */
    private final TokenAuthentication tokenAuth;

    /**
     * Ctor.
     *
     * @param basicAuth Basic authentication.
     * @param tokenAuth Token authentication.
     */
    public CombinedAuthScheme(
        final Authentication basicAuth,
        final TokenAuthentication tokenAuth
    ) {
        this.basicAuth = basicAuth;
        this.tokenAuth = tokenAuth;
    }

    @Override
    public CompletionStage<Result> authenticate(
        final Headers headers,
        final RequestLine line
    ) {
        return new RqHeaders(headers, Authorization.NAME)
            .stream()
            .findFirst()
            .map(Authorization::new)
            .map(
                auth -> {
                    if (BasicAuthScheme.NAME.equals(auth.scheme())) {
                        return this.authenticateBasic(auth);
                    } else if (BearerAuthScheme.NAME.equals(auth.scheme())) {
                        return this.authenticateBearer(auth);
                    }
                    return CompletableFuture.completedFuture(
                        AuthScheme.result(
                            AuthUser.ANONYMOUS,
                            String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                                BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                        )
                    );
                }
            )
            .orElseGet(
                () -> CompletableFuture.completedFuture(
                    AuthScheme.result(
                        AuthUser.ANONYMOUS,
                        String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                            BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                    )
                )
            );
    }

    /**
     * Authenticate using Basic authentication.
     *
     * @param auth Authorization header
     * @return Authentication result
     */
    private CompletionStage<AuthScheme.Result> authenticateBasic(final Authorization auth) {
        final Authorization.Basic basic = new Authorization.Basic(auth.credentials());
        final Optional<AuthUser> user = this.basicAuth.user(basic.username(), basic.password());
        return CompletableFuture.completedFuture(
            AuthScheme.result(
                user,
                String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                    BasicAuthScheme.NAME, BearerAuthScheme.NAME)
            )
        );
    }

    /**
     * Authenticate using Bearer token authentication.
     *
     * @param auth Authorization header
     * @return Authentication result
     */
    private CompletionStage<AuthScheme.Result> authenticateBearer(final Authorization auth) {
        return this.tokenAuth.user(new Authorization.Bearer(auth.credentials()).token())
            .thenApply(
                user -> AuthScheme.result(
                    user,
                    String.format("%s realm=\"pantera\", %s realm=\"pantera\"",
                        BasicAuthScheme.NAME, BearerAuthScheme.NAME)
                )
            );
    }
}
