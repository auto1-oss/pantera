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

/**
 * Signal indicating the outcome of a deduplicated fetch.
 *
 * <p>Top-level enum (promoted in WI-post-05) so callers that coalesce
 * upstream fetches via {@link com.auto1.pantera.http.resilience.SingleFlight}
 * can import the signal from a stable package-level location.
 *
 * @since 1.20.13
 */
public enum FetchSignal {
    /**
     * Upstream returned 200 and content is now cached in storage.
     * Waiting callers should read from cache.
     */
    SUCCESS,

    /**
     * Upstream returned 404. Negative cache has been updated.
     * Waiting callers should return 404.
     */
    NOT_FOUND,

    /**
     * Upstream returned an error (5xx, timeout, exception).
     * Waiting callers should return 503 or fall back to stale cache.
     */
    ERROR
}
