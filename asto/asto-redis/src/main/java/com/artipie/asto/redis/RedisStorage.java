/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.redis;

import com.artipie.asto.ArtipieIOException;
import com.artipie.asto.Concatenation;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.OneTimePublisher;
import com.artipie.asto.Remaining;
import com.artipie.asto.Storage;
import com.artipie.asto.UnderLockOperation;
import com.artipie.asto.ValueNotFoundException;
import com.artipie.asto.ext.CompletableFutureSupport;
import com.artipie.asto.lock.storage.StorageLock;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.redisson.api.RMapAsync;

/**
 * Redis implementation of Storage.
 *
 * <p><strong>ENTERPRISE RECOMMENDATION:</strong> Redis storage is optimized for small
 * metadata files. For large artifacts, use FileStorage or S3Storage instead.
 * This storage has a configurable size limit (default 10MB) to prevent memory issues.</p>
 *
 * @since 0.1
 */
public final class RedisStorage implements Storage {

    /**
     * Default maximum content size (10MB).
     * Redis should be used for metadata, not large artifacts.
     */
    public static final long DEFAULT_MAX_SIZE = 10 * 1024 * 1024L;

    /**
     * Async interface for Redis based implementation
     * of {@link java.util.concurrent.ConcurrentMap} and {@link java.util.Map}.
     */
    private final RMapAsync<String, byte[]> data;

    /**
     * Storage identifier is redisson instance id, example: b0d9b09f-7c45-4a22-a8b7-c4979b65476a.
     */
    private final String id;

    /**
     * Maximum content size in bytes.
     */
    private final long maxSize;

    /**
     * Ctor with default max size (10MB).
     *
     * @param data Async interface for Redis.
     * @param id Redisson instance id
     */
    public RedisStorage(final RMapAsync<String, byte[]> data, final String id) {
        this(data, id, DEFAULT_MAX_SIZE);
    }

    /**
     * Ctor with configurable max size.
     *
     * @param data Async interface for Redis.
     * @param id Redisson instance id
     * @param maxSize Maximum content size in bytes (0 = unlimited)
     */
    public RedisStorage(final RMapAsync<String, byte[]> data, final String id, final long maxSize) {
        this.data = data;
        this.id = String.format("Redis: id=%s", id);
        this.maxSize = maxSize;
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        return this.data.containsKeyAsync(key.string()).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key root) {
        return this.data.readAllKeySetAsync()
            .thenApply(
                keys -> {
                    final Collection<Key> res = new LinkedList<>();
                    final String prefix = root.string();
                    for (final String string : new TreeSet<>(keys)) {
                        if (string.startsWith(prefix)) {
                            res.add(new Key.From(string));
                        }
                    }
                    return res;
                }
            ).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final CompletableFuture<Void> res;
        if (Key.ROOT.equals(key)) {
            res = new CompletableFutureSupport.Failed<Void>(
                new ArtipieIOException("Unable to save to root")
            ).get();
        } else {
            // ENTERPRISE: Check size limit before buffering to prevent OOM
            final long contentSize = content.size().orElse(-1L);
            if (this.maxSize > 0 && contentSize > this.maxSize) {
                res = new CompletableFutureSupport.Failed<Void>(
                    new ArtipieIOException(
                        String.format(
                            "Content size %d exceeds Redis storage limit of %d bytes. "
                                + "Use FileStorage or S3Storage for large artifacts.",
                            contentSize, this.maxSize
                        )
                    )
                ).get();
            } else {
                // OPTIMIZATION: Use size-optimized Concatenation when size is known
                res = Concatenation.withSize(new OneTimePublisher<>(content), contentSize)
                    .single()
                    .to(SingleInterop.get())
                    .thenApply(Remaining::new)
                    .thenApply(Remaining::bytes)
                    .thenCompose(bytes -> {
                        // Double-check size after buffering (for unknown sizes)
                        if (this.maxSize > 0 && bytes.length > this.maxSize) {
                            throw new ArtipieIOException(
                                String.format(
                                    "Content size %d exceeds Redis storage limit of %d bytes. "
                                        + "Use FileStorage or S3Storage for large artifacts.",
                                    bytes.length, this.maxSize
                                )
                            );
                        }
                        return this.data.fastPutAsync(key.string(), bytes);
                    })
                    .thenRun(() -> { })
                    .toCompletableFuture();
            }
        }
        return res;
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        final String src = source.string();
        return this.data.containsKeyAsync(src)
            .thenCompose(
                exists -> {
                    final CompletionStage<Void> res;
                    if (exists) {
                        res = this.data.getAsync(src)
                            .thenCompose(
                                bytes -> this.data.fastPutAsync(destination.string(), bytes)
                            ).thenCompose(
                                unused -> this.data.fastRemoveAsync(src)
                                    .thenRun(() -> { })
                            );
                    } else {
                        res = new CompletableFutureSupport.Failed<Void>(
                            new ArtipieIOException(
                                String.format("No value for source key: %s", src)
                            )
                        ).get();
                    }
                    return res;
                }
            ).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final CompletableFuture<Content> res;
        if (Key.ROOT.equals(key)) {
            res = new CompletableFutureSupport.Failed<Content>(
                new ArtipieIOException("Unable to load from root")
            ).get();
        } else {
            res = this.data.getAsync(key.string())
                .thenApply(
                    bytes -> {
                        if (bytes != null) {
                            return (Content) new Content.OneTime(new Content.From(bytes));
                        }
                        throw new ValueNotFoundException(key);
                    }
                ).toCompletableFuture();
        }
        return res;
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        final String str = key.string();
        return this.data.fastRemoveAsync(str)
            .thenAccept(
                num -> {
                    if (num != 1) {
                        throw new ArtipieIOException(
                            String.format("Key does not exist: %s", str)
                        );
                    }
                }
            ).toCompletableFuture();
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return new UnderLockOperation<>(new StorageLock(this, key), operation)
            .perform(this);
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return this.data.getAsync(key.string())
            .thenApply(
                bytes -> {
                    if (bytes != null) {
                        return new RedisMeta(bytes.length);
                    }
                    throw new ValueNotFoundException(key);
                }
            ).toCompletableFuture();
    }

    @Override
    public String identifier() {
        return this.id;
    }

    /**
     * Metadata for redis storage.
     *
     * @since 1.9
     */
    private static final class RedisMeta implements Meta {

        /**
         * Byte-array length.
         */
        private final long length;

        /**
         * New metadata.
         *
         * @param length Array length
         */
        RedisMeta(final int length) {
            this.length = length;
        }

        @Override
        public <T> T read(final ReadOperator<T> opr) {
            final Map<String, String> raw = new HashMap<>();
            Meta.OP_SIZE.put(raw, this.length);
            return opr.take(Collections.unmodifiableMap(raw));
        }
    }
}
