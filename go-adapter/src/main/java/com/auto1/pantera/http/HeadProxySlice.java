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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * HEAD proxy slice for Go.
 *
 * <p>Track 5 Phase 2B: when the requested module file is already in local
 * storage, the HEAD response is built from storage metadata without
 * contacting {@code proxy.golang.org}. Cache-miss still proxies upstream.
 *
 * @since 1.0
 */
final class HeadProxySlice implements Slice {

    /** Upstream client slice (cache-miss fallback). */
    private final Slice remote;

    /**
     * Local storage; consulted first. {@link Optional#empty()} disables the
     * cache-first short-circuit and reverts to pre-Track-5 pass-through.
     */
    private final Optional<Storage> storage;

    /**
     * Ctor. Pass-through HEAD (no cache check).
     *
     * @param remote Remote slice
     */
    HeadProxySlice(final Slice remote) {
        this(remote, Optional.empty());
    }

    /**
     * Cache-first HEAD slice.
     *
     * @param remote  Remote slice (fallback on cache miss).
     * @param storage Local storage consulted first.
     */
    HeadProxySlice(final Slice remote, final Optional<Storage> storage) {
        this.remote = remote;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
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
                EcsLogger.debug("com.auto1.pantera.go")
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
        return this.remote.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(
                resp -> {
                    // CRITICAL: Consume body to prevent Vert.x request leak
                    return resp.body().asBytesFuture().thenApply(ignored -> {
                        if (resp.status().success()) {
                            return ResponseBuilder.ok()
                                .headers(resp.headers())
                                .build();
                        }
                        return ResponseBuilder.notFound().build();
                    });
                }
            );
    }
}
