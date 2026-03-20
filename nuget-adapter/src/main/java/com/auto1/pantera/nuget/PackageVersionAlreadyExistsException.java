/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.nuget;

import com.auto1.pantera.PanteraException;

/**
 * Exception indicates that package version cannot be added,
 * because it is already exists in the storage.
 *
 * @since 0.1
 */
@SuppressWarnings("serial")
public final class PackageVersionAlreadyExistsException extends PanteraException {

    /**
     * Ctor.
     *
     * @param message Exception details message.
     */
    public PackageVersionAlreadyExistsException(final String message) {
        super(message);
    }
}
