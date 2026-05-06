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
package com.auto1.pantera.test;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.cache.StoragesCache;
import com.auto1.pantera.settings.cache.PanteraCaches;
import com.auto1.pantera.settings.cache.FiltersCache;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Test Pantera caches.
 * @since 0.28
 */
public final class TestPanteraCaches implements PanteraCaches {

    /**
     * Cache for configurations of storages.
     */
    private final StoragesCache strgcache;

    /**
     * Was users invalidating method called?
     */
    private final AtomicLong cleanuser;

    /**
     * Was policy invalidating method called?
     */
    private final AtomicLong cleanpolicy;

    /**
     * Cache for configurations of filters.
     */
    private final FiltersCache filtersCache;

    /**
     * Ctor with all fake initialized caches.
     */
    public TestPanteraCaches() {
        this.strgcache = new TestStoragesCache();
        this.cleanuser = new AtomicLong();
        this.cleanpolicy = new AtomicLong();
        this.filtersCache = new TestFiltersCache();
    }

    @Override
    public StoragesCache storagesCache() {
        return this.strgcache;
    }

    @Override
    public Cleanable<String> usersCache() {
        return new Cleanable<>() {
            @Override
            public void invalidate(final String uname) {
                TestPanteraCaches.this.cleanuser.incrementAndGet();
            }

            @Override
            public void invalidateAll() {
                throw new NotImplementedException("method not implemented");
            }
        };
    }

    @Override
    public Cleanable<String> policyCache() {
        return new Cleanable<>() {
            @Override
            public void invalidate(final String uname) {
                TestPanteraCaches.this.cleanpolicy.incrementAndGet();
            }

            @Override
            public void invalidateAll() {
                throw new NotImplementedException("not implemented");
            }
        };
    }

    @Override
    public FiltersCache filtersCache() {
        return this.filtersCache;
    }

    /**
     * True if invalidate method of the {@link Cleanable} for users was called exactly one time.
     * @return True if invalidated
     */
    public boolean wereUsersInvalidated() {
        return this.cleanuser.get() == 1;
    }

    /**
     * True if invalidate method of the {@link Cleanable} for policy was called exactly one time.
     * @return True if invalidated
     */
    public boolean wasPolicyInvalidated() {
        return this.cleanpolicy.get() == 1;
    }
}
