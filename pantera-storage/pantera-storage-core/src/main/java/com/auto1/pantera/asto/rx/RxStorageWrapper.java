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
package com.auto1.pantera.asto.rx;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import hu.akarnokd.rxjava2.interop.CompletableInterop;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Completable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import java.util.Collection;
import java.util.function.Function;

/**
 * Reactive wrapper over {@code Storage}.
 *
 * <p>CRITICAL: This wrapper does NOT use observeOn() to avoid backpressure violations
 * and resource exhaustion under high concurrency. The underlying Storage implementations
 * (FileStorage, S3Storage, etc.) already handle threading via CompletableFuture's
 * thread pools. Adding observeOn(Schedulers.io()) causes:
 * <ul>
 *   <li>MissingBackpressureException: Queue is full?! (buffer overflow at 128 items)</li>
 *   <li>Unbounded thread pool growth (Schedulers.io() is cached, grows without limit)</li>
 *   <li>High CPU usage (excessive thread creation and context switching)</li>
 *   <li>High memory usage (each thread + buffering in observeOn queues)</li>
 *   <li>Disk I/O spikes (many concurrent operations on separate threads)</li>
 *   <li>OOM kills under concurrent load</li>
 * </ul>
 *
 * <p>This is the same issue as VertxSliceServer observeOn() bug that caused file corruption.
 * The fix is to let the underlying storage handle threading, not force everything onto
 * the IO scheduler.
 *
 * @since 0.9
 */
public final class RxStorageWrapper implements RxStorage {

    /**
     * Wrapped storage.
     */
    private final Storage storage;

    /**
     * The scheduler to observe on (DEPRECATED - kept for backward compatibility but not used).
     * @deprecated Scheduler is no longer used to avoid backpressure violations.
     *             Will be removed in future versions.
     */
    @Deprecated
    private final Scheduler scheduler;

    /**
     * Ctor.
     *
     * @param storage The storage
     */
    public RxStorageWrapper(final Storage storage) {
        this(storage, null);
    }

    /**
     * Ctor.
     *
     * @param storage The storage
     * @param scheduler The scheduler to observe on (DEPRECATED - ignored to prevent backpressure).
     * @deprecated Scheduler parameter is ignored to avoid backpressure violations.
     *             Use RxStorageWrapper(Storage) instead.
     */
    @Deprecated
    public RxStorageWrapper(final Storage storage, final Scheduler scheduler) {
        this.storage = storage;
        this.scheduler = scheduler;
    }

    @Override
    public Single<Boolean> exists(final Key key) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        // The underlying storage.exists() already returns CompletableFuture which handles threading
        // Use RxFuture.single (non-blocking) instead of SingleInterop.fromFuture (blocking)
        return Single.defer(() -> RxFuture.single(this.storage.exists(key)));
    }

    @Override
    public Single<Collection<Key>> list(final Key prefix) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        // Use RxFuture.single (non-blocking) instead of SingleInterop.fromFuture (blocking)
        return Single.defer(() -> RxFuture.single(this.storage.list(prefix)));
    }

    @Override
    public Completable save(final Key key, final Content content) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        return Completable.defer(
            () -> CompletableInterop.fromFuture(this.storage.save(key, content))
        );
    }

    @Override
    public Completable move(final Key source, final Key destination) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        return Completable.defer(
            () -> CompletableInterop.fromFuture(this.storage.move(source, destination))
        );
    }

    @Override
    @Deprecated
    public Single<Long> size(final Key key) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        // Use RxFuture.single (non-blocking) instead of SingleInterop.fromFuture (blocking)
        return Single.defer(() -> RxFuture.single(this.storage.size(key)));
    }

    @Override
    public Single<Content> value(final Key key) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        // Use RxFuture.single (non-blocking) instead of SingleInterop.fromFuture (blocking)
        return Single.defer(() -> RxFuture.single(this.storage.value(key)));
    }

    @Override
    public Completable delete(final Key key) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        return Completable.defer(() -> CompletableInterop.fromFuture(this.storage.delete(key)));
    }

    @Override
    public <T> Single<T> exclusively(
        final Key key,
        final Function<RxStorage, Single<T>> operation
    ) {
        // CRITICAL: Do NOT use observeOn() - causes backpressure violations under high concurrency
        // Use RxFuture.single (non-blocking) instead of SingleInterop.fromFuture (blocking)
        // SingleInterop.get() is used to convert Single back to CompletionStage (non-blocking)
        return Single.defer(
            () -> RxFuture.single(
                this.storage.exclusively(
                    key,
                    st -> operation.apply(new RxStorageWrapper(st)).to(SingleInterop.get())
                )
            )
        );
    }
}
