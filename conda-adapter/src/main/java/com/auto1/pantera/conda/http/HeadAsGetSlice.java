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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.concurrent.CompletableFuture;

/**
 * Answers HEAD with the status and headers GET would produce, without the
 * body (RFC 9110 section 9.3.2), so HEAD reflects whether a resource really
 * exists and applies the same authentication as GET. libmamba probes
 * {@code repodata.json.zst} / {@code repodata_shards.msgpack.zst} with HEAD
 * and uses them when HEAD claims they exist.
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
     * @param origin Origin slice
     */
    HeadAsGetSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> res;
        if (line.method() == RqMethod.HEAD) {
            res = this.origin.response(
                new RequestLine(RqMethod.GET, line.uri(), line.version()), headers, body
            ).thenCompose(
                rsp -> rsp.body().discard().thenApply(
                    ignored -> new Response(rsp.status(), rsp.headers(), Content.EMPTY)
                )
            );
        } else {
            res = this.origin.response(line, headers, body);
        }
        return res;
    }
}
