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
 * Invalid repository name encountered either during manifest validation or any API operation.
 *
 * @since 0.5
 */
@SuppressWarnings("serial")
public final class InvalidRepoNameException extends RuntimeException implements DockerError {

    /**
     * Ctor.
     *
     * @param details Error details.
     */
    public InvalidRepoNameException(final String details) {
        super(details);
    }

    @Override
    public String code() {
        return "NAME_INVALID";
    }

    @Override
    public String message() {
        return "invalid repository name";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.getMessage());
    }
}
