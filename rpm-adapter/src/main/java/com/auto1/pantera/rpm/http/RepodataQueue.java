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
package com.auto1.pantera.rpm.http;

import com.auto1.pantera.asto.Storage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Serialises the operations that rewrite one repository's {@code repodata/}
 * within this process: an upload adding packages, a delete removing them,
 * and a {@code repomd.xml} read creating empty metadata for a repository
 * that has none. Without it, the read could see the metadata absent, an
 * upload could then write it, and the read would replace it with empty
 * metadata; two such operations also contend for the fail-fast storage
 * lock on {@code repodata/} and one of them fails.
 *
 * <p>Operations queue without blocking a thread: each one starts when the
 * previous one on the same storage has completed, successfully or not. The
 * queue is keyed by the storage identifier, so every slice instance serving
 * the same repository shares it. Nodes of a cluster sharing a storage are
 * not serialised against each other.</p>
 *
 * @since 2.2.9
 */
final class RepodataQueue {

    /**
     * Tail of the queue per storage.
     */
    private static final ConcurrentMap<String, CompletableFuture<Void>> TAILS =
        new ConcurrentHashMap<>();

    /**
     * Queue id.
     */
    private final String id;

    /**
     * Ctor.
     * @param asto Repository storage
     */
    RepodataQueue(final Storage asto) {
        this.id = String.join("|", asto.identifier(), "repodata");
    }

    /**
     * Run the operation once every earlier operation on this repository's
     * metadata is done.
     * @param operation Operation
     * @param <T> Result type
     * @return Operation result
     */
    <T> CompletionStage<T> run(final Supplier<? extends CompletionStage<T>> operation) {
        final CompletableFuture<Void> mine = new CompletableFuture<>();
        final CompletableFuture<Void> previous = RepodataQueue.TAILS.put(this.id, mine);
        final CompletableFuture<Void> start;
        if (previous == null) {
            start = CompletableFuture.completedFuture(null);
        } else {
            start = previous;
        }
        return start.thenCompose(nothing -> operation.get()).whenComplete(
            (res, err) -> {
                RepodataQueue.TAILS.remove(this.id, mine);
                mine.complete(null);
            }
        );
    }
}
