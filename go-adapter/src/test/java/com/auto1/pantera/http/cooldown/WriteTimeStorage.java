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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Test storage decorator that reports an {@code updated-at} write time for
 * every key it saved, the way {@code FileStorage} (file mtime) and the S3
 * storage (Last-Modified) do.
 *
 * <p>{@code InMemoryStorage} reports only a size, which
 * {@link com.auto1.pantera.http.CacheTimeControl} treats as no evidence
 * of freshness (stale: refetch, serve the copy only if the refetch fails).
 * A cache-hit path therefore cannot be exercised over a bare
 * {@code InMemoryStorage}; wrap it in this decorator instead.</p>
 */
final class WriteTimeStorage extends Storage.Wrap {

    /**
     * Write time per saved key.
     */
    private final Map<String, Instant> written = new ConcurrentHashMap<>();

    /**
     * Ctor.
     * @param delegate Storage to decorate
     */
    WriteTimeStorage(final Storage delegate) {
        super(delegate);
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        return super.save(key, content)
            .thenRun(() -> this.written.put(key.string(), Instant.now()));
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return super.delete(key).thenRun(() -> this.written.remove(key.string()));
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return super.metadata(key).thenApply(
            meta -> {
                final Instant time = this.written.get(key.string());
                if (time == null) {
                    return meta;
                }
                return new Meta() {
                    @Override
                    public <T> T read(final ReadOperator<T> opr) {
                        final Map<String, String> raw = meta.read(HashMap<String, String>::new);
                        Meta.OP_UPDATED_AT.put(raw, time);
                        return opr.take(raw);
                    }
                };
            }
        );
    }

    /**
     * Poll until {@code key} is durably saved with its write time. The
     * stream-through cache write (see {@code FromStorageCache}) tees bytes
     * to the caller while saving a copy in the background, so the write is
     * not guaranteed durable the instant the caller's future completes —
     * poll for the eventual state rather than asserting instantly.
     * @param key Key expected to land
     * @throws Exception If the key never lands
     */
    void awaitPersisted(final Key key) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!this.exists(key).get(1, TimeUnit.SECONDS)
            || !this.written.containsKey(key.string())) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Cache entry for " + key.string() + " never persisted");
            }
            Thread.sleep(2);
        }
    }
}
