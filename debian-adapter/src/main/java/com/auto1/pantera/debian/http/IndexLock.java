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
package com.auto1.pantera.debian.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Serialises the read-modify-write operations on one Packages index within
 * this process: an upload merging a package, a delete removing one, and a
 * {@code /dists/} read creating a missing index empty. Without it, the read
 * could see the index absent, an upload could then write it, and the read
 * would overwrite it with an empty index.
 *
 * <p>Operations queue without blocking a thread: each one starts when the
 * previous one on the same storage and key has completed, successfully or
 * not. The queue is keyed by the storage identifier and index key, so
 * every slice instance serving the same repository shares it. Nodes of a
 * cluster sharing a storage are not serialised against each other.</p>
 *
 * @since 2.2.9
 */
final class IndexLock {

    /**
     * Tail of the queue per storage and index key.
     */
    private static final ConcurrentMap<String, CompletableFuture<Void>> TAILS =
        new ConcurrentHashMap<>();

    /**
     * Queue id.
     */
    private final String id;

    /**
     * Ctor.
     * @param asto Storage
     * @param index Packages index key
     */
    IndexLock(final Storage asto, final Key index) {
        this.id = String.join("|", asto.identifier(), index.string());
    }

    /**
     * Run the operation once every earlier operation on this index is done.
     * @param operation Operation
     * @param <T> Result type
     * @return Operation result
     */
    <T> CompletionStage<T> run(final Supplier<? extends CompletionStage<T>> operation) {
        final CompletableFuture<Void> mine = new CompletableFuture<>();
        final CompletableFuture<Void> previous = IndexLock.TAILS.put(this.id, mine);
        final CompletableFuture<Void> start;
        if (previous == null) {
            start = CompletableFuture.completedFuture(null);
        } else {
            start = previous;
        }
        return start.thenCompose(nothing -> operation.get()).whenComplete(
            (res, err) -> {
                IndexLock.TAILS.remove(this.id, mine);
                mine.complete(null);
            }
        );
    }
}
