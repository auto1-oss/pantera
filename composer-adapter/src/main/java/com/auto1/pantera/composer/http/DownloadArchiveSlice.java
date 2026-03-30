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
package com.auto1.pantera.composer.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Slice for uploading archive by key from storage.
 */
final class DownloadArchiveSlice implements Slice {

    private final Repository repos;

    /**
     * Slice by key from storage.
     * @param repository Repository
     */
    DownloadArchiveSlice(final Repository repository) {
        this.repos = repository;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored -> {
            final String raw = line.uri().getPath();
            return this.response(raw);
        });
    }

    private CompletableFuture<Response> response(final String path) {
        // URL decode the path to handle %2B → +, but DON'T decode + to space
        // Java's URLDecoder.decode() incorrectly treats + as space in paths
        // So we manually decode only %XX sequences
        final String decodedPath = decodePathPreservingPlus(path);

        final CompletableFuture<Response> initial = this.repos.value(new KeyFromPath(decodedPath))
            .thenApply(content -> ResponseBuilder.ok().body(content).build());
        return initial.handle((resp, err) -> {
            if (err == null) {
                return CompletableFuture.completedFuture(resp);
            }
            final Throwable cause = err instanceof CompletionException ? err.getCause() : err;
            if (cause instanceof ValueNotFoundException) {
                // Fallback: try with + replaced by space (for legacy files stored with spaces)
                if (decodedPath.contains("+")) {
                    return this.repos.value(new KeyFromPath(decodedPath.replace('+', ' ')))
                        .thenApply(content -> ResponseBuilder.ok().body(content).build())
                        .exceptionally(fallbackErr -> ResponseBuilder.notFound().build());
                }
                return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            }
            return CompletableFuture.<Response>failedFuture(cause);
        }).thenCompose(Function.identity());
    }
    
    /**
     * Decode URL-encoded path while preserving literal + characters.
     * Standard URLDecoder incorrectly treats + as space in paths (it's only for query strings).
     */
    private static String decodePathPreservingPlus(final String path) {
        try {
            // Replace + with a placeholder before decoding
            final String placeholder = "\u0000PLUS\u0000";
            final String withPlaceholder = path.replace("+", placeholder);
            final String decoded = java.net.URLDecoder.decode(withPlaceholder, java.nio.charset.StandardCharsets.UTF_8);
            // Restore + characters
            return decoded.replace(placeholder, "+");
        } catch (Exception e) {
            return path; // Fallback to original if decoding fails
        }
    }
}
