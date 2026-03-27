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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;

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
     * @param storage Repository storage.
     */
    public DownloadSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key.From key = new Key.From(
            line.uri().getPath().replaceFirst("/", "")
        );
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
}
