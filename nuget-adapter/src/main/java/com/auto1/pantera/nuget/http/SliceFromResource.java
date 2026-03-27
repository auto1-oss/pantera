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
package com.auto1.pantera.nuget.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.ResponseBuilder;

import java.util.concurrent.CompletableFuture;

/**
 * Slice created from {@link Resource}.
 */
final class SliceFromResource implements Slice {

    /**
     * Origin resource.
     */
    private final Resource origin;

    /**
     * @param origin Origin resource.
     */
    SliceFromResource(final Resource origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final RqMethod method = line.method();
        if (method.equals(RqMethod.GET)) {
            return this.origin.get(headers);
        }
        if (method.equals(RqMethod.PUT)) {
            return this.origin.put(headers, body);
        }
        return ResponseBuilder.methodNotAllowed().completedFuture();
    }
}
