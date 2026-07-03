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
package com.auto1.pantera.http.client.auth;

/**
 * Thrown by {@link OAuthTokenFormat#token(byte[])} when the upstream
 * token endpoint returned a parseable JSON body in the OAuth Registry V2
 * error envelope shape — {@code {"errors":[{"code":"…","message":"…"}]}}
 * — instead of a token.
 *
 * <p>This is an expected outcome (the upstream denies our request), not
 * a programming bug. The upper layer ({@code BearerAuthenticator},
 * {@code AuthClientSlice}) catches this and treats it as "skip this
 * upstream" — the group resolver then falls through to the next member.
 * Distinguishing it from {@link IllegalStateException} keeps the
 * "weird body" diagnostic in {@code BearerAuthenticator} from firing on
 * every anonymous-denied GCR / DHI hit.
 *
 * @since 2.2.0
 */
public final class UpstreamAuthDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param errorsJson Raw JSON literal of the {@code errors} array as
     *                   returned by the upstream — used as the message
     *                   for human inspection and downstream logs.
     */
    public UpstreamAuthDeniedException(final String errorsJson) {
        super("Upstream auth denied: " + errorsJson);
    }
}
