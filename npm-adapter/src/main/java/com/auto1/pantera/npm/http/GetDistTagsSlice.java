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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.PackageNameFromUrl;

import java.util.concurrent.CompletableFuture;

/**
 * Returns value of the `dist-tags` field from package `meta.json`.
 * Request line to this slice looks like /-/package/@hello%2fsimple-npm-project/dist-tags.
 */
public final class GetDistTagsSlice implements Slice {

    /**
     * Abstract Storage.
     */
    private final Storage storage;

    /**
     * @param storage Abstract storage
     */
    public GetDistTagsSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(final RequestLine line,
                                                final Headers headers,
                                                final Content body) {
        final String pkg = new PackageNameFromUrl(
            line.toString().replace("/dist-tags", "").replace("/-/package", "")
        ).value();
        final Key key = new Key.From(pkg, "meta.json");
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored ->
            this.storage.exists(key).thenCompose(
                exists -> {
                    if (exists) {
                        return this.storage.value(key)
                            .thenCompose(Content::asJsonObjectFuture)
                            .thenApply(json -> ResponseBuilder.ok()
                                .jsonBody(json.getJsonObject("dist-tags"))
                                .build());
                    }
                    return ResponseBuilder.notFound().completedFuture();
                }
            )
        );
    }
}
