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
package com.auto1.pantera.asto.blob;

/**
 * Thrown by {@link CachedBlobStorage#save} when the WS1.4 hard admission
 * control (spec {@code WS1-storage-for-scale.md} &sect;3.D, acceptance #5)
 * evicted every eligible candidate and the incoming write still would not
 * fit under {@code cache.max-disk-bytes} -- e.g. the content itself exceeds
 * the configured bound, or every other entry is pinned {@code
 * PENDING_WRITE} and cannot be reclaimed.
 *
 * <p>Raised BEFORE any bytes are written to the local disk tier, mirroring
 * {@link WriteBackSaturatedException}'s "admission checked first" discipline
 * so a cache that cannot make room never grows past its bound.</p>
 *
 * @since 2.3.0
 */
public final class CacheAdmissionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Key whose write was rejected.
     */
    private final String key;

    /**
     * Size of the rejected write, in bytes.
     */
    private final long attemptedBytes;

    /**
     * Configured hard bound, in bytes.
     */
    private final long maxDiskBytes;

    /**
     * New admission-rejection exception.
     *
     * @param key Key whose write was rejected.
     * @param attemptedBytes Size of the rejected write, in bytes.
     * @param maxDiskBytes Configured hard bound, in bytes.
     */
    public CacheAdmissionRejectedException(final String key, final long attemptedBytes, final long maxDiskBytes) {
        super(
            "Cache admission rejected for key '" + key + "': " + attemptedBytes
            + " bytes would exceed the " + maxDiskBytes + "-byte cache bound even after evicting"
            + " every eligible (non-PENDING_WRITE) entry"
        );
        this.key = key;
        this.attemptedBytes = attemptedBytes;
        this.maxDiskBytes = maxDiskBytes;
    }

    /**
     * Key whose write was rejected.
     *
     * @return Non-null key string.
     */
    public String key() {
        return this.key;
    }

    /**
     * Size of the rejected write, in bytes.
     *
     * @return Attempted byte count.
     */
    public long attemptedBytes() {
        return this.attemptedBytes;
    }

    /**
     * Configured hard bound, in bytes.
     *
     * @return Max disk bytes.
     */
    public long maxDiskBytes() {
        return this.maxDiskBytes;
    }
}
