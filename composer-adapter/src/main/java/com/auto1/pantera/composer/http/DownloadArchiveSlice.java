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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.PresignResolver;
import com.auto1.pantera.composer.Repository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Slice for uploading archive by key from storage.
 *
 * <p>This slice serves only Composer dist archives ({@code .zip}/{@code
 * .tar.gz}/{@code .tgz}) -- {@code packages.json}, provider and per-package
 * metadata are served by other slices and never reach here. On a
 * redirect-eligible GET (WS1.7, spec {@code WS1-storage-for-scale.md}
 * &sect;3.B2) it may answer {@code 302} to a presigned URL that serves the
 * IDENTICAL stored bytes the streaming path would, so the client's standard
 * {@code dist.shasum} (SHA-1) verification is unaffected.</p>
 */
final class DownloadArchiveSlice implements Slice {

    private final Repository repos;

    /**
     * WS1.7 per-repo presigned-direct-download policy. {@link
     * DownloadPolicy#streamOnly()} (the one-arg-ctor default) is byte-identical
     * to pre-WS1.7 behaviour: no {@code 302} is ever issued.
     */
    private final DownloadPolicy policy;

    /**
     * Repo name for the {@code pantera.storage.download.decision} metric tag,
     * derived from the outermost {@link SubStorage} prefix.
     */
    private final String repoName;

    /**
     * Slice by key from storage -- stream-only (pre-WS1.7 behaviour).
     * @param repository Repository
     */
    DownloadArchiveSlice(final Repository repository) {
        this(repository, DownloadPolicy.streamOnly());
    }

    /**
     * Slice by key from storage with an explicit WS1.7 download policy.
     * @param repository Repository
     * @param policy WS1.7 download policy
     */
    DownloadArchiveSlice(final Repository repository, final DownloadPolicy policy) {
        this.repos = repository;
        this.policy = policy;
        final Storage storage = repository.storage();
        this.repoName = storage instanceof SubStorage sub ? sub.prefix().string() : "unknown";
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored -> {
            final String raw = line.uri().getPath();
            final Optional<URI> presigned = this.presignedFor(line, raw);
            if (presigned.isPresent()) {
                this.recordDecision("redirect");
                return CompletableFuture.completedFuture(
                    ResponseBuilder.found().header(new Location(presigned.get().toString())).build()
                );
            }
            if (this.policy.mode() != DownloadMode.STREAM) {
                this.recordDecision("stream");
            }
            return this.response(raw);
        });
    }

    /**
     * WS1.7: resolve a presigned URL for this dist-archive GET, or empty when a
     * redirect is not applicable (stream-only policy, non-GET, no presigner, or
     * not-yet-durable).
     *
     * @param line Request line
     * @param rawPath Raw request path
     * @return Presigned URL to redirect to, or empty to stream
     */
    private Optional<URI> presignedFor(final RequestLine line, final String rawPath) {
        if (this.policy.mode() == DownloadMode.STREAM || line.method() != RqMethod.GET) {
            return Optional.empty();
        }
        final Key key = new KeyFromPath(DownloadArchiveSlice.decodePathPreservingPlus(rawPath));
        return PresignResolver.resolve(this.repos.storage(), key)
            .flatMap(target -> target.presignIfDurable(this.policy.presignTtlSeconds()));
    }

    /**
     * WS1.7: record the redirect-vs-stream serving decision.
     *
     * @param decision {@code "redirect"} or {@code "stream"}
     */
    private void recordDecision(final String decision) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordDownloadDecision(this.repoName, decision);
        }
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
