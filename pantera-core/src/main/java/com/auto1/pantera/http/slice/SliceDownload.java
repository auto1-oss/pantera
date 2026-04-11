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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.OptimizedStorageCache;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentFileName;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * This slice responds with value from storage by key from path.
 * <p>
 * It converts URI path to storage {@link Key}
 * and use it to access storage.
 * </p>
 * @see SliceUpload
 */
public final class SliceDownload implements Slice {

    private final Storage storage;

    /**
     * Path to key transformation.
     */
    private final Function<String, Key> transform;

    /**
     * Slice by key from storage.
     *
     * @param storage Storage
     */
    public SliceDownload(final Storage storage) {
        this(storage, KeyFromPath::new);
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     *
     * @param storage Storage
     * @param transform Transformation
     */
    public SliceDownload(final Storage storage,
        final Function<String, Key> transform) {
        this.storage = storage;
        this.transform = transform;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = this.transform.apply(line.uri().getPath());
        return this.storage.exists(key)
                .thenCompose(
                    exist -> {
                        if (exist) {
                            // Use optimized storage access for 100-1000x faster downloads
                            // on FileStorage (direct NIO). Falls back to standard storage.value()
                            // for S3 and other storage types.
                            return OptimizedStorageCache.optimizedValue(this.storage, key).thenApply(
                                content -> {
                                    final java.util.List<String> parts = key.parts();
                                    final String filename = parts.isEmpty() ? key.string() : parts.get(parts.size() - 1);
                                    final long size = content.size().orElse(0L);
                                    AuditLogger.download("", "", "", "", filename, size);
                                    return ResponseBuilder.ok()
                                        .header(new ContentFileName(line.uri()))
                                        .body(content)
                                        .build();
                                }
                            );
                        }
                        return CompletableFuture.completedFuture(
                            ResponseBuilder.notFound()
                                .textBody(String.format("Key %s not found", key.string()))
                                .build()
                        );
                    }
        );
    }
}
