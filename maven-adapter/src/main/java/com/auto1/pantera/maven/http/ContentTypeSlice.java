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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Sets the Maven {@code Content-Type} of the requested file (see
 * {@link ArtifactHeaders}) on a successful response. For file kinds Pantera
 * types itself it replaces whatever upstream sent, so a cache miss and a
 * cache hit of one URL answer the same type (R20); other files keep an
 * upstream type and get a guessed one when they have none. So
 * proxied {@code .module}, checksum and archive files are typed like the
 * ones a local repository serves (responses also carry
 * {@code X-Content-Type-Options: nosniff}). Only that header changes: the
 * status, the other headers (notably the {@code Content-Length} of a HEAD
 * answer) and the body pass through unchanged.
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
            if (!resp.status().success()) {
                return resp;
            }
            final Key key = new KeyFromPath(line.uri().getPath());
            final Optional<String> own = ArtifactHeaders.mavenType(key);
            final Response typed;
            if (own.isPresent()) {
                // A file kind Pantera types itself answers the same type
                // from upstream (cache miss) and from the cache (R20).
                typed = ContentTypeSlice.withType(resp, own.get());
            } else if (resp.headers().find("Content-Type").isEmpty()) {
                typed = ContentTypeSlice.withType(
                    resp, ArtifactHeaders.contentType(key).getValue()
                );
            } else {
                typed = resp;
            }
            return typed;
        });
    }

    /**
     * The response with its Content-Type set to the given type. The other
     * headers and the body are kept untouched: rebuilding the body through
     * ResponseBuilder would overwrite Content-Length with the size of a HEAD
     * answer's empty body (0).
     * @param resp Response
     * @param type Content type
     * @return Typed response
     */
    private static Response withType(final Response resp, final String type) {
        final List<Header> kept = new ArrayList<>();
        for (final Header header : resp.headers()) {
            if (!"Content-Type".equalsIgnoreCase(header.getKey())) {
                kept.add(header);
            }
        }
        kept.add(new Header("Content-Type", type));
        return new Response(resp.status(), new Headers(kept), resp.body());
    }
}
