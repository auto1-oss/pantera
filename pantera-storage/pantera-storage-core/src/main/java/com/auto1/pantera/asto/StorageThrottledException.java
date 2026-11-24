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
package com.auto1.pantera.asto;

import com.auto1.pantera.PanteraException;

/**
 * The storage backend refused an operation because the caller sends requests
 * too fast (S3 {@code 503 SlowDown}, {@code RequestLimitExceeded}). Nothing
 * is wrong with the request itself: the caller backs off and repeats it.
 *
 * @since 2.2.14
 */
public final class StorageThrottledException extends PanteraException {

    private static final long serialVersionUID = 4107264522336187013L;

    /**
     * Ctor.
     * @param msg Message
     */
    public StorageThrottledException(final String msg) {
        super(msg);
    }

    /**
     * Ctor.
     * @param msg Message
     * @param cause Backend failure
     */
    public StorageThrottledException(final String msg, final Throwable cause) {
        super(msg, cause);
    }
}
