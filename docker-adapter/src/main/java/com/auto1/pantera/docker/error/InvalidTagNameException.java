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
 * Invalid tag name encountered during a manifest upload or any API operation.
 * See <a href="https://docs.docker.com/registry/spec/api/#errors-2">Errors</a>.
 *
 * @since 0.5
 */
@SuppressWarnings("serial")
public final class InvalidTagNameException extends RuntimeException implements DockerError {

    /**
     * Ctor.
     *
     * @param details Error details.
     */
    public InvalidTagNameException(final String details) {
        super(details);
    }

    @Override
    public String code() {
        return "TAG_INVALID";
    }

    @Override
    public String message() {
        return "invalid tag name";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(this.getMessage());
    }
}
