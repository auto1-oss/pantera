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
package com.auto1.pantera.http.cache;

import java.util.function.Consumer;

/**
 * Global accessor for the single shared {@code onCacheWrite} callback used by
 * {@link BaseCachedProxySlice} when its constructor was called without an
 * explicit consumer (the legacy 10-arg / 8-arg overloads kept for backward
 * compatibility).
 *
 * <p>Boot wiring sets the dispatcher's
 * {@link com.auto1.pantera.prefetch.PrefetchDispatcher#onCacheWrite}-backed
 * consumer once via {@link #setSharedCallback(Consumer)}; every cache-write
 * thereafter fires the same consumer regardless of which proxy adapter served
 * the request. This mirrors the {@link NegativeCacheRegistry} pattern that
 * threads the singleton {@link NegativeCache} into every adapter without
 * surgery on each adapter's constructor chain.</p>
 *
 * <p>Thread-safety: the shared reference is volatile; reads are lock-free.
 * Set-once semantics are enforced by the boot wiring (a second call simply
 * replaces the consumer — useful for tests but not expected in production).</p>
 *
 * @since 2.2.0
 */
public final class CacheWriteCallbackRegistry {

    /**
     * No-op fallback used when no shared callback has been installed (tests,
     * early startup, DB-less boot). Returned by {@link #sharedCallback()} so
     * callers never need to null-check.
     */
    private static final Consumer<CacheWriteEvent> NO_OP = event -> { };

    /** Singleton instance. */
    private static final CacheWriteCallbackRegistry INSTANCE = new CacheWriteCallbackRegistry();

    /**
     * Currently installed callback; {@code null} when no consumer has been
     * registered.
     */
    private volatile Consumer<CacheWriteEvent> shared;

    private CacheWriteCallbackRegistry() {
    }

    /**
     * @return Process-wide singleton.
     */
    public static CacheWriteCallbackRegistry instance() {
        return INSTANCE;
    }

    /**
     * Install the shared callback. Called once from {@code VertxMain.start}
     * after the {@code PrefetchDispatcher} has been constructed.
     *
     * @param callback Consumer to invoke after every successful cache write.
     *     Must be thread-safe; throws are caught + logged inside
     *     {@link BaseCachedProxySlice}.
     */
    public void setSharedCallback(final Consumer<CacheWriteEvent> callback) {
        this.shared = callback;
    }

    /**
     * @return {@code true} if a shared callback has been installed.
     */
    public boolean isSharedCallbackSet() {
        return this.shared != null;
    }

    /**
     * @return Currently installed callback, or a no-op if none has been
     *     installed. Never {@code null}.
     */
    public Consumer<CacheWriteEvent> sharedCallback() {
        final Consumer<CacheWriteEvent> snap = this.shared;
        if (snap != null) {
            return snap;
        }
        return NO_OP;
    }

    /**
     * Clear the shared reference (for tests).
     */
    public void clear() {
        this.shared = null;
    }
}
