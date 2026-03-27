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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.PanteraException;

/**
 * Throw this error on any event processing error occurred in consumer.
 * @since 1.13
 */
public final class EventProcessingError extends PanteraException {

    /**
     * Required serial.
     */
    private static final long serialVersionUID = 1843017424729658155L;

    /**
     * Ctor.
     * @param msg Error message
     * @param cause Error cause
     */
    public EventProcessingError(final String msg, final Throwable cause) {
        super(msg, cause);
    }

}
