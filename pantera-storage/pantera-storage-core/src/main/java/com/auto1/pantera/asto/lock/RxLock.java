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
package com.auto1.pantera.asto.lock;

import hu.akarnokd.rxjava2.interop.CompletableInterop;
import io.reactivex.Completable;

/**
 * Reactive adapter for {@link Lock}.
 *
 * @since 0.27
 */
public final class RxLock {

    /**
     * Origin.
     */
    private final Lock origin;

    /**
     * Ctor.
     *
     * @param origin Origin.
     */
    public RxLock(final Lock origin) {
        this.origin = origin;
    }

    /**
     * Acquire the lock.
     *
     * @return Completion of lock acquire operation.
     */
    public Completable acquire() {
        return Completable.defer(() -> CompletableInterop.fromFuture(this.origin.acquire()));
    }

    /**
     * Release the lock.
     *
     * @return Completion of lock release operation.
     */
    public Completable release() {
        return Completable.defer(() -> CompletableInterop.fromFuture(this.origin.release()));
    }
}
