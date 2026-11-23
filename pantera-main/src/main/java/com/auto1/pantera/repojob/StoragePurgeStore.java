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
package com.auto1.pantera.repojob;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.StorageThrottledException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link PurgeStore} over a {@link Storage}: its paged listing and batch
 * delete, awaited on the job thread.
 *
 * @since 2.2.14
 */
public final class StoragePurgeStore implements PurgeStore {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Longest wait for one storage call.
     */
    private final Duration timeout;

    /**
     * Ctor with a five-minute limit per storage call.
     * @param storage Storage holding the directory to purge
     */
    public StoragePurgeStore(final Storage storage) {
        this(storage, Duration.ofMinutes(5));
    }

    /**
     * Ctor.
     * @param storage Storage holding the directory to purge
     * @param timeout Longest wait for one storage call
     */
    public StoragePurgeStore(final Storage storage, final Duration timeout) {
        this.storage = storage;
        this.timeout = timeout;
    }

    @Override
    public Page list(final String dir, final Optional<String> after, final int limit) {
        final ListPage page = this.await(this.storage.listPage(new Key.From(dir), after, limit));
        final List<String> names = new ArrayList<>(page.keys().size());
        page.keys().forEach(key -> names.add(key.string()));
        return new Page(names, page.next());
    }

    @Override
    public void delete(final List<String> names) {
        final List<Key> keys = new ArrayList<>(names.size());
        names.forEach(name -> keys.add(new Key.From(name)));
        this.await(this.storage.deleteBatch(keys));
    }

    @Override
    public int abortUploads(final String dir) {
        return this.await(this.storage.abortIncompleteUploads(new Key.From(dir)));
    }

    /**
     * Wait for a storage call.
     * @param call Storage call
     * @param <T> Result type
     * @return Its result
     * @throws StoreThrottled When the storage asked to slow down
     */
    private <T> T await(final CompletableFuture<T> call) {
        try {
            return call.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the storage", ex);
        } catch (final TimeoutException ex) {
            call.cancel(true);
            throw new IllegalStateException(
                String.format("Storage call did not finish within %s", this.timeout), ex
            );
        } catch (final ExecutionException ex) {
            Throwable cause = ex.getCause();
            while (cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof StorageThrottledException) {
                throw new StoreThrottled(cause);
            }
            throw new IllegalStateException(
                String.format("Storage call failed: %s", cause.getMessage()), cause
            );
        }
    }
}
