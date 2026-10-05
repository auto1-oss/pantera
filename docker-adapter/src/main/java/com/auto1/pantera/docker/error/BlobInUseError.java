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

import com.auto1.pantera.docker.Digest;
import java.util.Optional;

/**
 * Error body for a blob delete refused because another image of the
 * registry still references the blob (see {@link BlobInUseException}).
 * Uses the OCI {@code DENIED} code: the client may not perform the delete,
 * independent of its permissions on the requested image.
 */
public final class BlobInUseError implements DockerError {

    /**
     * Blob digest.
     */
    private final Digest digest;

    /**
     * Ctor.
     *
     * @param digest Blob digest.
     */
    public BlobInUseError(final Digest digest) {
        this.digest = digest;
    }

    @Override
    public String code() {
        return "DENIED";
    }

    @Override
    public String message() {
        return "blob is referenced by another repository and was not deleted";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.digest.string());
    }
}
