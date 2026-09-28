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
package com.auto1.pantera.asto.lock.storage;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Serializes read-modify-write updates of a format index file (conda
 * {@code repodata.json}, the RubyGems {@code specs.4.8} family, ...) across
 * every writer: uploads, management-API deletes and imports, on this node
 * and on the other nodes sharing the storage.
 *
 * <p>It is the storage lock of {@link Storage#exclusively} (a proposal
 * under {@code .pantera-locks/<key>/}), with two differences that make it
 * usable on a request path:</p>
 * <ul>
 *   <li>a writer that finds the lock held waits for it (bounded retries
 *       with capped exponential backoff, scheduled without blocking a
 *       thread) instead of failing at once;</li>
 *   <li>the proposal carries a lease, so a node that dies holding the lock
 *       does not block the index forever.</li>
 * </ul>
 * <p>Only acquiring the lock is retried; a failure of the operation itself
 * is returned as is.</p>
 *
 * @since 2.2.9
 */
public final class IndexUpdateLock {

    /**
     * Default lease of a held lock.
     */
    private static final Duration LEASE = Duration.ofMinutes(10);

    /**
     * Default number of acquire attempts.
     */
    private static final int ATTEMPTS = 25;

    /**
     * Default first retry delay.
     */
    private static final Duration FIRST_DELAY = Duration.ofMillis(20);

    /**
     * Default longest retry delay.
     */
    private static final Duration MAX_DELAY = Duration.ofSeconds(2);

    /**
     * Storage holding the index.
     */
    private final Storage storage;

    /**
     * Locked index key.
     */
    private final Key key;

    /**
     * Lease of a held lock.
     */
    private final Duration lease;

    /**
     * Acquire attempts.
     */
    private final int attempts;

    /**
     * Longest retry delay.
     */
    private final Duration max;

    /**
     * Ctor with the default lease and waiting budget (about half a minute).
     * @param storage Storage holding the index
     * @param key Locked index key
     */
    public IndexUpdateLock(final Storage storage, final Key key) {
        this(storage, key, IndexUpdateLock.LEASE, IndexUpdateLock.ATTEMPTS, IndexUpdateLock.MAX_DELAY);
    }

    /**
     * Ctor.
     * @param storage Storage holding the index
     * @param key Locked index key
     * @param lease Lease of a held lock
     * @param attempts Acquire attempts, at least one
     * @param max Longest retry delay
     */
    public IndexUpdateLock(
        final Storage storage, final Key key, final Duration lease,
        final int attempts, final Duration max
    ) {
        this.storage = storage;
        this.key = key;
        this.lease = lease;
        this.attempts = Math.max(1, attempts);
        this.max = max;
    }

    /**
     * Run an operation while holding the lock.
     * @param operation Operation, given the storage
     * @param <T> Result type
     * @return Result of the operation
     */
    public <T> CompletableFuture<T> run(final Function<Storage, CompletionStage<T>> operation) {
        final StorageLock lock = new StorageLock(
            this.storage, this.key, Instant.now().plus(this.lease)
        );
        return this.acquire(lock, 0).thenCompose(
            acquired -> {
                CompletionStage<T> result;
                try {
                    result = operation.apply(this.storage);
                } catch (final RuntimeException ex) {
                    result = CompletableFuture.failedFuture(ex);
                }
                return result.handle(
                    (value, err) -> lock.release().handle(
                        (released, rerr) -> {
                            if (err instanceof CompletionException) {
                                throw (CompletionException) err;
                            }
                            if (err != null) {
                                throw new CompletionException(err);
                            }
                            return value;
                        }
                    )
                ).thenCompose(Function.identity());
            }
        );
    }

    /**
     * Acquire the lock, waiting for a holder to release it.
     * @param lock Lock
     * @param attempt Zero-based attempt number
     * @return Completion when held
     */
    private CompletableFuture<Void> acquire(final StorageLock lock, final int attempt) {
        return lock.acquire().toCompletableFuture().handle(
            (nothing, err) -> {
                final CompletableFuture<Void> res;
                if (err == null) {
                    res = CompletableFuture.completedFuture(null);
                } else if (attempt + 1 >= this.attempts) {
                    res = CompletableFuture.failedFuture(err);
                } else {
                    res = CompletableFuture.runAsync(
                        () -> { },
                        CompletableFuture.delayedExecutor(
                            this.delay(attempt), TimeUnit.MILLISECONDS
                        )
                    ).thenCompose(ignored -> this.acquire(lock, attempt + 1));
                }
                return res;
            }
        ).thenCompose(Function.identity());
    }

    /**
     * Delay before a retry, doubling from the first delay up to the maximum.
     * @param attempt Zero-based attempt that failed
     * @return Delay in milliseconds
     */
    private long delay(final int attempt) {
        final long first = IndexUpdateLock.FIRST_DELAY.toMillis();
        final long cap = this.max.toMillis();
        final int shift = Math.min(attempt, 20);
        return Math.min(cap, first << shift);
    }
}
