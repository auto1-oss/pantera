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
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.LastModified;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Answers {@code HEAD} for a slice that only knows {@code GET}: the status
 * and headers the matching {@code GET} would produce, without the body.
 *
 * <p>Generic HTTP clients probe a download with {@code HEAD} before fetching
 * it (a CI tool installer validating its URL, {@code curl -I}); a read route
 * bound to {@code GET} alone answers them 404 or 405 for a file that
 * exists.</p>
 *
 * <p>The {@code GET} body is drained, never held in memory, so its buffers
 * and the storage read behind it are released. When that body knows its
 * modification time ({@link Content#lastModified()}) and the answer carries
 * no {@code Last-Modified}, the header is set from it, so the {@code HEAD}
 * is dated like the {@code GET} without a storage lookup. Every other method
 * passes through untouched.</p>
 *
 * @since 2.2.12
 */
public final class HeadAsGetSlice implements Slice {

    /**
     * Slice that answers {@code GET}.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Slice that answers {@code GET}
     */
    public HeadAsGetSlice(final Slice origin) {
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
                resp -> resp.body().discard().thenApply(
                    ignored -> new Response(
                        resp.status(), HeadAsGetSlice.dated(resp), Content.EMPTY
                    )
                )
            );
        } else {
            res = this.origin.response(line, headers, body);
        }
        return res;
    }

    /**
     * Headers of the {@code GET} answer, with the modification time its body
     * carries when the answer has none.
     * @param resp Answer to the {@code GET}
     * @return Headers for the {@code HEAD} answer
     */
    private static Headers dated(final Response resp) {
        final Headers headers;
        if (resp.status().success() && resp.headers().find(LastModified.NAME).isEmpty()) {
            final Instant now = Instant.now();
            headers = resp.body().lastModified()
                .map(carried -> carried.isAfter(now) ? now : carried)
                .map(carried -> resp.headers().copy().add(new LastModified(carried)))
                .orElseGet(resp::headers);
        } else {
            headers = resp.headers();
        }
        return headers;
    }
}
