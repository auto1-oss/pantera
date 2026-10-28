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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Backend-agnostic durable object store.
 *
 * <p><strong>Why this exists (WS1.0):</strong> {@code BlobStore} is the minimal
 * surface every object-store backend must provide so that the local metadata
 * index, disk cache, write-back queue, eviction and presigned-redirect machinery
 * planned for WS1.1+ never need to know which backend they are talking to. It is
 * intentionally narrower than {@link com.auto1.pantera.asto.Storage}: no
 * {@code move} (a Pantera-level convenience, not an object-store primitive) and no
 * {@code exclusively} advisory locking.</p>
 *
 * <p><strong>Implementations:</strong> S3 and S3-API-compatible services (MinIO,
 * Cloudflare R2, Backblaze B2, Wasabi, Ceph/RADOS Gateway, GCS via its S3
 * interoperability endpoint) are all served today by
 * {@code com.auto1.pantera.asto.s3.S3Storage}, the reference implementation, via
 * config alone (custom endpoint, region, path-style, credentials). Native GCS and
 * Azure Blob implementations are a later, separate addition behind this same
 * interface (see WS1.8) and must not change any consumer of {@code BlobStore}.</p>
 *
 * @since 2.3.0
 */
public interface BlobStore {

    /**
     * This blob exists in the store?
     *
     * @param key The key.
     * @return TRUE if present, FALSE otherwise.
     */
    CompletableFuture<Boolean> exists(Key key);

    /**
     * Fetch a blob's metadata without downloading its bytes.
     *
     * @param key The key.
     * @return Future with metadata; fails if the key does not exist.
     */
    CompletableFuture<? extends Meta> head(Key key);

    /**
     * Fetch a blob's bytes.
     *
     * @param key The key.
     * @return Future with content; fails if the key does not exist.
     */
    CompletableFuture<Content> get(Key key);

    /**
     * Durably write a blob.
     *
     * @param key The key.
     * @param content Bytes to write.
     * @return Completion or error signal.
     */
    CompletableFuture<Void> put(Key key, Content content);

    /**
     * Remove a blob. Fails if it does not exist.
     *
     * @param key The key.
     * @return Completion or error signal.
     */
    CompletableFuture<Void> delete(Key key);

    /**
     * Recursively list all keys under a prefix.
     *
     * @param prefix The prefix.
     * @return Collection of matching keys.
     */
    CompletableFuture<Collection<Key>> list(Key prefix);

    /**
     * List keys hierarchically using a delimiter (non-recursive).
     *
     * <p>Default implementation falls back to the recursive {@link #list(Key)} and
     * splits the result client-side; backends with native delimiter support
     * (e.g. S3's {@code ListObjectsV2} {@code delimiter} parameter) should
     * override this for efficiency, exactly as
     * {@code com.auto1.pantera.asto.s3.S3Storage} does.</p>
     *
     * @param prefix Prefix to list under.
     * @param delimiter Delimiter, typically {@code "/"}.
     * @return Files and directories one level below the prefix.
     */
    default CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
        return this.list(prefix).thenApply(keys -> BlobStore.split(prefix, delimiter, keys));
    }

    /**
     * Identifier for logs/metrics: backend + endpoint/bucket, not sensitive data.
     *
     * @return Human readable identifier.
     */
    default String identifier() {
        return this.getClass().getSimpleName();
    }

    /**
     * Splits a flat recursive key listing into direct files/directories one level
     * below {@code prefix} -- the shared fallback for backends without native
     * delimiter support.
     *
     * @param prefix Listed prefix.
     * @param delimiter Delimiter.
     * @param keys Flat recursive key listing.
     * @return Files and directories one level below the prefix.
     */
    private static ListResult split(
        final Key prefix,
        final String delimiter,
        final Collection<Key> keys
    ) {
        final List<Key> files = new ArrayList<>();
        final LinkedHashSet<Key> dirs = new LinkedHashSet<>();
        final String pfx = prefix.string();
        final int len = pfx.isEmpty() ? 0 : pfx.length() + 1;
        for (final Key key : keys) {
            final String str = key.string();
            if (str.length() <= len) {
                continue;
            }
            final String relative = str.substring(len);
            final int idx = relative.indexOf(delimiter);
            if (idx < 0) {
                files.add(key);
            } else {
                dirs.add(new Key.From(str.substring(0, len + idx + delimiter.length())));
            }
        }
        return new ListResult.Simple(files, new ArrayList<>(dirs));
    }
}
