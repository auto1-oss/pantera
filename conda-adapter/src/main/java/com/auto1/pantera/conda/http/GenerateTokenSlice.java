/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.rq.RequestLine;

import javax.json.Json;
import java.util.concurrent.CompletableFuture;

/**
 * Slice for token authorization.
 */
final class GenerateTokenSlice implements Slice {

    /**
     * Authentication.
     */
    private final Authentication auth;

    /**
     * Tokens.
     */
    private final Tokens tokens;

    /**
     * @param auth Authentication
     * @param tokens Tokens
     */
    GenerateTokenSlice(final Authentication auth, final Tokens tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return new BasicAuthScheme(this.auth).authenticate(headers)
            .toCompletableFuture()
            .thenApply(
                result -> {
                    if (result.status() == AuthScheme.AuthStatus.FAILED) {
                        return ResponseBuilder.unauthorized()
                            .header(new WwwAuthenticate(result.challenge()))
                            .build();
                    }
                    return ResponseBuilder.ok()
                        .jsonBody(
                            Json.createObjectBuilder()
                                .add("token", this.tokens.generate(result.user()))
                                .build()
                        )
                        .build();
                }
        );
    }
}
