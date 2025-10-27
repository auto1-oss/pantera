/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer.http;

import com.artipie.asto.Content;
import com.artipie.asto.ValueNotFoundException;
import com.artipie.composer.Repository;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;

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
        final String raw = line.uri().getPath();
        return this.response(raw);
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
            // Fallback: try with + replaced by space (for legacy files)
            if (cause instanceof ValueNotFoundException && decodedPath.contains("+")) {
                return this.repos.value(new KeyFromPath(decodedPath.replace('+', ' ')))
                    .thenApply(content -> ResponseBuilder.ok().body(content).build());
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
