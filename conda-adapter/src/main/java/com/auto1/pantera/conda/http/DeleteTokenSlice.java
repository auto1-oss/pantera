/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.conda.http.auth.TokenAuthScheme;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Delete token slice.
 * <a href="https://api.anaconda.org/docs#/authentication/delete_authentications">Documentation</a>.
 * This slice checks if the token is valid and returns 201 if yes. Token itself is not removed
 * from the Pantera.
 */
final class DeleteTokenSlice implements Slice {

    /**
     * Auth tokens.
     */
    private final Tokens tokens;

    /**
     * Ctor.
     * @param tokens Auth tokens
     */
    DeleteTokenSlice(final Tokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line,
                                                final Headers headers, final Content body) {

        Optional<String> opt = new RqHeaders(headers, Authorization.NAME)
            .stream().findFirst().map(Authorization::new)
            .map(auth -> new Authorization.Token(auth.credentials()).token());
        if (opt.isPresent()) {
            String token = opt.get();
            return this.tokens.auth()
                .user(token)
                .toCompletableFuture()
                .thenApply(
                    user -> user.isPresent()
                        ? ResponseBuilder.created().build()
                        : ResponseBuilder.badRequest().build()
                );
        }
        return ResponseBuilder.unauthorized()
            .header(new WwwAuthenticate(TokenAuthScheme.NAME))
            .completedFuture();
    }
}
