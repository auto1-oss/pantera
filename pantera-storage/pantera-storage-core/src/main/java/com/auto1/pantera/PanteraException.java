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
package com.auto1.pantera;

/**
 * Base Pantera exception.
 * <p>It should be used as a base exception for all Pantera public APIs
 * as a contract instead of others.</p>
 *
 * @since 1.0
 * @implNote PanteraException is unchecked exception, but it's a good
 *  practice to document it via {@code throws} tag in JavaDocs.
 */
public class PanteraException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * New exception with message and base cause.
     * @param msg Message
     * @param cause Cause
     */
    public PanteraException(final String msg, final Throwable cause) {
        super(msg, cause);
    }

    /**
     * New exception with base cause.
     * @param cause Cause
     */
    public PanteraException(final Throwable cause) {
        super(cause);
    }

    /**
     * New exception with message.
     * @param msg Message
     */
    public PanteraException(final String msg) {
        super(msg);
    }
}
