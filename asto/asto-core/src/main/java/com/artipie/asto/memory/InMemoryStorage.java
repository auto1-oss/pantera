/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.memory;

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
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Simple implementation of Storage that holds all data in memory.
 * Uses ConcurrentSkipListMap for lock-free reads and fine-grained locking.
 *
 * @since 0.14
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class InMemoryStorage implements Storage {

    /**
     * Values stored by key strings.
     * ConcurrentSkipListMap provides thread-safe operations without coarse-grained locking.
     * It is package private for avoid using sync methods for operations of storage for benchmarks.
     */
    final ConcurrentNavigableMap<String, byte[]> data;

    /**
     * Ctor.
     */
    public InMemoryStorage() {
        this(new ConcurrentSkipListMap<>());
    }

    /**
     * Ctor.
     * @param data Content of storage
     */
    InMemoryStorage(final ConcurrentNavigableMap<String, byte[]> data) {
        this.data = data;
    }

    /**
     * Legacy constructor for backward compatibility with tests.
     * @param data Content of storage as TreeMap
     * @deprecated Use constructor with ConcurrentNavigableMap
     */
    @Deprecated
    InMemoryStorage(final NavigableMap<String, byte[]> data) {
        this.data = new ConcurrentSkipListMap<>(data);
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        // ConcurrentSkipListMap.containsKey() is lock-free
        return CompletableFuture.completedFuture(
            this.data.containsKey(key.string())
        );
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key root) {
        return CompletableFuture.supplyAsync(
            () -> {
                // ConcurrentSkipListMap provides thread-safe iteration
                final String prefix = root.string();
                final Collection<Key> keys = new LinkedList<>();
                for (final String string : this.data.navigableKeySet().tailSet(prefix)) {
                    if (string.startsWith(prefix)) {
                        keys.add(new Key.From(string));
                    } else {
                        break;
                    }
                }
                return keys;
            }
        );
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final CompletableFuture<Void> res;
        if (Key.ROOT.equals(key)) {
            res = new CompletableFutureSupport.Failed<Void>(
                new ArtipieIOException("Unable to save to root")
            ).get();
        } else {
            res = new Concatenation(new OneTimePublisher<>(content)).single()
                .to(SingleInterop.get())
                .thenApply(Remaining::new)
                .thenApply(Remaining::bytes)
                .thenAccept(
                    bytes -> {
                        // ConcurrentSkipListMap.put() is thread-safe
                        this.data.put(key.string(), bytes);
                    }
                ).toCompletableFuture();
        }
        return res;
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return CompletableFuture.runAsync(
            () -> {
                final String key = source.string();
                // Atomic remove operation
                final byte[] value = this.data.remove(key);
                if (value == null) {
                    throw new ArtipieIOException(
                        String.format("No value for source key: %s", source.string())
                    );
                }
                // Put to destination (thread-safe)
                this.data.put(destination.string(), value);
            }
        );
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return CompletableFuture.supplyAsync(
            () -> {
                // Thread-safe get operation
                final byte[] content = this.data.get(key.string());
                if (content == null) {
                    throw new ValueNotFoundException(key);
                }
                return new MemoryMeta(content.length);
            }
        );
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final CompletableFuture<Content> res;
        if (Key.ROOT.equals(key)) {
            res = new CompletableFutureSupport.Failed<Content>(
                new ArtipieIOException("Unable to load from root")
            ).get();
        } else {
            res = CompletableFuture.supplyAsync(
                () -> {
                    // ConcurrentSkipListMap.get() is lock-free
                    final byte[] content = this.data.get(key.string());
                    if (content == null) {
                        throw new ValueNotFoundException(key);
                    }
                    return new Content.OneTime(new Content.From(content));
                }
            );
        }
        return res;
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return CompletableFuture.runAsync(
            () -> {
                final String str = key.string();
                // Atomic remove with null check
                if (this.data.remove(str) == null) {
                    throw new ArtipieIOException(
                        String.format("Key does not exist: %s", str)
                    );
                }
            }
        );
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return new UnderLockOperation<>(new StorageLock(this, key), operation).perform(this);
    }

    /**
     * Metadata for memory storage.
     * @since 1.9
     */
    private static final class MemoryMeta implements Meta {

        /**
         * Byte-array length.
         */
        private final long length;

        /**
         * New metadata.
         * @param length Array length
         */
        MemoryMeta(final int length) {
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
