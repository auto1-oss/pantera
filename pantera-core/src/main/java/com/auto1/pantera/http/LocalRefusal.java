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
package com.auto1.pantera.http;

/**
 * A 5xx answer that Pantera produced itself, without asking the upstream.
 *
 * <p>Two things refuse a request locally: the outbound circuit breaker while
 * it is open ({@link UpstreamCircuitOpenException#HEADER}), and a
 * repository's bulkhead when no permit came free within its wait bound
 * ({@link #BULKHEAD}). Such an answer says nothing about the upstream or
 * about the artifact. A layer above a repository (the race over a proxy's
 * remotes, a group walking its members) must therefore neither count it as a
 * failure of that repository nor read it as "not found": it skips the
 * repository and, when nothing else answers, relays the refusal with its
 * {@code Retry-After}. Reporting it as 502 fails a build that a 503 with
 * {@code Retry-After} would have let the client repeat; counting it opens the
 * member's breaker on evidence that Pantera made up itself.</p>
 *
 * @since 2.2.12
 */
public final class LocalRefusal {

    /**
     * Header marking the 503 of a bulkhead without a free permit.
     */
    public static final String BULKHEAD = "X-Pantera-Bulkhead-Overflow";

    /**
     * Answer of a repository.
     */
    private final Response response;

    /**
     * Ctor.
     * @param response Answer of a repository
     */
    public LocalRefusal(final Response response) {
        this.response = response;
    }

    /**
     * Whether the answer is a local refusal.
     * @return True for a 5xx that carries the circuit-open or the bulkhead
     *  marker
     */
    public boolean present() {
        return this.response.status().serverError()
            && (this.marked(UpstreamCircuitOpenException.HEADER) || this.marked(LocalRefusal.BULKHEAD));
    }

    /**
     * Whether the bulkhead refused the request.
     * @return True for a 5xx that carries the bulkhead marker
     */
    public boolean bulkhead() {
        return this.response.status().serverError() && this.marked(LocalRefusal.BULKHEAD);
    }

    /**
     * Whether the answer carries a header.
     * @param name Header name
     * @return True if present
     */
    private boolean marked(final String name) {
        return !this.response.headers().values(name).isEmpty();
    }
}
