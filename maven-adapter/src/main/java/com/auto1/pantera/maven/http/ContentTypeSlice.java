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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import java.util.concurrent.CompletableFuture;

/**
 * Adds the Maven {@code Content-Type} of the requested file (see
 * {@link ArtifactHeaders}) to a successful response that has none, so
 * proxied {@code .module}, checksum and archive files are typed like the
 * ones a local repository serves (responses also carry
 * {@code X-Content-Type-Options: nosniff}).
 *
 * @since 2.2.9
 */
final class ContentTypeSlice implements Slice {

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Wrapped slice
     */
    ContentTypeSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return this.origin.response(line, headers, body).thenApply(resp -> {
            if (!resp.status().success() || !resp.headers().find("Content-Type").isEmpty()) {
                return resp;
            }
            return ResponseBuilder.from(resp.status())
                .headers(resp.headers())
                .header(ArtifactHeaders.contentType(new KeyFromPath(line.uri().getPath())))
                .body(resp.body())
                .build();
        });
    }
}
