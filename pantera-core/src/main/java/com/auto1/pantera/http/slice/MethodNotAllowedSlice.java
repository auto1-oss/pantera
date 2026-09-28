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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;

/**
 * Answers {@code 405 Method Not Allowed} with an {@code Allow} header.
 *
 * <p>The request body is drained with {@link Content#discard()}, never
 * materialised: a refusal must not buffer a body the client may have
 * declared (or sent) at gigabytes just to answer 405.</p>
 *
 * @since 2.2.9
 */
public final class MethodNotAllowedSlice implements Slice {

    /**
     * Value of the {@code Allow} header.
     */
    private final String allow;

    /**
     * Ctor.
     * @param allow Allowed methods, e.g. {@code "GET, HEAD"}
     */
    public MethodNotAllowedSlice(final String allow) {
        this.allow = allow;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return body.discard().thenApply(
            ignored -> ResponseBuilder.methodNotAllowed().header("Allow", this.allow).build()
        );
    }
}
