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

import java.util.Optional;

/**
 * Wire payload {@link CachedBlobStorage} encodes into the opaque {@code
 * versionToken} string passed to {@link StorageInvalidationBus#publish}
 * (spec {@code WS1-storage-for-scale.md} &sect;3.E) -- the bus itself never
 * interprets this string; it is purely {@code CachedBlobStorage}'s own
 * private wire format for the two pieces of information a receiver needs to
 * arbitrate a cross-node coherence message correctly without a live
 * blob-store HEAD:
 * <ul>
 *   <li>{@link #namespace()} -- disambiguates repositories/storage instances
 *   that share ONE process-wide bus and hence one {@code "storage"} channel:
 *   a receiver ignores any message whose namespace does not match its own
 *   local disk-cache directory, so two repositories that happen to use
 *   identical logical key strings (e.g. both cache a {@code readme.txt})
 *   never cross-invalidate each other.</li>
 *   <li>{@link #committedAtEpochMilli()} -- lets a receiver detect a message
 *   that is superseded by a local write it has already applied: a delayed
 *   or reordered message must never evict a strictly newer local entry
 *   (the race explicitly called out in WS1.5's spec step -- "a stale
 *   invalidation arriving after a newer local write").</li>
 * </ul>
 *
 * @param namespace Publisher's local disk-cache directory (its {@code
 *  diskRoot}, stable and identical for the same repository across nodes in
 *  a homogeneously-configured cluster), used to scope delivery to listeners
 *  for the SAME repository only.
 * @param digest Content digest (hex) of the committed bytes, or {@code null}
 *  for a delete tombstone -- carried for logs/diagnostics only, NEVER
 *  compared for ordering: a content hash establishes no happens-before
 *  relationship between two independently-computed writes.
 * @param committedAtEpochMilli Wall-clock time (the publisher's own local
 *  clock) the commit/delete this message describes was confirmed locally.
 * @since 2.3.0
 */
record StorageInvalidationToken(String namespace, String digest, long committedAtEpochMilli) {

    /**
     * Field separator for the wire encoding -- a control character, so it
     * cannot collide with a real disk-cache path or hex digest.
     */
    private static final char FIELD_SEPARATOR = '\u0001';

    /**
     * Encodes this token to the wire string carried as {@link
     * StorageInvalidationBus#publish}'s {@code versionToken} argument.
     *
     * @return Encoded token.
     */
    String encode() {
        return this.namespace
            + StorageInvalidationToken.FIELD_SEPARATOR
            + (this.digest == null ? "" : this.digest)
            + StorageInvalidationToken.FIELD_SEPARATOR
            + this.committedAtEpochMilli;
    }

    /**
     * Decodes a wire string produced by {@link #encode()}.
     *
     * @param raw Wire string, or {@code null}.
     * @return Decoded token, or {@link Optional#empty()} if {@code raw} is
     *  not a well-formed encoding -- defensive: a malformed message is
     *  dropped rather than risking an incorrect eviction.
     */
    static Optional<StorageInvalidationToken> decode(final String raw) {
        Optional<StorageInvalidationToken> result;
        if (raw == null) {
            result = Optional.empty();
        } else {
            final String[] parts = raw.split(String.valueOf(StorageInvalidationToken.FIELD_SEPARATOR), 3);
            if (parts.length == 3) {
                try {
                    result = Optional.of(
                        new StorageInvalidationToken(parts[0], parts[1].isEmpty() ? null : parts[1], Long.parseLong(parts[2]))
                    );
                } catch (final NumberFormatException ex) {
                    result = Optional.empty();
                }
            } else {
                result = Optional.empty();
            }
        }
        return result;
    }
}
