/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto;

import com.auto1.pantera.PanteraException;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Pantera input-output exception.
 * @since 1.0
 */
public class PanteraIOException extends PanteraException {

    private static final long serialVersionUID = 862160427262047490L;

    /**
     * New IO excption.
     * @param cause IO exception
     */
    public PanteraIOException(final IOException cause) {
        super(cause);
    }

    /**
     * New IO excption with message.
     * @param msg Message
     * @param cause IO exception
     */
    public PanteraIOException(final String msg, final IOException cause) {
        super(msg, cause);
    }

    /**
     * New IO exception.
     * @param cause Unkown exception
     */
    public PanteraIOException(final Throwable cause) {
        this(PanteraIOException.unwrap(cause));
    }

    /**
     * New IO exception.
     * @param msg Exception message
     * @param cause Unkown exception
     */
    public PanteraIOException(final String msg, final Throwable cause) {
        this(msg, PanteraIOException.unwrap(cause));
    }

    /**
     * New IO exception with message.
     * @param msg Exception message
     */
    public PanteraIOException(final String msg) {
        this(new IOException(msg));
    }

    /**
     * Resolve unkown exception to IO exception.
     * @param cause Unkown exception
     * @return IO exception
     */
    private static IOException unwrap(final Throwable cause) {
        final IOException iex;
        if (cause instanceof UncheckedIOException) {
            iex = ((UncheckedIOException) cause).getCause();
        } else if (cause instanceof IOException) {
            iex = (IOException) cause;
        } else {
            iex = new IOException(cause);
        }
        return iex;
    }
}
