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

package com.auto1.pantera.asto.test;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Test storage that parks the first write matching a predicate until
 * released, and reports when that write arrived. Used to prove that a
 * check-then-write sequence is serialised: the first writer is parked
 * inside its write after its check passed, a second writer is started, and
 * only a lock kept in storage can make the second one wait and then see the
 * first one's files.
 *
 * <p>Writes of the storage lock itself ({@code .pantera-locks/}) are never
 * parked, or the lock holder could not release.</p>
 *
 * <p>{@link #contender()} completes once, after the parked write arrived, a
 * second actor shows up: it checks a target key for existence (no lock, it
 * raced past) or proposes a lock entry (it is waiting for the first one).
 * Releasing the parked write only then makes the outcome independent of
 * thread timing in both cases.</p>
 *
 * @since 2.2.10
 */
public final class ParkedStorage extends Storage.Wrap {

    /**
     * Lock entries prefix.
     */
    private static final String LOCKS = ".pantera-locks";

    /**
     * Which write to park.
     */
    private final Predicate<Key> target;

    /**
     * Completed when the parked write arrived.
     */
    private final CompletableFuture<Void> arrived;

    /**
     * Completed by {@link #release()}.
     */
    private final CompletableFuture<Void> gate;

    /**
     * Whether a write has been parked already.
     */
    private final AtomicBoolean parked;

    /**
     * Completed when a second actor shows up after the parked write.
     */
    private final CompletableFuture<Void> contender;

    /**
     * Park the first write that is not a lock entry.
     *
     * @param delegate Real storage
     */
    public ParkedStorage(final Storage delegate) {
        this(delegate, key -> true);
    }

    /**
     * Park the first write matching the predicate.
     *
     * @param delegate Real storage
     * @param target Which write to park, lock entries are never offered
     */
    public ParkedStorage(final Storage delegate, final Predicate<Key> target) {
        super(delegate);
        this.target = target;
        this.arrived = new CompletableFuture<>();
        this.gate = new CompletableFuture<>();
        this.parked = new AtomicBoolean();
        this.contender = new CompletableFuture<>();
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        if (this.arrived.isDone() && this.target.test(key)) {
            this.contender.complete(null);
        }
        return this.delegate().exists(key);
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        final CompletableFuture<Void> result;
        if (key.string().contains(ParkedStorage.LOCKS) && this.arrived.isDone()) {
            this.contender.complete(null);
        }
        if (!key.string().contains(ParkedStorage.LOCKS) && this.target.test(key)
            && this.parked.compareAndSet(false, true)) {
            this.arrived.complete(null);
            result = this.gate.thenCompose(ignored -> this.delegate().save(key, content));
        } else {
            result = this.delegate().save(key, content);
        }
        return result;
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        final CompletableFuture<Void> result;
        if (!destination.string().contains(ParkedStorage.LOCKS) && this.target.test(destination)
            && this.parked.compareAndSet(false, true)) {
            this.arrived.complete(null);
            result = this.gate.thenCompose(ignored -> this.delegate().move(source, destination));
        } else {
            result = this.delegate().move(source, destination);
        }
        return result;
    }

    /**
     * Completed when the parked write arrived.
     *
     * @return Future
     */
    public CompletableFuture<Void> arrived() {
        return this.arrived;
    }

    /**
     * Completed when a second actor showed up after the parked write: an
     * existence check of a target key, or a lock proposal.
     *
     * @return Future
     */
    public CompletableFuture<Void> contender() {
        return this.contender;
    }

    /**
     * Let the parked write proceed.
     */
    public void release() {
        this.gate.complete(null);
    }
}
