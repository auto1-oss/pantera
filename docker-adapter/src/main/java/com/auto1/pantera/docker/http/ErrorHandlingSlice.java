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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.error.DockerError;
import com.auto1.pantera.docker.error.UnsupportedError;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Slice that handles exceptions in origin slice by sending well-formed error responses.
 */
final class ErrorHandlingSlice implements Slice {

    private final Slice origin;

    /**
     * @param origin Origin.
     */
    ErrorHandlingSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        try {
            return this.origin.response(line, headers, body)
                .handle((response, error) -> {
                    CompletableFuture<Response> res;
                    if (error != null) {
                        res = handle(error)
                            .map(CompletableFuture::completedFuture)
                            .orElseGet(() -> CompletableFuture.failedFuture(error));
                        } else {
                        res = CompletableFuture.completedFuture(response);
                        }
                    return res;
                    }
                ).thenCompose(Function.identity());
        } catch (Exception error) {
            return handle(error)
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> CompletableFuture.failedFuture(error));
        }
    }

    /**
     * Translates throwable to error response.
     *
     * @param throwable Throwable to translate.
     * @return Result response, empty that throwable cannot be handled.
     */
    private static Optional<Response> handle(final Throwable throwable) {
        if (throwable instanceof DockerError error) {
            return Optional.of(ResponseBuilder.badRequest().jsonBody(error.json()).build());
        }
        if (throwable instanceof UnsupportedOperationException) {
            return Optional.of(
                ResponseBuilder.methodNotAllowed().jsonBody(new UnsupportedError().json()).build()
            );
        }
        if (throwable instanceof CompletionException) {
            return Optional.ofNullable(throwable.getCause()).flatMap(ErrorHandlingSlice::handle);
        }
        return Optional.empty();
    }
}
