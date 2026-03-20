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
package com.auto1.pantera.asto.ext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Support of new {@link CompletableFuture} API for JDK 1.8.
 * @param <T> Future type
 * @since 0.33
 */
public abstract class CompletableFutureSupport<T> implements Supplier<CompletableFuture<T>> {

    /**
     * Supplier wrap.
     */
    private final Supplier<? extends CompletableFuture<T>> wrap;

    /**
     * New wrapped future supplier.
     * @param wrap Supplier to wrap
     */
    protected CompletableFutureSupport(final Supplier<? extends CompletableFuture<T>> wrap) {
        this.wrap = wrap;
    }

    @Override
    public final CompletableFuture<T> get() {
        return this.wrap.get();
    }

    /**
     * Failed completable future supplier.
     * @param <T> Future type
     * @since 0.33
     */
    public static final class Failed<T> extends CompletableFutureSupport<T> {
        /**
         * New failed future.
         * @param err Failure exception
         */
        public Failed(final Exception err) {
            super(() -> {
                final CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(err);
                return future;
            });
        }
    }

}
