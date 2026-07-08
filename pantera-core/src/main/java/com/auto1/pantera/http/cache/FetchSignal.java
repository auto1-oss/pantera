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
     * Upstream returned a non-404 that a multi-remote race launders into a 404
     * (e.g. a {@code 403}/{@code 429} rate-limit or {@code 410}) so the walk can
     * fall through to the next remote. The absence is NOT authoritative: waiting
     * callers should return 404 but mark it with {@link NegativeCache#SKIP_HEADER}
     * so a fronting group does not negative-cache it (the artifact may exist and
     * the upstream was merely throttling). Distinct from {@link #NOT_FOUND} so
     * the distinction survives fetch deduplication (all coalesced waiters get
     * the marked variant, not just the leader).
     */
    NOT_FOUND_UNVERIFIED,

    /**
     * Upstream returned an error (5xx, timeout, exception).
     * Waiting callers should return 503 or fall back to stale cache.
     */
    ERROR
}
