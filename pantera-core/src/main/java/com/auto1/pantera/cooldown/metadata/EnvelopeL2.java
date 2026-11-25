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
package com.auto1.pantera.cooldown.metadata;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Shared (L2) tier of {@link FilteredMetadataCache}: envelopes plus one
 * index per package that lists the envelope keys of that package.
 *
 * <p>The index is what makes a by-package invalidation cost the number of
 * envelopes of that package instead of a walk over the whole keyspace.
 * Contract of every implementation:</p>
 * <ul>
 *   <li>{@link #put} registers the key in the index and stores the envelope
 *       as one atomic step, and the index never expires before an envelope
 *       it lists — an envelope missing from its index cannot exist;</li>
 *   <li>{@link #drop} deletes the listed envelopes and their index entries
 *       as one atomic step;</li>
 *   <li>an index entry whose envelope is gone (expired, deleted by another
 *       writer) is harmless.</li>
 * </ul>
 *
 * @since 2.2.14
 */
interface EnvelopeL2 {

    /**
     * Read one envelope.
     *
     * @param key Envelope key
     * @return Stored bytes, {@code null} when absent
     */
    CompletableFuture<byte[]> get(String key);

    /**
     * Store an envelope and list it in its package index, atomically.
     *
     * @param index Index key of the package
     * @param key Envelope key
     * @param ttlSeconds Envelope time to live, seconds
     * @param value Bytes to store
     * @return Completion of the write
     */
    CompletableFuture<Void> put(String index, String key, long ttlSeconds, byte[] value);

    /**
     * Delete every envelope the index lists whose key starts with
     * {@code prefix}, together with its index entry, atomically.
     *
     * @param index Index key of the package
     * @param prefix Key prefix to match; empty matches every listed key
     * @return What was dropped
     */
    CompletableFuture<Dropped> drop(String index, String prefix);

    /**
     * Envelope keys the index lists.
     *
     * @param index Index key of the package
     * @return Listed keys; some may have expired already
     */
    CompletableFuture<List<String>> members(String index);

    /**
     * Remaining time to live of an envelope.
     *
     * @param key Envelope key
     * @return Milliseconds; {@code -2} when absent, {@code -1} when it never expires
     */
    CompletableFuture<Long> pttl(String key);

    /**
     * Delete keys by name.
     *
     * @param keys Keys to delete
     * @return Number of keys that existed
     */
    CompletableFuture<Long> delete(List<String> keys);

    /**
     * Delete every key matching a glob pattern by walking the keyspace.
     * Cost grows with the total number of keys: for administrative actions
     * (repository or policy change) only, never for a request or a refresh.
     *
     * @param pattern Glob pattern
     * @return Number of keys deleted
     */
    CompletableFuture<Long> deleteMatching(String pattern);

    /**
     * Result of {@link EnvelopeL2#drop}.
     *
     * @param keys Every envelope key that was listed and matched
     * @param deleted How many of them still held an envelope
     */
    record Dropped(List<String> keys, int deleted) {
    }
}
