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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.RepositoryEvents;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Slice to upload the resource to storage by key from path.
 *
 * <p>The audit trail for this upload comes from the queued {@link
 * com.auto1.pantera.scheduling.ArtifactEvent} (added by {@link RepositoryEvents
 * #addUploadEventByKey}, when {@code events} is present) reaching {@code
 * DbConsumer}, which is the single sanctioned emission point for {@code
 * artifact_publish} across every format. This class must not call {@link
 * com.auto1.pantera.audit.AuditLogger} directly — doing so previously produced
 * two audit lines (event.action {@code artifact_upload} and {@code
 * artifact_publish}) for the same physical upload wherever this generic Slice
 * was reused (conan, the generic files format).
 */
public final class SliceUpload implements Slice {

    private final Storage storage;

    /**
     * Path to key transformation.
     */
    private final Function<String, Key> transform;

    /**
     * Repository events.
     */
    private final Optional<RepositoryEvents> events;

    /**
     * Slice by key from storage.
     * @param storage Storage
     */
    public SliceUpload(final Storage storage) {
        this(storage, KeyFromPath::new);
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     * @param storage Storage
     * @param transform Transformation
     */
    public SliceUpload(final Storage storage,
        final Function<String, Key> transform) {
        this(storage, transform, Optional.empty());
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     * @param storage Storage
     * @param events Repository events
     */
    public SliceUpload(final Storage storage,
        final RepositoryEvents events) {
        this(storage, KeyFromPath::new, Optional.of(events));
    }

    /**
     * Slice by key from storage using custom URI path transformation.
     * @param storage Storage
     * @param transform Transformation
     * @param events Repository events
     */
    public SliceUpload(final Storage storage, final Function<String, Key> transform,
        final Optional<RepositoryEvents> events) {
        this.storage = storage;
        this.transform = transform;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Key key = transform.apply(line.uri().getPath());
        CompletableFuture<Void> res = this.storage.save(key, new ContentWithSize(body, headers));
        if (this.events.isPresent()) {
            res = res.thenCompose(
                nothing -> this.storage.metadata(key)
                    .thenApply(meta -> meta.read(Meta.OP_SIZE).orElseThrow())
                    .thenAccept(
                        size -> this.events.get()
                            .addUploadEventByKey(key, size, headers)
                    )
            );
        }
        // Generic upload — used by files-adapter and several other
        // adapters' delegated save paths. Use the storage key as the
        // canonical artifact name; the negative cache key is built
        // from the URL path by BaseCachedProxySlice / GroupResolver so
        // they share the same string shape.
        return res.thenApply(nothing -> {
            com.auto1.pantera.http.cache.NegativeCacheRegistry.instance()
                .invalidateAfterUpload("file", key.string());
            com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry.instance()
                .invalidateAfterUpload("file", key.string());
            return ResponseBuilder.created().build();
        });
    }
}
