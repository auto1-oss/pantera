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
package com.auto1.pantera.api;

/**
 * Raised when a configuration update keeps a secret masked ({@link SecretRedactor#MASK})
 * while changing the connection target (URL/endpoint/host) in the same object.
 *
 * <p>Restoring the stored secret against a caller-changed target would send the
 * real credential to a destination the caller chose without ever knowing the
 * secret. The update is refused so the caller must re-enter the secret when the
 * target changes.</p>
 *
 * @since 2.2.9
 */
public final class SecretRebindException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Ctor.
     * @param key The secret-bearing key whose target changed
     */
    public SecretRebindException(final String key) {
        super(
            "Re-enter the secret value for '" + key
                + "' when changing the connection URL, endpoint or host"
        );
    }
}
