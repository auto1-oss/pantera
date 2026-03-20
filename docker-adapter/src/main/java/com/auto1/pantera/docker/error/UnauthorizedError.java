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
 * Client unauthorized error.
 *
 * @since 0.5
 */
public final class UnauthorizedError implements DockerError {

    @Override
    public String code() {
        return "UNAUTHORIZED";
    }

    @Override
    public String message() {
        return "authentication required";
    }

    @Override
    public Optional<String> detail() {
        return Optional.empty();
    }
}
