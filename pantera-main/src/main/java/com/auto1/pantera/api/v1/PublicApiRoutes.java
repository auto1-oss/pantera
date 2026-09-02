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
package com.auto1.pantera.api.v1;

import io.vertx.core.http.HttpMethod;
import java.util.regex.Pattern;

/**
 * Exact allowlist of the {@code /api/v1/*} routes that are served WITHOUT
 * the JWT filter — each is authenticated by its own means (password login,
 * SSO handshake, HMAC download token) or is a liveness probe.
 *
 * <p>SECURITY (2.2.9, SecOps auth-header-bypass #33): the filter used to
 * exempt any path that merely {@code contains("/artifact/download-direct")}
 * or {@code endsWith("/auth/token")} / {@code "/health"}. A substring match
 * is not a route: a protected route whose path embedded one of those
 * fragments — a PyPI yank on a repository named {@code artifact} with a
 * package named {@code download-direct}, say — ran with no authentication
 * at all. Exemption is now decided by HTTP method plus the exact path
 * shape of each registered public route.</p>
 *
 * @since 2.2.9
 */
public final class PublicApiRoutes {

    /**
     * {@code GET /api/v1/repositories/:name/artifact/download-direct} — the
     * HMAC-token download; {@code :name} is one path segment.
     */
    private static final Pattern DOWNLOAD_DIRECT = Pattern.compile(
        "/api/v1/repositories/[^/]+/artifact/download-direct"
    );

    /**
     * {@code GET /api/v1/auth/providers/:name/redirect} — SSO redirect.
     */
    private static final Pattern SSO_REDIRECT = Pattern.compile(
        "/api/v1/auth/providers/[^/]+/redirect"
    );

    private PublicApiRoutes() {
    }

    /**
     * Whether the request is one of the public routes and may skip JWT.
     *
     * @param method HTTP method
     * @param path Request path (no query string)
     * @return {@code true} only for an exact public route
     */
    public static boolean exempt(final HttpMethod method, final String path) {
        if (method == null || path == null) {
            return false;
        }
        final boolean get = HttpMethod.GET.equals(method);
        final boolean post = HttpMethod.POST.equals(method);
        return get && DOWNLOAD_DIRECT.matcher(path).matches()
            || post && "/api/v1/auth/token".equals(path)
            || post && "/api/v1/auth/callback".equals(path)
            || get && "/api/v1/auth/providers".equals(path)
            || get && SSO_REDIRECT.matcher(path).matches()
            || get && "/api/v1/health".equals(path);
    }
}
