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

import com.auto1.pantera.docker.ManifestReference;

import java.util.Optional;

/**
 * This error is returned when the manifest, identified by name and tag
 * is unknown to the repository.
 */
public final class ManifestError implements DockerError {

    /**
     * Manifest reference.
     */
    private final ManifestReference ref;

    /**
     * Ctor.
     *
     * @param ref Manifest reference.
     */
    public ManifestError(ManifestReference ref) {
        this.ref = ref;
    }

    @Override
    public String code() {
        return "MANIFEST_UNKNOWN";
    }

    @Override
    public String message() {
        return "manifest unknown";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.ref.digest());
    }
}
