/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto;

import java.io.IOException;

/**
 * Exception indicating that value cannot be found in storage.
 *
 * @since 0.28
 */
@SuppressWarnings("serial")
public class ValueNotFoundException extends PanteraIOException {

    /**
     * Ctor.
     *
     * @param key Key that was not found.
     */
    public ValueNotFoundException(final Key key) {
        super(message(key));
    }

    /**
     * Ctor.
     *
     * @param key Key that was not found.
     * @param cause Original cause for exception.
     */
    public ValueNotFoundException(final Key key, final Throwable cause) {
        super(new IOException(message(key), cause));
    }

    /**
     * Build exception message for given key.
     *
     * @param key Key that was not found.
     * @return Message string.
     */
    private static String message(final Key key) {
        return String.format("No value for key: %s", key.string());
    }
}
