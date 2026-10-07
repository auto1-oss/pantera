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
 * Thrown by {@link CachedBlobStorage#save} in write-back mode when the
 * bounded write-back admission gate is saturated (spec {@code
 * WS1-storage-for-scale.md} &sect;3.C, acceptance #4).
 *
 * <p>Raised BEFORE any bytes are written to the local disk tier -- admission
 * is checked first specifically so a saturated queue cannot grow the disk
 * cache unbounded. Two independent consumers translate this into behaviour
 * appropriate to their call site (deliberately thin, central handling --
 * WS1.2 does not sprawl a saturation branch across every format adapter):
 * <ul>
 *   <li>{@code ProxyCacheWriter} (proxy read-through fill): the client has
 *   already been served its bytes via the tee, so saturation must NOT fail
 *   the read -- it is logged as a degraded-mode event and the cache write is
 *   skipped for this key.</li>
 *   <li>A hosted upload (the {@code save} call IS the request): the client is
 *   waiting, so this should map to {@code 503 Service Unavailable} with a
 *   {@code Retry-After} header carrying {@link #retryAfterSeconds()}.</li>
 * </ul>
 *
 * @since 2.3.0
 */
public final class WriteBackSaturatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Key whose write-back admission was rejected.
     */
    private final String key;

    /**
     * Suggested {@code Retry-After} duration, in seconds, for callers that
     * surface this as an HTTP response.
     */
    private final long retryAfterSeconds;

    /**
     * New saturation exception.
     *
     * @param key Key whose write-back admission was rejected.
     * @param retryAfterSeconds Suggested {@code Retry-After} hint, in seconds.
     */
    public WriteBackSaturatedException(final String key, final long retryAfterSeconds) {
        super(
            "Write-back queue saturated, rejecting save for key '" + key
            + "' (retry after " + retryAfterSeconds + "s)"
        );
        this.key = key;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Key whose write-back admission was rejected.
     *
     * @return Non-null key string.
     */
    public String key() {
        return this.key;
    }

    /**
     * Suggested {@code Retry-After} duration, in seconds.
     *
     * @return Non-negative retry-after hint.
     */
    public long retryAfterSeconds() {
        return this.retryAfterSeconds;
    }
}
