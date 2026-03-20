/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
