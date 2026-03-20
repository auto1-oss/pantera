/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.rpm.pkg;

import com.auto1.pantera.PanteraException;

/**
 * Exception indicates that package is invalid.
 *
 * @since 0.8.3
 */
@SuppressWarnings("serial")
public class InvalidPackageException extends PanteraException {
    /**
     * Ctor.
     *
     * @param cause Underlying cause for package being invalid.
     */
    public InvalidPackageException(final Throwable cause) {
        super(cause);
    }
}
