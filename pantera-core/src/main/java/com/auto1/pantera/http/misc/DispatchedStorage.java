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
package com.auto1.pantera.http.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Decorator that wraps any {@link Storage} and dispatches completion
 * continuations to the named thread pools from {@link StorageExecutors}.
 * <p>
 * Each storage operation category is dispatched to its own pool:
 * <ul>
 *   <li>READ ops (exists, value, metadata) use {@link StorageExecutors#READ}</li>
 *   <li>WRITE ops (save, move, delete) use {@link StorageExecutors#WRITE}</li>
 *   <li>LIST ops (list) use {@link StorageExecutors#LIST}</li>
 * </ul>
 * <p>
 * The {@code exclusively()} method delegates directly without dispatching
 * to avoid deadlocks with lock management. The {@code identifier()} method
 * also delegates directly as it is synchronous with no I/O.
 *
 * @since 1.20.13
 */
public final class DispatchedStorage implements Storage {

    /**
     * Delegate storage.
     */
    private final Storage delegate;

    /**
     * Wraps the given storage with thread pool dispatching.
     * @param delegate Storage to wrap
     */
    public DispatchedStorage(final Storage delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        return dispatch(this.delegate.exists(key), StorageExecutors.READ);
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        return dispatch(this.delegate.list(prefix), StorageExecutors.LIST);
    }

    @Override
    public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
        return dispatch(this.delegate.list(prefix, delimiter), StorageExecutors.LIST);
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        return dispatch(this.delegate.save(key, content), StorageExecutors.WRITE);
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return dispatch(this.delegate.move(source, destination), StorageExecutors.WRITE);
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return dispatch(this.delegate.metadata(key), StorageExecutors.READ);
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        return dispatch(this.delegate.value(key), StorageExecutors.READ);
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return dispatch(this.delegate.delete(key), StorageExecutors.WRITE);
    }

    @Override
    public CompletableFuture<Void> deleteAll(final Key prefix) {
        return dispatch(this.delegate.deleteAll(prefix), StorageExecutors.WRITE);
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return this.delegate.exclusively(key, operation);
    }

    /**
     * Returns the underlying delegate storage.
     * Useful for inspecting the actual storage type when this decorator wraps it.
     * @return The delegate storage
     */
    public Storage unwrap() {
        return this.delegate;
    }

    @Override
    public String identifier() {
        return this.delegate.identifier();
    }

    /**
     * Dispatch a future's completion to the given executor.
     * Guarantees the returned future is always completed by a thread
     * from the target executor, so downstream {@code thenApply()} /
     * {@code thenCompose()} continuations run on that pool.
     *
     * @param source Source future from the delegate storage
     * @param executor Target executor pool
     * @param <T> Result type
     * @return Future that completes on the target executor
     */
    private static <T> CompletableFuture<T> dispatch(
        final CompletableFuture<? extends T> source,
        final Executor executor
    ) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete(
            (val, err) -> {
                try {
                    executor.execute(() -> {
                        if (err != null) {
                            result.completeExceptionally(err);
                        } else {
                            result.complete(val);
                        }
                    });
                } catch (final java.util.concurrent.RejectedExecutionException rex) {
                    result.completeExceptionally(rex);
                }
            }
        );
        return result;
    }
}
