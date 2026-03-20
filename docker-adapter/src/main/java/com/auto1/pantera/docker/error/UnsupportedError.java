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
 * The operation was unsupported due to a missing implementation or invalid set of parameters.
 * See <a href="https://docs.docker.com/registry/spec/api/#errors-2">Errors</a>.
 *
 * @since 0.8
 */
public final class UnsupportedError implements DockerError {

    @Override
    public String code() {
        return "UNSUPPORTED";
    }

    @Override
    public String message() {
        return "The operation is unsupported.";
    }

    @Override
    public Optional<String> detail() {
        return Optional.empty();
    }
}
