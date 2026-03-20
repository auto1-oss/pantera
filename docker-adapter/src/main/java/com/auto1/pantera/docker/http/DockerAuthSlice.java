/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.error.DeniedError;
import com.auto1.pantera.docker.error.UnauthorizedError;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Slice that wraps origin Slice replacing body with errors JSON in Docker API format
 * for 403 Unauthorized response status.
 */
final class DockerAuthSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * @param origin Origin slice.
     */
    DockerAuthSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return this.origin.response(line, headers, body)
            .thenApply(response -> {
                if (response.status() == RsStatus.UNAUTHORIZED) {
                    return ResponseBuilder.unauthorized()
                        .headers(response.headers())
                        .jsonBody(new UnauthorizedError().json())
                        .build();
                }
                if (response.status() == RsStatus.PROXY_AUTHENTICATION_REQUIRED) {
                    return ResponseBuilder.proxyAuthenticationRequired()
                        .headers(response.headers())
                        .jsonBody(new UnauthorizedError().json())
                        .build();
                }
                if (response.status() == RsStatus.FORBIDDEN) {
                    return ResponseBuilder.forbidden()
                        .headers(response.headers())
                        .jsonBody(new DeniedError().json())
                        .build();
                }
                return response;
            });
    }
}
