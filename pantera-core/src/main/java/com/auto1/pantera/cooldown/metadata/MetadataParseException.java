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
package com.auto1.pantera.cooldown.metadata;

/**
 * Exception thrown when metadata parsing fails.
 *
 * @since 1.0
 */
public final class MetadataParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message Error message
     */
    public MetadataParseException(final String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message Error message
     * @param cause Underlying cause
     */
    public MetadataParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
