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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import java.util.ArrayList;
import java.util.Optional;

/**
 * The principal the auth layer verified for a request, carried back to the
 * access-log slice on an internal response header.
 *
 * <p>The access log is written by {@code EcsLoggingSlice}, which wraps the
 * auth slices and so only sees the raw {@code Authorization} header on the
 * way in. Reading the user from that header logged every Bearer client as
 * {@code anonymous} and a failed Basic login under the unverified claimed
 * name. The authz slices instead stamp the verified principal on the
 * response; the access-log slice reads it and strips it before the response
 * leaves Pantera. Any value an origin slice (for example a proxied upstream)
 * put there is removed first, so it cannot be spoofed.</p>
 *
 * @since 2.2.9
 */
public final class VerifiedPrincipal {

    /**
     * Internal response header name. Never sent to clients.
     */
    public static final String HEADER = "X-Pantera-Ctx-User";

    /**
     * Response carrying (or not) the header.
     */
    private final Response response;

    /**
     * Ctor.
     * @param response Response to read or re-stamp
     */
    public VerifiedPrincipal(final Response response) {
        this.response = response;
    }

    /**
     * The verified principal, if the auth layer stamped one.
     * @return Username
     */
    public Optional<String> user() {
        return this.response.headers().values(VerifiedPrincipal.HEADER)
            .stream().filter(val -> !val.isBlank()).findFirst();
    }

    /**
     * The response with any existing principal header replaced by
     * {@code user}, or removed when {@code user} is {@code null}.
     * @param user Verified username, or {@code null}
     * @return Re-stamped response
     */
    public Response stamped(final String user) {
        final Headers headers = new Headers(
            new ArrayList<>(
                this.response.headers().stream()
                    .filter(hdr -> !VerifiedPrincipal.HEADER.equalsIgnoreCase(hdr.getKey()))
                    .toList()
            )
        );
        if (user != null && !user.isBlank()) {
            headers.add(VerifiedPrincipal.HEADER, user);
        }
        return new Response(this.response.status(), headers, this.response.body());
    }

    /**
     * The response without the principal header.
     * @return Response safe to send to the client
     */
    public Response stripped() {
        return this.stamped(null);
    }
}
