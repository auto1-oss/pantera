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
package com.auto1.pantera.rpm.meta;

/**
 * Various error/problems with xml parsing/reading/writing.
 * @since 1.3
 */
@SuppressWarnings("serial")
public final class XmlException extends RuntimeException {

    /**
     * Ctor.
     * @param cause Error cause
     */
    public XmlException(final Throwable cause) {
        super(cause);
    }

    /**
     * Ctor.
     * @param message Error message
     */
    XmlException(final String message) {
        super(message);
    }

    /**
     * Ctor.
     * @param message Message
     * @param cause Error cause
     */
    public XmlException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
