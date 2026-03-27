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
