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
 * A request body exceeded the byte limit it was metered against.
 *
 * <p>Raised by {@link com.auto1.pantera.http.body.BoundedContent} as the
 * body's terminal error once the ACTUAL bytes delivered pass the limit —
 * regardless of framing, so a chunked body (no {@code Content-Length}) is
 * bounded exactly like a declared one. The server maps it to
 * {@code 413 Payload Too Large}; {@code ContentLengthRestriction} maps it
 * the same way for the per-repository cap. Carries the limit and the
 * byte count observed when it tripped for the 413 log line.</p>
 *
 * @since 2.2.9
 */
public final class RequestBodyTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The byte limit that was exceeded.
     */
    private final long limit;

    /**
     * Bytes observed when the limit tripped.
     */
    private final long observed;

    /**
     * Ctor.
     *
     * @param limit The byte limit that was exceeded
     * @param observed Bytes observed when the limit tripped
     */
    public RequestBodyTooLargeException(final long limit, final long observed) {
        super(
            String.format(
                "request body exceeded the %d-byte limit (%d bytes received)",
                limit, observed
            )
        );
        this.limit = limit;
        this.observed = observed;
    }

    /**
     * @return The byte limit that was exceeded
     */
    public long limit() {
        return this.limit;
    }

    /**
     * @return Bytes observed when the limit tripped
     */
    public long observed() {
        return this.observed;
    }

    /**
     * Whether {@code error} (or any cause beneath it) is a body-limit
     * violation — the check every 413-mapping site needs, because the
     * exception surfaces through {@code CompletionException} and adapter
     * wrappers.
     *
     * @param error Throwable to inspect
     * @return {@code true} if a body-limit violation is in the cause chain
     */
    public static boolean isCause(final Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof RequestBodyTooLargeException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
