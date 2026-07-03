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
 * explicit consumer.
 *
 * <p>The prefetch subsystem that owned this slot was deleted in M2
 * ({@code analysis/plan/v1/PLAN.md}). The registry is retained as a NO_OP-
 * defaulting hook surface so a future observed-coordinate prewarming feature
 * (Phase 4c, 2.3.0) can install a callback via {@link #setSharedCallback}
 * without redesigning the adapter chain. Until then every cache write
 * dispatches to the no-op sentinel.</p>
 *
 * <p>Thread-safety: the shared reference is volatile; reads are lock-free.
 * Tests that exercise the registry directly should call {@link #clear()}
 * in their {@code @AfterEach} to avoid leaking a callback into later forks.</p>
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
     * Install the shared callback. Reserved for a future
     * observed-coordinate prewarming subsystem (Phase 4c, 2.3.0).
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
     * Reference-equality check against the registry's no-op sentinel. Used by
     * {@link ProxyCacheWriter#commitVerified} to skip the synchronous temp-
     * file materialisation when the captured callback is the registry's
     * no-op (i.e., no consumer was installed at the time the writer was
     * constructed).
     *
     * @param callback Consumer to test; {@code null} treated as no-op.
     * @return {@code true} if the consumer is the registry's no-op sentinel
     *     (or null).
     */
    public boolean isNoOp(final Consumer<CacheWriteEvent> callback) {
        return callback == null || callback == NO_OP;
    }

    /**
     * Clear the shared reference (for tests).
     */
    public void clear() {
        this.shared = null;
    }
}
