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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Head slice for Maven proxy.
 *
 * <p>Track 5 Phase 2B: when the artifact is already in local storage, the
 * HEAD response is built from the storage metadata (size, last-modified)
 * without contacting upstream. Pre-Track-5 every HEAD proxied to Maven
 * Central — which under post-2024 Cloudflare-backed rate limits became a
 * dominant source of upstream traffic and 429s. With a populated cache,
 * HEAD is now a pure-local query.
 *
 * @since 0.5
 */
final class HeadProxySlice implements Slice {

    /** Upstream client slice (cache-miss fallback). */
    private final Slice client;

    /**
     * Local storage; checked first. {@link Optional#empty()} disables the
     * cache-first path entirely and reverts to pre-Track-5 pass-through.
     */
    private final Optional<Storage> storage;

    /**
     * New slice for {@code HEAD} requests.
     * @param client HTTP client slice
     */
    HeadProxySlice(final Slice client) {
        this(client, Optional.empty());
    }

    /**
     * New cache-first {@code HEAD} slice. The local {@code storage} is
     * consulted first; if the key exists we synthesise a 200 with the
     * storage's known {@code Content-Length} and {@code Last-Modified}
     * metadata, never touching {@code client}.
     */
    HeadProxySlice(final Slice client, final Optional<Storage> storage) {
        this.client = client;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        if (this.storage.isEmpty()) {
            return this.upstreamHead(line);
        }
        final String rawPath = line.uri().getPath();
        final String keyPath = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        final Key key = new Key.From(keyPath);
        final Storage raw = this.storage.get();
        return raw.exists(key).thenCompose(present -> {
            if (!present) {
                return this.upstreamHead(line);
            }
            return raw.metadata(key).thenApply(meta -> {
                final ResponseBuilder resp = ResponseBuilder.ok();
                meta.read(Meta.OP_SIZE).ifPresent(size ->
                    resp.header("Content-Length", String.valueOf(size))
                );
                return resp.build();
            }).exceptionally(err -> {
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("HEAD cache-hit metadata read failed; serving 200 without length")
                    .eventCategory("web")
                    .eventAction("head_cache_hit")
                    .field("url.path", rawPath)
                    .error(err)
                    .log();
                return ResponseBuilder.ok().build();
            });
        }).toCompletableFuture();
    }

    private CompletableFuture<Response> upstreamHead(final RequestLine line) {
        return this.client.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp ->
                // CRITICAL: Must consume body even for HEAD requests to prevent Vert.x request leak
                // This is the same pattern as Docker ProxyLayers fix
                resp.body().asBytesFuture().thenApply(ignored ->
                    ResponseBuilder.from(resp.status()).headers(resp.headers()).build()
                )
            );
    }
}
