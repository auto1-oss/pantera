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
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.rq.RequestLine;

import javax.json.Json;
import javax.json.JsonStructure;
import java.io.StringReader;
import java.util.concurrent.CompletableFuture;

/**
 * Slice to handle `GET /user` request.
 */
final class GetUserSlice implements Slice {

    /**
     * Authentication.
     */
    private final AuthScheme scheme;

    /**
     * Ctor.
     * @param scheme Authentication
     */
    GetUserSlice(final AuthScheme scheme) {
        this.scheme = scheme;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line, final Headers headers,
                                                final Content body) {
        return this.scheme.authenticate(headers, line)
            .toCompletableFuture()
            .thenApply(
                result -> {
                    if (result.status() != AuthScheme.AuthStatus.FAILED) {
                        return ResponseBuilder.ok()
                            .jsonBody(GetUserSlice.json(result.user().name()))
                            .build();
                    }
                    return ResponseBuilder.unauthorized()
                        .header(new WwwAuthenticate(result.challenge()))
                        .build();
                }
            );
    }

    /**
     * Json response with user info.
     * @param name Username
     * @return User info as JsonStructure
     */
    private static JsonStructure json(final String name) {
        return Json.createReader(
            new StringReader(
                String.join(
                    "\n",
                    "{",
                    "  \"company\": \"Pantera\",",
                    "  \"created_at\": \"2020-08-01 13:06:29.212000+00:00\",",
                    "  \"description\": \"\",",
                    "  \"location\": \"\",",
                    String.format("  \"login\": \"%s\",", name),
                    String.format("  \"name\": \"%s\",", name),
                    "  \"url\": \"\",",
                    "  \"user_type\": \"user\"",
                    "}"
                )
            )
        ).read();
    }
}
