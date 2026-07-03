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

import com.auto1.pantera.PanteraException;
import java.io.Serial;

/**
 * The outbound HTTP client's per-upstream circuit breaker is open and the
 * call was fast-failed with a synthesised 502 carrying the {@link #HEADER}
 * marker. Adapters that collapse upstream error responses into exceptions
 * (npm's {@code HttpNpmRemote}, maven's {@code CachedProxySlice}) throw this
 * type instead of a generic status exception so the group resolver can tell
 * "the breaker fast-failed locally" apart from "the upstream really failed"
 * — the former must NOT convict the group member on its health window.
 *
 * @since 2.2.0
 */
public final class UpstreamCircuitOpenException extends PanteraException {

    /**
     * Marker header set on synthesised circuit-open 502 responses by
     * {@code CircuitBreakingClientSlice} (http-client) and re-attached by
     * adapter error paths when they rebuild a response from this exception.
     * Value is always the literal string {@code "true"}.
     */
    public static final String HEADER = "X-Pantera-Circuit-Open";

    @Serial
    private static final long serialVersionUID = 7734515123818264031L;

    /**
     * Seconds until the breaker re-probes, from the synthesised response's
     * {@code Retry-After}. Zero when unknown.
     */
    private final long retryAfterSeconds;

    /**
     * @param retryAfterSeconds Seconds until the breaker re-probes;
     *     pass 0 when unknown.
     */
    public UpstreamCircuitOpenException(final long retryAfterSeconds) {
        super("Upstream circuit breaker is open");
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
    }

    /**
     * @return Seconds until the breaker re-probes; 0 when unknown.
     */
    public long retryAfterSeconds() {
        return this.retryAfterSeconds;
    }
}
