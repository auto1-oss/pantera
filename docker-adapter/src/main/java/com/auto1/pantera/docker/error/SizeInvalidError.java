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
 * The request body is larger than the registry accepts (413).
 *
 * @since 2.2.9
 */
public final class SizeInvalidError implements DockerError {

    @Override
    public String code() {
        return "SIZE_INVALID";
    }

    @Override
    public String message() {
        return "request body exceeds the configured size limit";
    }

    @Override
    public Optional<String> detail() {
        return Optional.empty();
    }
}
