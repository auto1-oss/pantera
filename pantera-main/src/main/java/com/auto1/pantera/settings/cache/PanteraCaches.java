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
package com.auto1.pantera.settings.cache;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.cache.StoragesCache;

/**
 * Encapsulates caches which are possible to use in settings of Pantera server.
 *
 * @since 0.23
 */
public interface PanteraCaches {
    /**
     * Obtains storages cache.
     *
     * @return Storages cache.
     */
    StoragesCache storagesCache();

    /**
     * Obtains cache for user logins.
     *
     * @return Cache for user logins.
     */
    Cleanable<String> usersCache();

    /**
     * Obtains cache for user policy.
     *
     * @return Cache for policy.
     */
    Cleanable<String> policyCache();

    /**
     * Obtains filters cache.
     *
     * @return Filters cache.
     */
    FiltersCache filtersCache();

    /**
     * Implementation with all real instances of caches.
     *
     * @since 0.23
     */
    class All implements PanteraCaches {
        /**
         * Cache for user logins.
         */
        private final Cleanable<String> authcache;

        /**
         * Cache for configurations of storages.
         */
        private final StoragesCache strgcache;

        /**
         * Cache for user policy — already resolved by the caller to either
         * the raw policy (single-instance) or a {@code PublishingCleanable}
         * wrapper broadcasting invalidations cross-node (WS2.3, 2.3.0). A
         * no-op {@link Cleanable} when the policy isn't itself
         * {@link Cleanable} (e.g. a policy implementation with no local
         * cache to invalidate).
         */
        private final Cleanable<String> policyCache;

        /**
         * Cache for configurations of filters.
                 */
        private final FiltersCache filtersCache;

        /**
         * Ctor with all initialized caches.
         * @param users Users cache
         * @param strgcache Storages cache
         * @param policyCache Pantera policy cache — pre-wrapped by the
         *     caller when cross-node broadcast is available
         * @param filtersCache Filters cache
                         */
        public All(
            final Cleanable<String> users,
            final StoragesCache strgcache,
            final Cleanable<String> policyCache,
            final FiltersCache filtersCache
        ) {
            this.authcache = users;
            this.strgcache = strgcache;
            this.policyCache = policyCache;
            this.filtersCache = filtersCache;
        }

        @Override
        public StoragesCache storagesCache() {
            return this.strgcache;
        }

        @Override
        public Cleanable<String> usersCache() {
            return this.authcache;
        }

        @Override
        public Cleanable<String> policyCache() {
            return this.policyCache;
        }

        @Override
        public FiltersCache filtersCache() {
            return this.filtersCache;
        }
    }
}
