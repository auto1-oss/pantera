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
package com.auto1.pantera.http;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Runs asynchronous operations one at a time per key, without blocking any
 * thread: an operation starts only when the previous operation queued under
 * the same key has completed (successfully or not). Operations under
 * different keys run concurrently. Idle keys hold no memory.
 *
 * @since 2.2.9
 */
final class KeyedSerializer {

    /**
     * Completion of the last operation queued per key.
     */
    private final ConcurrentMap<String, CompletableFuture<Void>> tails;

    /**
     * Ctor.
     */
    KeyedSerializer() {
        this.tails = new ConcurrentHashMap<>();
    }

    /**
     * Queue an operation under a key.
     * @param key Serialization key
     * @param operation Operation to run once the key is free
     * @param <T> Result type
     * @return Result of the operation
     */
    <T> CompletableFuture<T> run(
        final String key, final Supplier<CompletableFuture<T>> operation
    ) {
        final CompletableFuture<Void> mine = new CompletableFuture<>();
        final CompletableFuture<Void> previous = this.tails.put(key, mine);
        final CompletableFuture<Void> ready;
        if (previous == null) {
            ready = CompletableFuture.completedFuture(null);
        } else {
            ready = previous;
        }
        final CompletableFuture<T> result = ready.thenCompose(ignored -> operation.get());
        result.whenComplete(
            (value, error) -> {
                this.tails.remove(key, mine);
                mine.complete(null);
            }
        );
        return result;
    }
}
