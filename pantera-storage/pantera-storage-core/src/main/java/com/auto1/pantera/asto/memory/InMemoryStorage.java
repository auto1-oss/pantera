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
package com.auto1.pantera.asto.memory;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.OneTimePublisher;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.UnderLockOperation;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.ext.CompletableFutureSupport;
import com.auto1.pantera.asto.lock.storage.StorageLock;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    public CompletableFuture<ListResult> list(final Key root, final String delimiter) {
        return CompletableFuture.supplyAsync(
            () -> {
                String prefix = root.string();
                // Ensure prefix ends with delimiter if not empty and not root
                if (!prefix.isEmpty() && !prefix.endsWith(delimiter)) {
                    prefix = prefix + delimiter;
                }
                
                final Collection<Key> files = new ArrayList<>();
                final Collection<Key> directories = new LinkedHashSet<>();
                
                // Thread-safe iteration over concurrent map
                for (final String keyStr : this.data.navigableKeySet().tailSet(prefix)) {
                    if (!keyStr.startsWith(prefix)) {
                        break; // No more keys with this prefix
                    }
                    
                    // Skip the prefix itself if it's an exact match
                    if (keyStr.equals(prefix)) {
                        continue;
                    }
                    
                    // Get the part after the prefix
                    final String relative;
                    if (prefix.isEmpty()) {
                        relative = keyStr;
                    } else {
                        relative = keyStr.substring(prefix.length());
                    }
                    
                    // Find delimiter in the relative path
                    final int delimIdx = relative.indexOf(delimiter);
                    
                    if (delimIdx < 0) {
                        // No delimiter found - this is a file at this level
                        files.add(new Key.From(keyStr));
                    } else {
                        // Delimiter found - extract directory prefix
                        final String dirName = relative.substring(0, delimIdx);
                        // Ensure directory key ends with delimiter
                        String dirPrefix = prefix + dirName;
                        if (!dirPrefix.endsWith(delimiter)) {
                            dirPrefix = dirPrefix + delimiter;
                        }
                        directories.add(new Key.From(dirPrefix));
                    }
                }
                
                return new ListResult.Simple(files, new ArrayList<>(directories));
            }
        );
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final CompletableFuture<Void> res;
        if (Key.ROOT.equals(key)) {
            res = new CompletableFutureSupport.Failed<Void>(
                new PanteraIOException("Unable to save to root")
            ).get();
        } else {
            // OPTIMIZATION: Use size hint for efficient pre-allocation
            final long knownSize = content.size().orElse(-1L);
            res = Concatenation.withSize(new OneTimePublisher<>(content), knownSize).single()
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
                    throw new PanteraIOException(
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
                new PanteraIOException("Unable to load from root")
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
                    throw new PanteraIOException(
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
