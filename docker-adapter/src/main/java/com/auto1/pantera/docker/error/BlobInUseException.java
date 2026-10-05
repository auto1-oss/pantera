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
package com.auto1.pantera.docker.error;

/**
 * Signals that a blob {@code DELETE} was refused because the blob is also
 * referenced by another image of the same registry. Blobs live in a
 * registry-wide content-addressed store, so removing the data on behalf of
 * one image would break every other image that uses it.
 *
 * <p>Like {@link DockerReferenceNotFoundException}, deliberately not a
 * {@link DockerError}: the blob delete slice maps it to {@code 409 Conflict}
 * itself.</p>
 */
@SuppressWarnings("serial")
public final class BlobInUseException extends RuntimeException {

    /**
     * @param details Error details.
     */
    public BlobInUseException(final String details) {
        super(details);
    }
}
