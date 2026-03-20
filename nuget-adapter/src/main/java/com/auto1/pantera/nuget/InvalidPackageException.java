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
package com.auto1.pantera.nuget;

import com.auto1.pantera.PanteraException;

/**
 * Exception indicates that package is invalid and so cannot be handled by repository.
 *
 * @since 0.1
 */
@SuppressWarnings("serial")
public final class InvalidPackageException extends PanteraException {
    /**
     * Ctor.
     *
     * @param cause Underlying cause for package being invalid.
     */
    public InvalidPackageException(final Throwable cause) {
        super(cause);
    }
}
