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
package com.auto1.pantera.npm;

/**
 * A publish payload is malformed or internally inconsistent, e.g. it
 * declares one target version while carrying the metadata or attachments
 * of another. Answered with 400 Bad Request.
 *
 * @since 2.2.10
 */
public final class InvalidPublishException extends IllegalArgumentException {

    /**
     * Serial version.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Ctor.
     * @param message Reason the payload is refused
     */
    public InvalidPublishException(final String message) {
        super(message);
    }
}
