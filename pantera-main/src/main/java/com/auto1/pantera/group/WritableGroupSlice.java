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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

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
