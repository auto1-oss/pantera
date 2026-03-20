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
package com.auto1.pantera.asto.factory;

import com.auto1.pantera.PanteraException;

/**
 * Exception indicating that {@link StorageFactory} cannot be found.
 *
 * @since 1.13.0
 */
public class StorageNotFoundException extends PanteraException {

    private static final long serialVersionUID = 0L;

    /**
     * Ctor.
     *
     * @param type Storage type
     */
    public StorageNotFoundException(final String type) {
        super(String.format("Storage with type '%s' is not found.", type));
    }
}
