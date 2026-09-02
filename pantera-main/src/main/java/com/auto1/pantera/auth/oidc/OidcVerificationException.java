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
package com.auto1.pantera.auth.oidc;

/**
 * An OIDC id_token failed verification. The message names the failed check
 * for the server-side log; callers never surface it to the client.
 *
 * @since 2.2.9
 */
public final class OidcVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Ctor.
     * @param message Failed check
     */
    public OidcVerificationException(final String message) {
        super(message);
    }

    /**
     * Ctor with cause.
     * @param message Failed check
     * @param cause Underlying failure
     */
    public OidcVerificationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
