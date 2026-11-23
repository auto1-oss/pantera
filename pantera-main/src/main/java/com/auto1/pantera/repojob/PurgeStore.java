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

import java.util.List;
import java.util.Optional;

/**
 * The part of a storage a purge needs: a resumable, bounded listing of one
 * directory and an idempotent batch delete. Calls block; they run on the
 * job thread, never on an event loop.
 *
 * @since 2.2.14
 */
public interface PurgeStore {

    /**
     * Up to {@code limit} object names strictly inside a directory, in a
     * stable order, after the given name.
     * @param dir Directory (a repository key)
     * @param after Name to resume after, empty for the beginning
     * @param limit Maximum number of names
     * @return A page of names
     */
    Page list(String dir, Optional<String> after, int limit);

    /**
     * Delete objects. A name that does not exist is not an error.
     * @param names Object names
     * @throws StoreThrottled When the storage asks to slow down
     */
    void delete(List<String> names);

    /**
     * Abort uploads to the directory that were started and never completed.
     * @param dir Directory
     * @return Number of uploads aborted
     */
    int abortUploads(String dir);

    /**
     * A page of a listing.
     * @param names Object names
     * @param next Name to resume after, empty when the listing is finished
     */
    record Page(List<String> names, Optional<String> next) {
    }

    /**
     * The storage asked to slow down; nothing is wrong, retry later.
     */
    final class StoreThrottled extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Ctor.
         * @param cause Storage failure
         */
        public StoreThrottled(final Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
