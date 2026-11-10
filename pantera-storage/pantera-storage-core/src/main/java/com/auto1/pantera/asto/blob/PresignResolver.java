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

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.SubStorage;
import java.net.URI;
import java.util.Optional;

/**
 * WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2): resolves whether
 * a presigned-URL redirect is currently possible for one key on one {@link
 * Storage}, touching only in-memory index lookups and {@code instanceof}
 * checks on the storage composition already in hand -- never the blob store
 * itself.
 *
 * <p>Unwraps {@link SubStorage} layers (a repository's storage is typically
 * {@code SubStorage(repoPrefix, SubStorage(v2Prefix, &lt;alias storage&gt;))}
 * -- see {@code RepoConfig}/{@code AstoDocker}) to find the underlying {@link
 * CachedBlobStorage} or a bare {@link Presigner}-implementing storage (e.g.
 * plain {@code S3Storage} with no cache wrapper), accumulating the prefixed
 * key along the way so the presigned URL addresses the SAME object the
 * storage's own {@code value(key)} would have read. Deliberately has no
 * compile-time dependency on {@code pantera-storage-s3} -- only the {@link
 * Presigner} interface -- so a future native GCS/Azure backend (WS1.8) drops
 * in unchanged.</p>
 *
 * <p>This is the composition point the WS1.7 phase calls for: the serving
 * slice (an adapter, or {@code pantera-core}) calls {@link #resolve(Storage,
 * Key)} and never sees a backend-specific type.</p>
 *
 * @since 2.3.0
 */
public final class PresignResolver {

    /**
     * Bound on {@link SubStorage} unwrap depth -- guards against a
     * pathological/cyclic storage composition looping forever. No real
     * repo wiring nests more than two layers today.
     */
    private static final int MAX_UNWRAP_DEPTH = 16;

    private PresignResolver() {
    }

    /**
     * Resolve a presign target for {@code key} on {@code storage}, if the
     * underlying storage composition supports presigning at all.
     *
     * @param storage Repo-scoped storage (may be {@link SubStorage}-wrapped
     *  any number of times).
     * @param key Key as seen by {@code storage} (i.e. NOT yet prefixed).
     * @return A resolved {@link Target}, or empty if nothing in this
     *  storage's composition implements {@link Presigner} (or does, but is
     *  not currently configured to sign) -- the caller MUST fall back to
     *  streaming in that case.
     */
    public static Optional<Target> resolve(final Storage storage, final Key key) {
        Storage current = storage;
        Key resolved = key;
        int depth = 0;
        while (current instanceof SubStorage sub && depth < MAX_UNWRAP_DEPTH) {
            resolved = new Key.From(sub.prefix(), resolved);
            current = sub.origin();
            depth++;
        }
        final Key finalKey = resolved;
        final Optional<Target> result;
        if (current instanceof CachedBlobStorage cached) { // NOPMD CloseResource - not owned here: `cached` is the repo's shared storage instance, whose lifecycle belongs to whatever built it (RepositorySlices/S3StorageFactory), not this read-only resolve() call
            result = cached.presigner().map(
                presigner -> new Target(presigner, finalKey, cached.isDurablyPresent(finalKey))
            );
        } else if (current instanceof Presigner presigner && presigner.isPresignConfigured()) {
            // A bare Presigner-implementing storage (e.g. plain S3Storage
            // with no CachedBlobStorage/StorageIndex wrapper) has no
            // PENDING_WRITE concept: if the caller already resolved
            // existence to reach this point, the object is durably in the
            // blob store by definition -- there is no other tier it could
            // be sitting in.
            result = Optional.of(new Target(presigner, finalKey, true));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * A resolved presign target: what to sign, and whether it is currently
     * safe to do so.
     *
     * @param presigner Presigner backing the underlying blob store.
     * @param key Fully-qualified key as addressed on the underlying storage.
     * @param durablyPresent Whether {@code key} is confirmed durably in the
     *  blob store right now (never {@code true} for a {@code PENDING_WRITE}
     *  write-back entry -- see {@link CachedBlobStorage#isDurablyPresent}).
     * @since 2.3.0
     */
    public record Target(Presigner presigner, Key key, boolean durablyPresent) {

        /**
         * Issue the presigned URL iff {@link #durablyPresent()} -- local
         * signing only, zero blob-store round trip either way.
         *
         * @param ttlSeconds Validity window in seconds.
         * @return The presigned URL, or empty if the object is not (yet)
         *  durably present in the blob store.
         */
        public Optional<URI> presignIfDurable(final long ttlSeconds) {
            return this.durablyPresent
                ? Optional.of(this.presigner.presignGet(this.key, ttlSeconds))
                : Optional.empty();
        }
    }
}
