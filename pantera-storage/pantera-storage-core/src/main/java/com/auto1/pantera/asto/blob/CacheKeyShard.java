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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Deterministic key &lt;-&gt; on-disk relative path transform for {@link
 * CachedBlobStorage}'s disk tier (spec {@code WS1-storage-for-scale.md}
 * &sect;3.D): a 2-level hex fan-out derived from a hash of the logical key,
 * so that {@code CachedBlobStorage} never materializes one huge flat cache
 * directory no matter how many keys it caches or how flat their own logical
 * structure is (e.g. many generic/npm uploads sharing one shallow prefix).
 *
 * <p><strong>Layout:</strong> {@code <2 hex>/<2 hex>/<percent-encoded key>} --
 * the two hex directories are the first four hex digits of the key's
 * SHA-256 hash (uniformly distributing across 65536 shard directories
 * regardless of the logical key's own shape); the leaf file name is the
 * ORIGINAL key string, percent-encoded ({@link URLEncoder}) so that a
 * multi-segment logical key (which would otherwise itself create nested
 * directories through {@link Key#string()}'s {@code "/"} delimiter) becomes
 * a single flat file name under the shard, and so that {@link
 * #fromDiskPath(Path, Path)} can reconstruct the exact original key by
 * percent-decoding that one leaf segment -- the hash-derived shard
 * directories are write-only fan-out, never consulted on decode.</p>
 *
 * <p>Used consistently everywhere {@link CachedBlobStorage} addresses its
 * disk tier ({@code save}/{@code value}/{@code delete}/{@code pathFor}/
 * sidecar read-write/eviction) so the mapping never drifts between write and
 * read paths, and by {@link StorageIndex#rebuildFromDisk(Path,
 * java.util.function.Function)} on boot to recover the logical key for every
 * discovered on-disk file.</p>
 *
 * <p><strong>Known limitation:</strong> a percent-encoded key can exceed most
 * filesystems' ~255-byte single-filename limit for a very long logical key
 * (e.g. an unusually long generic-format path); this mirrors a limitation
 * already accepted by the pre-WS1.4 {@code DiskCacheStorage} for deeply
 * nested keys and is not addressed here.</p>
 *
 * @since 2.3.0
 */
final class CacheKeyShard {

    /**
     * Number of hex characters per shard-directory level.
     */
    private static final int SHARD_HEX_LEN = 2;

    /**
     * Total shard prefix depth (two directory levels).
     */
    private static final int SHARD_PREFIX_DEPTH = 2;

    /**
     * Expected path-segment count under the cache root for a well-formed
     * sharded entry: 2 shard directories + 1 leaf file.
     */
    private static final int EXPECTED_NAME_COUNT = SHARD_PREFIX_DEPTH + 1;

    private CacheKeyShard() {
    }

    /**
     * Computes the sharded on-disk key for a logical key.
     *
     * @param original Logical key as seen by {@code CachedBlobStorage}'s
     *  callers.
     * @return Physical key to address the disk tier
     *  ({@code FileStorage}) with -- always exactly {@value
     *  #EXPECTED_NAME_COUNT} path segments.
     */
    static Key toDiskKey(final Key original) {
        final String raw = original.string();
        final String hash = CacheKeyShard.sha256Hex(raw);
        return new Key.From(
            hash.substring(0, SHARD_HEX_LEN),
            hash.substring(SHARD_HEX_LEN, SHARD_HEX_LEN * 2),
            URLEncoder.encode(raw, StandardCharsets.UTF_8)
        );
    }

    /**
     * Recovers the logical key from a data file discovered by {@link
     * StorageIndex#rebuildFromDisk(Path, java.util.function.Function)}'s
     * boot-time directory walk.
     *
     * @param root Cache namespace root the walk started from.
     * @param dataFile Discovered regular data file (already filtered to
     *  exclude sidecars and staging temp files by the caller).
     * @return The original logical key, or {@link Optional#empty()} if
     *  {@code dataFile} does not conform to the sharded layout (e.g. a
     *  leftover from an unrelated layout) -- the caller skips such files;
     *  they self-heal via the next cold fill.
     */
    static Optional<Key> fromDiskPath(final Path root, final Path dataFile) {
        final Path relative = root.relativize(dataFile);
        final Optional<Key> result;
        if (relative.getNameCount() != EXPECTED_NAME_COUNT) {
            result = Optional.empty();
        } else {
            result = CacheKeyShard.decodeLeaf(relative.getFileName().toString());
        }
        return result;
    }

    private static Optional<Key> decodeLeaf(final String leaf) {
        Optional<Key> result;
        try {
            result = Optional.of(new Key.From(URLDecoder.decode(leaf, StandardCharsets.UTF_8)));
        } catch (final IllegalArgumentException ex) {
            // EXPECTED: a malformed percent-encoding (e.g. a stray on-disk
            // file that never went through toDiskKey) -- unrecoverable as a
            // key, skip it; it plays no part in cache correctness.
            result = Optional.empty();
        }
        return result;
    }

    private static String sha256Hex(final String raw) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", ex);
        }
    }
}
