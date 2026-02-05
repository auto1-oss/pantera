/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.http;

import com.artipie.asto.Content;
import com.artipie.docker.error.DeniedError;
import com.artipie.docker.error.UnauthorizedError;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.RsStatus;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;

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
            .thenCompose(response -> {
                if (response.status() == RsStatus.UNAUTHORIZED) {
                    // CRITICAL: Drain original response body before creating new response
                    return drainBody(response).thenApply(ignored ->
                        ResponseBuilder.unauthorized()
                            .headers(response.headers())
                            .jsonBody(new UnauthorizedError().json())
                            .build()
                    );
                }
                if (response.status() == RsStatus.PROXY_AUTHENTICATION_REQUIRED) {
                    return drainBody(response).thenApply(ignored ->
                        ResponseBuilder.proxyAuthenticationRequired()
                            .headers(response.headers())
                            .jsonBody(new UnauthorizedError().json())
                            .build()
                    );
                }
                if (response.status() == RsStatus.FORBIDDEN) {
                    return drainBody(response).thenApply(ignored ->
                        ResponseBuilder.forbidden()
                            .headers(response.headers())
                            .jsonBody(new DeniedError().json())
                            .build()
                    );
                }
                return CompletableFuture.completedFuture(response);
            });
    }

    /**
     * Drain response body to prevent resource leaks.
     * @param response Response to drain
     * @return Future completed when body is drained
     */
    private static CompletableFuture<Void> drainBody(final Response response) {
        if (response.body() != null) {
            return response.body().asBytesFuture()
                .<Void>thenApply(bytes -> null)
                .exceptionally(err -> null);
        }
        return CompletableFuture.completedFuture(null);
    }
}
