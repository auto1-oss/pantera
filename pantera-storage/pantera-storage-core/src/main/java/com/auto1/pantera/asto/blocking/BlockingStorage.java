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
package com.auto1.pantera.asto.blocking;

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.Storage;
import java.util.Collection;

/**
 * More primitive and easy to use wrapper to use {@code Storage}.
 *
 * @since 0.1
 */
public class BlockingStorage {

    /**
     * Wrapped storage.
     */
    private final Storage storage;

    /**
     * Wrap a {@link Storage} in order get a blocking version of it.
     *
     * @param storage Storage to wrap
     */
    public BlockingStorage(final Storage storage) {
        this.storage = storage;
    }

    /**
     * This file exists?
     *
     * @param key The key (file name)
     * @return TRUE if exists, FALSE otherwise
     */
    public boolean exists(final Key key) {
        return this.storage.exists(key).join();
    }

    /**
     * Return the list of keys that start with this prefix, for
     * example "foo/bar/".
     *
     * @param prefix The prefix.
     * @return Collection of relative keys.
     */
    public Collection<Key> list(final Key prefix) {
        return this.storage.list(prefix).join();
    }

    /**
     * Save the content.
     *
     * @param key The key
     * @param content The content
     */
    public void save(final Key key, final byte[] content) {
        this.storage.save(key, new Content.From(content)).join();
    }

    /**
     * Moves value from one location to another.
     *
     * @param source Source key.
     * @param destination Destination key.
     */
    public void move(final Key source, final Key destination) {
        this.storage.move(source, destination).join();
    }

    /**
     * Get value size.
     *
     * @param key The key of value.
     * @return Size of value in bytes.
     * @deprecated Storage size is deprecated
     */
    @Deprecated
    public long size(final Key key) {
        return this.storage.size(key).join();
    }

    /**
     * Obtain value for the specified key.
     *
     * @param key The key
     * @return Value associated with the key
     */
    public byte[] value(final Key key) {
        return new Remaining(
            this.storage.value(key).thenApplyAsync(
                pub -> {
                    // OPTIMIZATION: Use size hint when available for pre-allocation
                    final long knownSize = pub.size().orElse(-1L);
                    return Concatenation.withSize(pub, knownSize).single().blockingGet();
                }
            ).join(),
            true
        ).bytes();
    }

    /**
     * Removes value from storage. Fails if value does not exist.
     *
     * @param key Key for value to be deleted.
     */
    public void delete(final Key key) {
        this.storage.delete(key).join();
    }

    /**
     * Removes all items with key prefix.
     *
     * @param prefix Key prefix.
     */
    public void deleteAll(final Key prefix) {
        this.storage.deleteAll(prefix).join();
    }
}
