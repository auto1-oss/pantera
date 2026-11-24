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
 * An upstream answered a status that the caller cannot turn into a result.
 *
 * <p>Adapters whose internal API returns "found or not" (an
 * {@code Optional}) have nowhere to put "the upstream answered 503": they
 * fail with this exception, which keeps the status and the headers of the
 * upstream answer ({@code Retry-After}, the circuit-open marker). The slice
 * that answers the client maps it with
 * {@link com.auto1.pantera.http.cache.UpstreamMiss}, exactly as if it had
 * seen the upstream answer itself. A generic exception here loses the status
 * and the marker, and the request ends as "not found" or as an unexplained
 * failure.</p>
 *
 * @since 2.2.14
 */
public final class UpstreamStatusException extends PanteraException {

    @Serial
    private static final long serialVersionUID = 4418036295180223750L;

    /**
     * Status the upstream answered.
     */
    private final int code;

    /**
     * Headers of the upstream answer.
     */
    private final transient Headers hdrs;

    /**
     * Ctor.
     * @param code Status the upstream answered
     * @param headers Headers of the upstream answer
     */
    public UpstreamStatusException(final int code, final Headers headers) {
        super("Upstream answered " + code);
        this.code = code;
        this.hdrs = headers;
    }

    /**
     * Status the upstream answered.
     * @return Status code
     */
    public int status() {
        return this.code;
    }

    /**
     * Headers of the upstream answer.
     * @return Headers, empty after deserialization
     */
    public Headers headers() {
        return this.hdrs == null ? Headers.EMPTY : this.hdrs;
    }
}
