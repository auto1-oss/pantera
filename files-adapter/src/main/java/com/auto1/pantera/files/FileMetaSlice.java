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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqParams;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Slice that returns metadata of a file when user requests it.
 */
public final class FileMetaSlice implements Slice {

    /**
     * Meta parameter.
     */
    private static final String META_PARAM = "meta";

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Slice to wrap for ordinary (non-metadata) requests. May be a WS1.7
     * redirect-eligible {@link com.auto1.pantera.http.slice.StorageArtifactSlice}.
     */
    private final Slice origin;

    /**
     * Slice used for {@code ?meta=true} requests. MUST be a stream-only serve:
     * a metadata request has to return the object's bytes plus the {@code
     * X-Pantera-*} headers this slice appends, so it can never be answered by
     * a presigned 302 (the redirect would drop those headers and the body).
     */
    private final Slice metaOrigin;

    /**
     * Ctor -- one origin used for both metadata and ordinary requests
     * (backwards-compatible: callers wiring a stream-only origin get the
     * previous behaviour unchanged).
     * @param origin Slice to wrap
     * @param storage Storage where to find file
     */
    public FileMetaSlice(final Slice origin, final Storage storage) {
        this(origin, origin, storage);
    }

    /**
     * Ctor with a distinct stream-only origin for {@code ?meta=true}. Lets an
     * ordinary object GET redirect (WS1.7) while a metadata request is always
     * streamed, so the appended {@code X-Pantera-*} headers survive.
     * @param origin Slice serving ordinary requests (may redirect)
     * @param metaOrigin Stream-only slice serving {@code ?meta=true} requests
     * @param storage Storage where to find file
     */
    public FileMetaSlice(final Slice origin, final Slice metaOrigin, final Storage storage) {
        this.origin = origin;
        this.metaOrigin = metaOrigin;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers iterable,
        final Content publisher
    ) {
        final URI uri = line.uri();
        final Optional<String> meta = new RqParams(uri).value(FileMetaSlice.META_PARAM);
        final CompletableFuture<Response> result;
        if (meta.isPresent() && Boolean.parseBoolean(meta.get())) {
            final CompletableFuture<Response> raw =
                this.metaOrigin.response(line, iterable, publisher);
            final Key key = new KeyFromPath(uri.getPath());
            result = raw.thenCompose(
                resp -> this.storage.exists(key)
                    .thenCompose(exist -> {
                        if (exist) {
                            return this.storage.metadata(key)
                                .thenApply(metadata -> {
                                    ResponseBuilder builder = ResponseBuilder.from(resp.status())
                                        .headers(resp.headers())
                                        .body(resp.body());
                                    from(metadata).stream().forEach(builder::header);
                                    return builder.build();
                                });
                        }
                        return CompletableFuture.completedFuture(resp);
                    }));
        } else {
            result = this.origin.response(line, iterable, publisher);
        }
        return result;
    }

    /**
     * Headers from meta.
     *
     * @param mtd Meta
     * @return Headers
     */
    private static Headers from(final Meta mtd) {
        final Map<Meta.OpRWSimple<?>, String> fmtd = new HashMap<>();
        fmtd.put(Meta.OP_MD5, "X-Pantera-MD5");
        fmtd.put(Meta.OP_CREATED_AT, "X-Pantera-CreatedAt");
        fmtd.put(Meta.OP_SIZE, "X-Pantera-Size");
        return new Headers(
            fmtd.entrySet().stream()
                .map(entry ->
                    new Header(entry.getValue(), mtd.read(entry.getKey()).orElseThrow().toString()))
                .toList()
        );
    }
}
