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
package com.auto1.pantera.http.client.jetty;

import java.io.IOException;

/**
 * Thrown when an upstream HTTP/2 peer reset the response stream mid-body
 * (RST_STREAM frame received by Jetty's
 * {@code HttpReceiverOverHTTP2.read()}). Distinguishes a transient
 * upstream condition from a real protocol violation or local bug, so
 * the response layer can map it to a 502 + Retry-After rather than a
 * 500.
 *
 * <p>The stream-reset path is not a Pantera defect — it is the
 * standard HTTP/2 mechanism by which a server signals "I'm done with
 * this stream" (NO_ERROR), "I rotated my backend" (CANCEL/REFUSED),
 * or "I'm overloaded" (ENHANCE_YOUR_CALM). The upper-layer slice
 * decides retry vs propagate; this exception carries the upstream
 * URL + the original {@link java.io.EOFException} for diagnostics.
 *
 * @since 2.2.0
 */
public final class UpstreamStreamResetException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Upstream URL the stream-reset was observed on; useful for
     * diagnostics and metric tagging. May be {@code null} when the
     * cause arrived before headers parsing completed.
     */
    private final String upstreamUrl;

    /**
     * @param upstreamUrl Upstream URL (may be {@code null}).
     * @param cause Underlying {@link java.io.EOFException} from Jetty.
     */
    public UpstreamStreamResetException(final String upstreamUrl, final Throwable cause) {
        super(message(upstreamUrl), cause);
        this.upstreamUrl = upstreamUrl;
    }

    /** @return Upstream URL or {@code null}. */
    public String upstreamUrl() {
        return this.upstreamUrl;
    }

    private static String message(final String url) {
        if (url == null) {
            return "Upstream HTTP/2 peer reset the response stream mid-body";
        }
        return "Upstream HTTP/2 peer reset the response stream mid-body: " + url;
    }
}
