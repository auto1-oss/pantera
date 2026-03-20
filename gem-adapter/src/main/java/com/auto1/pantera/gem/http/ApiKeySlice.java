/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.gem.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Responses on api key requests.
 */
final class ApiKeySlice implements Slice {

    /**
     * The users.
     */
    private final Authentication auth;

    /**
     * @param auth Auth.
     */
    ApiKeySlice(final Authentication auth) {
        this.auth = auth;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return new BasicAuthScheme(this.auth)
                .authenticate(headers)
                .thenApply(
                    result -> {
                        if (result.status() == AuthScheme.AuthStatus.AUTHENTICATED) {
                            final Optional<String> key = new RqHeaders(headers, Authorization.NAME)
                                .stream()
                                .filter(val -> val.startsWith(BasicAuthScheme.NAME))
                                .map(val -> val.substring(BasicAuthScheme.NAME.length() + 1))
                                .findFirst();
                            if (key.isPresent()) {
                                return ResponseBuilder.ok()
                                    .textBody(key.get(), StandardCharsets.US_ASCII)
                                    .build();
                            }
                        }
                        return ResponseBuilder.unauthorized().build();
                    }
                ).toCompletableFuture();
    }
}
