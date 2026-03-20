/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scheduling;

import com.auto1.pantera.ArtipieException;

/**
 * Throw this error on any event processing error occurred in consumer.
 * @since 1.13
 */
public final class EventProcessingError extends ArtipieException {

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
