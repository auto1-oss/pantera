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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.InternalHeaderScrub;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Decorator that unconditionally strips Pantera's internal client-base
 * marker headers ({@link ClientBaseUrl#HEADER}, {@link
 * ClientBaseUrl#ORIGINAL_PATH}) before delegating.
 *
 * <p>{@code SliceByPath} and {@code ApiRoutingSlice} already scrub these on
 * the shared main-port pipeline ({@code MainSlice}) — each stamps its own
 * authoritative value after discarding whatever a client sent. But a
 * repository bound to a dedicated port ({@code VertxMain#startRepos} at
 * boot, and the {@code RepositoryEvents.ADDRESS} hot-reload handler) is
 * handed straight to a listener, bypassing that pipeline entirely. Without
 * this wrapper, a client hitting a dedicated port directly could supply
 * {@link ClientBaseUrl#HEADER} itself and steer the absolute URLs
 * (npm {@code dist.tarball}) the repository slice emits — the same
 * poisoning {@code SliceByPath} prevents on the main port.</p>
 *
 * @since 2.3.0
 */
public final class InternalHeaderScrubSlice implements Slice {

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * @param origin Slice to delegate to once internal markers are scrubbed
     */
    public InternalHeaderScrubSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return this.origin.response(
            line,
            new InternalHeaderScrub(headers).without(
                ClientBaseUrl.HEADER, ClientBaseUrl.ORIGINAL_PATH
            ),
            body
        );
    }
}
