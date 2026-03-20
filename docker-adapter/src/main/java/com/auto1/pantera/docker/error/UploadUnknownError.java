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
 * If a blob upload has been cancelled or was never started, this error code may be returned.
 *
 * @since 0.5
 */
public final class UploadUnknownError implements DockerError {

    /**
     * Upload UUID.
     */
    private final String uuid;

    /**
     * Ctor.
     *
     * @param uuid Upload UUID.
     */
    public UploadUnknownError(final String uuid) {
        this.uuid = uuid;
    }

    @Override
    public String code() {
        return "BLOB_UPLOAD_UNKNOWN";
    }

    @Override
    public String message() {
        return "blob upload unknown to registry";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.uuid);
    }
}
