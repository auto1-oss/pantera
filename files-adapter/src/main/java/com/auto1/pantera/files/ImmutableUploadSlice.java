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
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ContentWithSize;
import com.auto1.pantera.http.slice.KeyFromPath;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Upload guard of an immutable file repository: a file that is already
 * stored can never be overwritten. A re-upload of byte-identical content
 * (a CI retry) is an idempotent 201 that rewrites nothing and publishes no
 * new event; different content is refused with 409 Conflict. A key that is
 * not stored yet goes to the wrapped upload slice.
 *
 * <p>Both sides are compared by SHA-256 computed over the streamed bytes,
 * so neither the upload nor the stored file is buffered in memory. When the
 * declared upload size differs from the stored size the stored file is not
 * read at all.</p>
 *
 * @since 2.2.10
 */
final class ImmutableUploadSlice implements Slice {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.files";

    /**
     * Upload slice for keys that are not stored yet.
     */
    private final Slice origin;

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor.
     * @param origin Upload slice for keys that are not stored yet
     * @param storage Storage
     * @param rname Repository name
     */
    ImmutableUploadSlice(final Slice origin, final Storage storage, final String rname) {
        this.origin = origin;
        this.storage = storage;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final Key key = new KeyFromPath(line.uri().getPath());
        if (key.string().isEmpty()) {
            return this.origin.response(line, headers, body);
        }
        // The existence check and the write run under one lock on the file,
        // kept in the repository storage: two uploads of the same new path,
        // on this instance or on another one sharing the storage, cannot
        // both pass the check and both write.
        final AtomicBoolean started = new AtomicBoolean();
        return new IndexUpdateLock(this.storage, key).run(
            locked -> {
                started.set(true);
                return locked.exists(key).thenCompose(
                    exists -> {
                        if (!exists) {
                            return this.origin.response(line, headers, body);
                        }
                        return this.redeploy(key, new ContentWithSize(body, headers));
                    }
                );
            }
        ).exceptionallyCompose(
            err -> {
                // The lock was never acquired: nothing read the body yet.
                final CompletableFuture<Void> drained = started.get()
                    ? CompletableFuture.completedFuture(null)
                    : body.discard().toCompletableFuture();
                return drained.thenCompose(ignored -> CompletableFuture.failedFuture(err));
            }
        );
    }

    /**
     * Re-upload of a stored file.
     * @param key File key
     * @param body Uploaded content
     * @return 201 for identical bytes, 409 otherwise
     */
    private CompletableFuture<Response> redeploy(final Key key, final Content body) {
        return this.storage.metadata(key)
            .thenApply(meta -> meta.read(Meta.OP_SIZE))
            .thenCompose(
                stored -> {
                    final Optional<Long> declared = body.size();
                    if (declared.isPresent() && stored.isPresent()
                        && !declared.get().equals(stored.get())) {
                        return body.discard().thenApply(ignored -> this.conflict(key));
                    }
                    return new ContentDigest(body, Digests.SHA256).hex()
                        .thenCompose(
                            incoming -> this.storage.value(key).thenCompose(
                                content -> new ContentDigest(content, Digests.SHA256).hex()
                            ).thenApply(
                                existing -> incoming.equals(existing)
                                    ? ResponseBuilder.created().build()
                                    : this.conflict(key)
                            )
                        ).toCompletableFuture();
                }
            );
    }

    /**
     * Refusal of an overwrite.
     * @param key File key
     * @return 409 Conflict
     */
    private Response conflict(final Key key) {
        EcsLogger.warn(ImmutableUploadSlice.LOGGER)
            .message("Rejected overwrite of a stored file in an immutable repository")
            .eventCategory("web")
            .eventAction("artifact_upload")
            .eventOutcome("failure")
            .field("event.reason", "artifact_immutable")
            .field("repository.name", this.rname)
            .field("url.path", "/" + key.string())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.CONFLICT)
            .textBody(
                "File /" + key.string() + " already exists with different content. "
                    + "The repository is immutable: upload to a new path, or delete "
                    + "the existing file first."
            )
            .build();
    }
}
