/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

/**
 * Exception thrown when metadata parsing fails.
 *
 * @since 1.0
 */
public final class MetadataParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message Error message
     */
    public MetadataParseException(final String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message Error message
     * @param cause Underlying cause
     */
    public MetadataParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
