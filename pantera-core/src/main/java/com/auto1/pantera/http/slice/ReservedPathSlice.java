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
 * Refuses requests that address the storage lock namespace.
 *
 * <p>Storage-backed locks keep their entries under
 * {@code .pantera-locks/<target>/} in the repository storage (see
 * {@code com.auto1.pantera.asto.lock.storage.Proposals}). A client that
 * could write there would plant a lock entry that blocks every later
 * upload to that target, and one that could delete there would release a
 * lock another upload is holding. The namespace is internal: every request
 * whose path has a {@code .pantera-locks} segment is answered 404, whatever
 * the method or format, and its body is drained.</p>
 *
 * @since 2.2.10
 */
public final class ReservedPathSlice implements Slice {

    /**
     * Lock namespace segment.
     */
    public static final String LOCKS = ".pantera-locks";

    /**
     * Origin.
     */
    private final Slice origin;

    /**
     * Ctor.
     *
     * @param origin Origin slice
     */
    public ReservedPathSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> result;
        if (ReservedPathSlice.reserved(line.uri().getPath())) {
            result = body.discard().toCompletableFuture()
                .thenApply(ignored -> ResponseBuilder.notFound().build());
        } else {
            result = this.origin.response(line, headers, body);
        }
        return result;
    }

    /**
     * Whether a path has a segment inside the lock namespace.
     *
     * @param path Request path
     * @return True when the path must not reach storage
     */
    private static boolean reserved(final String path) {
        boolean found = false;
        for (final String segment : path.split("/")) {
            if (ReservedPathSlice.LOCKS.equals(segment)) {
                found = true;
                break;
            }
        }
        return found;
    }
}
