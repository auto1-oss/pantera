/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
