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

import com.auto1.pantera.cache.StoragesCache;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test storages caches.
 * @since 0.28
 */
public final class TestStoragesCache extends StoragesCache {

    /**
     * Counter for `invalidateAll()` method calls.
     */
    private final AtomicInteger cnt;

    /**
     * Ctor.
     * Here an instance of cache is created. It is important that cache
     * is a local variable.
     */
    public TestStoragesCache() {
        super();
        this.cnt = new AtomicInteger(0);
    }

    @Override
    public void invalidateAll() {
        this.cnt.incrementAndGet();
        super.invalidateAll();
    }

    /**
     * Was this case invalidated?
     *
     * @return True, if it was invalidated once
     */
    public boolean wasInvalidated() {
        return this.cnt.get() == 1;
    }
}
