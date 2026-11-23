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

import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.cache.ValkeyKeyIndex;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link EnvelopeL2} on Valkey: the atomic indexed steps are
 * {@link ValkeyKeyIndex}'s.
 *
 * @since 2.2.14
 */
final class ValkeyEnvelopeL2 implements EnvelopeL2 {

    /**
     * Keys asked for per page of the administrative keyspace walk.
     */
    private static final int SCAN_COUNT = 500;

    /**
     * Connection.
     */
    private final ValkeyConnection valkey;

    /**
     * Per-package index of envelope keys.
     */
    private final ValkeyKeyIndex indexed;

    /**
     * Ctor.
     *
     * @param valkey Connection
     */
    ValkeyEnvelopeL2(final ValkeyConnection valkey) {
        this.valkey = valkey;
        this.indexed = new ValkeyKeyIndex(valkey.async());
    }

    @Override
    public CompletableFuture<byte[]> get(final String key) {
        return this.valkey.async().get(key).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> put(
        final String index, final String key, final long ttlSeconds, final byte[] value
    ) {
        return this.indexed.put(index, key, ttlSeconds, value);
    }

    @Override
    public CompletableFuture<Dropped> drop(final String index, final String prefix) {
        return this.indexed.drop(index, prefix).thenApply(
            dropped -> new Dropped(dropped.keys(), dropped.deleted())
        );
    }

    @Override
    public CompletableFuture<List<String>> members(final String index) {
        return this.indexed.members(index);
    }

    @Override
    public CompletableFuture<Long> pttl(final String key) {
        return this.valkey.async().pttl(key).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Long> delete(final List<String> keys) {
        final CompletableFuture<Long> result;
        if (keys.isEmpty()) {
            result = CompletableFuture.completedFuture(0L);
        } else {
            result = this.valkey.async().del(keys.toArray(new String[0])).toCompletableFuture();
        }
        return result;
    }

    @Override
    public CompletableFuture<Long> deleteMatching(final String pattern) {
        return this.deleteStep(ScanCursor.INITIAL, pattern, 0L);
    }

    /**
     * One page of {@link #deleteMatching(String)}.
     *
     * @param cursor Scan position
     * @param pattern Glob pattern
     * @param sofar Keys deleted by earlier pages
     * @return Number of keys deleted in total
     */
    private CompletableFuture<Long> deleteStep(
        final ScanCursor cursor, final String pattern, final long sofar
    ) {
        return this.valkey.async()
            .scan(cursor, ScanArgs.Builder.matches(pattern).limit(ValkeyEnvelopeL2.SCAN_COUNT))
            .toCompletableFuture()
            .thenCompose(page -> this.delete(page.getKeys()).thenCompose(
                count -> this.nextStep(page, pattern, sofar + count)
            ));
    }

    /**
     * Continue or finish the walk after a page.
     *
     * @param page Page just handled
     * @param pattern Glob pattern
     * @param total Keys deleted so far
     * @return Number of keys deleted in total
     */
    private CompletableFuture<Long> nextStep(
        final KeyScanCursor<String> page, final String pattern, final long total
    ) {
        final CompletableFuture<Long> result;
        if (page.isFinished()) {
            result = CompletableFuture.completedFuture(total);
        } else {
            result = this.deleteStep(page, pattern, total);
        }
        return result;
    }
}
