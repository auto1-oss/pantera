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
package com.auto1.pantera.gem;

import com.auto1.pantera.PanteraException;

/**
 * An uploaded file is not a gem Pantera can index: it cannot be read as a
 * gem package, or its specification names no usable file name. The message
 * is meant for the client.
 *
 * @since 2.2.9
 */
public final class InvalidGemException extends PanteraException {

    private static final long serialVersionUID = 1L;

    /**
     * Ctor.
     * @param msg Reason for the client
     * @param cause Cause
     */
    public InvalidGemException(final String msg, final Throwable cause) {
        super(msg, cause);
    }
}
