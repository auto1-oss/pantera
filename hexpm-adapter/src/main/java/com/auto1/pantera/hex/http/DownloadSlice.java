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
package com.auto1.pantera.hex.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.PresignResolver;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * This slice returns content as bytes by Key from request path.
 */
public final class DownloadSlice implements Slice {
    /**
     * Path to packages.
     */
    static final String PACKAGES = "packages";

    /**
     * Pattern for packages.
     */
    static final Pattern PACKAGES_PTRN =
        Pattern.compile(String.format("/%s/\\S+", DownloadSlice.PACKAGES));

    /**
     * Path to tarballs.
     */
    static final String TARBALLS = "tarballs";

    /**
     * Pattern for tarballs.
     */
    static final Pattern TARBALLS_PTRN =
        Pattern.compile(String.format("/%s/\\S+", DownloadSlice.TARBALLS));

    /**
     * Repository storage.
     */
    private final Storage storage;

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
     * @param storage Repository storage.
     */
    public DownloadSlice(final Storage storage) {
        this(storage, DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with an explicit WS1.7 (spec {@code WS1-storage-for-scale.md}
     * &sect;3.B2) download policy. Only the {@code /tarballs/} package-byte
     * route is redirect-eligible; the {@code /packages/} registry-metadata
     * route always streams.
     *
     * @param storage Repository storage.
     * @param policy WS1.7 download policy.
     */
    public DownloadSlice(final Storage storage, final DownloadPolicy policy) {
        this.storage = storage;
        this.policy = policy;
        this.repoName = storage instanceof SubStorage sub ? sub.prefix().string() : "unknown";
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key.From key = new Key.From(
            line.uri().getPath().replaceFirst("/", "")
        );
        final Optional<URI> presigned = this.presignedFor(line, key);
        if (presigned.isPresent()) {
            this.recordDecision("redirect");
            return CompletableFuture.completedFuture(
                ResponseBuilder.found().header(new Location(presigned.get().toString())).build()
            );
        }
        if (this.policy.mode() != DownloadMode.STREAM && DownloadSlice.isTarball(key)) {
            this.recordDecision("stream");
        }
        return this.storage.exists(key)
            .thenCompose(exist -> {
                    if (exist) {
                        return this.storage.value(key)
                            .thenApply(
                                value -> ResponseBuilder.ok()
                                    .header(ContentType.mime("application/octet-stream"))
                                    .body(value)
                                    .build()
                            );
                    }
                    return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
                }
            );
    }

    /**
     * WS1.7: resolve a presigned URL for a package-tarball GET, or empty when a
     * redirect is not applicable (stream-only policy, non-GET, a {@code
     * /packages/} registry-metadata key, no presigner, or not-yet-durable).
     *
     * @param line Request line
     * @param key Storage key derived from the request path
     * @return Presigned URL to redirect to, or empty to stream
     */
    private Optional<URI> presignedFor(final RequestLine line, final Key key) {
        if (this.policy.mode() == DownloadMode.STREAM
            || line.method() != RqMethod.GET
            || !DownloadSlice.isTarball(key)) {
            return Optional.empty();
        }
        return PresignResolver.resolve(this.storage, key)
            .flatMap(target -> target.presignIfDurable(this.policy.presignTtlSeconds()));
    }

    /**
     * @param key Storage key
     * @return {@code true} iff the key addresses a package tarball ({@code
     *  tarballs/...}) -- the only redirect-eligible route; {@code packages/...}
     *  registry metadata is excluded
     */
    private static boolean isTarball(final Key key) {
        return key.string().startsWith(DownloadSlice.TARBALLS + "/");
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
}
