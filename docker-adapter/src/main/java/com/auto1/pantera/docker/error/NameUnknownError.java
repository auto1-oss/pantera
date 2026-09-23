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
 * The repository (image) name is not known to the registry.
 *
 * @since 2.2.9
 */
public final class NameUnknownError implements DockerError {

    /**
     * Image name.
     */
    private final String name;

    /**
     * @param name Image name
     */
    public NameUnknownError(final String name) {
        this.name = name;
    }

    @Override
    public String code() {
        return "NAME_UNKNOWN";
    }

    @Override
    public String message() {
        return "repository name not known to registry";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.name);
    }
}
