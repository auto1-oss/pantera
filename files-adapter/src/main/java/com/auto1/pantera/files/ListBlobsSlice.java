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
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

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
     * Header carrying the path before the repository segment was trimmed.
     */
    private static final String FULL_PATH = "X-FullPath";

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
        final String path = line.uri().getPath();
        final Key key = this.transform.apply(path);
        final String base = ListBlobsSlice.linkBase(path, headers);
        return this.storage.list(key)
            .thenApply(
                keys -> {
                    final String text = this.format.apply(keys, base);
                    return ResponseBuilder.ok()
                        .header(ContentType.mime(this.mtype))
                        .body(text.getBytes(StandardCharsets.UTF_8))
                        .build();
                }
            );
    }

    /**
     * Link base for listed keys: the part of the client-facing path that
     * precedes the repository-relative path, so links keep the repository
     * segment (and any API prefix the client used). Keys are relative to the
     * repository root.
     *
     * @param path Repository-relative request path
     * @param headers Request headers
     * @return Base ending with {@code /}
     */
    private static String linkBase(final String path, final Headers headers) {
        final String inner = ListBlobsSlice.trimSlashes(path);
        return Stream.of(ClientBaseUrl.ORIGINAL_PATH, ListBlobsSlice.FULL_PATH)
            .flatMap(name -> headers.values(name).stream())
            .map(ListBlobsSlice::trimSlashes)
            .filter(full -> full.endsWith(inner))
            .findFirst()
            .map(full -> full.substring(0, full.length() - inner.length()))
            .map(prefix -> prefix + "/")
            .orElse("/");
    }

    /**
     * Drop trailing slashes.
     * @param path Path
     * @return Path without trailing slashes
     */
    private static String trimSlashes(final String path) {
        return path.replaceAll("/+$", "");
    }
}
