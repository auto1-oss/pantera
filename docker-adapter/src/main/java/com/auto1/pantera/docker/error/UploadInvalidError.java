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

import java.util.Optional;

/**
 * The blob upload encountered an error and can no longer proceed as sent,
 * e.g. an out-of-order chunk.
 *
 * @since 2.2.9
 */
public final class UploadInvalidError implements DockerError {

    /**
     * Error detail.
     */
    private final String detail;

    /**
     * @param detail Error detail
     */
    public UploadInvalidError(final String detail) {
        this.detail = detail;
    }

    @Override
    public String code() {
        return "BLOB_UPLOAD_INVALID";
    }

    @Override
    public String message() {
        return "blob upload invalid";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.detail);
    }
}
