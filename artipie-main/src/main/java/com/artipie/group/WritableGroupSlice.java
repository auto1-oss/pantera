/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Write-through group slice.
 * Routes write operations (PUT, POST, DELETE) to a designated write target member,
 * while read operations (GET, HEAD) use normal group resolution.
 *
 * @since 1.20.13
 */
public final class WritableGroupSlice implements Slice {

    /**
     * Delegate for read operations (group resolution).
     */
    private final Slice readDelegate;

    /**
     * Write target slice for PUT/POST/DELETE.
     */
    private final Slice writeTarget;

    /**
     * Ctor.
     * @param readDelegate Group slice for reads
     * @param writeTarget Target slice for writes
     */
    public WritableGroupSlice(final Slice readDelegate, final Slice writeTarget) {
        this.readDelegate = Objects.requireNonNull(readDelegate, "readDelegate");
        this.writeTarget = Objects.requireNonNull(writeTarget, "writeTarget");
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final String method = line.method().value();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return this.readDelegate.response(line, headers, body);
        }
        if ("PUT".equals(method) || "POST".equals(method) || "DELETE".equals(method)) {
            return this.writeTarget.response(line, headers, body);
        }
        return CompletableFuture.completedFuture(
            ResponseBuilder.methodNotAllowed().build()
        );
    }
}
