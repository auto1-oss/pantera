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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.http.cache.FetchSignal;

/**
 * What a single upstream fetch observed: the routing signal plus the raw
 * status code that produced it. The status is carried so a laundered 404 can
 * still name what it actually saw; it never changes the response status.
 *
 * <p>Local to this class on purpose: {@link com.auto1.pantera.http.resilience.SingleFlight}
 * is parameterized here as {@code SingleFlight<Key, UpstreamOutcome>}, so
 * carrying the extra status field is contained entirely within the npm
 * adapter. The shared {@link FetchSignal} enum -- consumed by every proxy
 * adapter -- is deliberately left untouched.
 *
 * @param signal Routing signal driving the response
 * @param status Upstream HTTP status, or 0 when there was no response
 * @since 2.2.5
 */
record UpstreamOutcome(FetchSignal signal, int status) {

    /**
     * Outcome with no upstream status (exception, timeout).
     * @param signal Routing signal
     */
    UpstreamOutcome(final FetchSignal signal) {
        this(signal, 0);
    }
}
