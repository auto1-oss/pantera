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
package com.auto1.pantera.http.client.egress;

import java.io.IOException;

/**
 * An outbound destination was refused by the {@link EgressPolicy}.
 * Extends {@link IOException} so it surfaces through the Jetty client the
 * same way a resolution failure does (the upstream is simply unreachable
 * from Pantera's point of view — a 502 to the caller, never a leak of what
 * lay behind the denied address).
 *
 * @since 2.2.9
 */
public final class EgressDeniedException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Denied host.
     */
    private final String host;

    /**
     * Ctor.
     *
     * @param host Denied host
     * @param reason Policy reason
     */
    public EgressDeniedException(final String host, final String reason) {
        super(String.format("Egress to '%s' denied: %s", host, reason));
        this.host = host;
    }

    /**
     * The denied host.
     *
     * @return Host
     */
    public String host() {
        return this.host;
    }
}
