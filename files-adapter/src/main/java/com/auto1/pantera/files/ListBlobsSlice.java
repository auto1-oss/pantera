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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * This slice lists blobs contained in given path.
 * <p>
 * It formats response content according to {@link Function}
 * formatter.
 * It also converts URI path to storage {@link com.auto1.pantera.asto.Key}
 * and use it to access storage.
 */
public final class ListBlobsSlice implements Slice {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Blob list format.
     */
    private final BlobListFormat format;

    /**
     * Mime type.
     */
    private final String mtype;

    /**
     * Path to key transformation.
     */
    private final Function<String, Key> transform;

    /**
     * Slice by key from storage.
     *
     * @param storage Storage
     * @param format Blob list format
     * @param mtype Mime type
     */
    public ListBlobsSlice(
        final Storage storage,
        final BlobListFormat format,
        final String mtype
    ) {
        this(storage, format, mtype, KeyFromPath::new);
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     *
     * @param storage Storage
     * @param format Blob list format
     * @param mtype Mime type
     * @param transform Transformation
     */
    public ListBlobsSlice(
        final Storage storage,
        final BlobListFormat format,
        final String mtype,
        final Function<String, Key> transform
    ) {
        this.storage = storage;
        this.format = format;
        this.mtype = mtype;
        this.transform = transform;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = this.transform.apply(line.uri().getPath());
        return this.storage.list(key)
            .thenApply(
                keys -> {
                    final String text = this.format.apply(keys);
                    return ResponseBuilder.ok()
                        .header(ContentType.mime(this.mtype))
                        .body(text.getBytes(StandardCharsets.UTF_8))
                        .build();
                }
            );
    }
}
