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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter that turns a HEAD request into a GET against the wrapped
 * slice, then drops the body before returning. RFC 9110 §9.3.2 lets
 * a server respond to HEAD by computing the GET response and omitting
 * the body — the underlying GET path stays the single source of truth for
 * "does this resource exist and what are its headers".
 *
 * <p>The body is discarded; status and headers are forwarded to the caller.
 * The Content-Length header (if present in the GET response) lets HEAD
 * callers size-check without downloading.</p>
 *
 * @since 2.2.9
 */
final class HeadAsGetSlice implements Slice {

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Origin slice answering GET
     */
    HeadAsGetSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final RequestLine asGet = new RequestLine(RqMethod.GET, line.uri(), line.version());
        return this.origin.response(asGet, headers, body).thenCompose(
            resp -> resp.body().discard().thenApply(
                ignored -> new Response(resp.status(), resp.headers(), Content.EMPTY)
            )
        );
    }
}
