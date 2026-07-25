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
 * Signals that a {@code DELETE} (manifest or blob) targeted a reference or
 * digest that does not resolve to an existing entry.
 *
 * <p>Deliberately does <b>not</b> implement {@link DockerError} — that
 * interface's implementations are mapped by {@code ErrorHandlingSlice} to
 * {@code 400 Bad Request}, which is the wrong status for "not found"
 * ({@code 404}). The delete slices catch this type explicitly and build
 * their own {@code 404} response with the OCI-appropriate error body
 * ({@code MANIFEST_UNKNOWN} / {@code BLOB_UNKNOWN}).
 */
@SuppressWarnings("serial")
public final class DockerReferenceNotFoundException extends RuntimeException {

    /**
     * @param details Error details.
     */
    public DockerReferenceNotFoundException(final String details) {
        super(details);
    }
}
