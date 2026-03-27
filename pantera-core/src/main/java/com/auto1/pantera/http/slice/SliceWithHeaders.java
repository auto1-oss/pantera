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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Decorator for {@link Slice} which adds headers to the origin.
 */
public final class SliceWithHeaders implements Slice {

    private final Slice origin;
    private final Headers additional;

    /**
     * @param origin Origin slice
     * @param headers Headers
     */
    public SliceWithHeaders(Slice origin, Headers headers) {
        this.origin = origin;
        this.additional = headers;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        return origin.response(line, headers, body)
            .thenApply(
                res -> {
                    ResponseBuilder builder = ResponseBuilder.from(res.status())
                        .headers(res.headers())
                        .body(res.body());
                    additional.stream().forEach(h -> builder.header(h.getKey(), h.getValue()));
                    return builder.build();
                }
            );
    }
}
