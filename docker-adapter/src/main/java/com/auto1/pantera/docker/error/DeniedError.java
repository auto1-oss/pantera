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
 * The access controller denied access for the operation on a resource.
 *
 * @since 0.5
 */
public final class DeniedError implements DockerError {

    @Override
    public String code() {
        return "DENIED";
    }

    @Override
    public String message() {
        return "requested access to the resource is denied";
    }

    @Override
    public Optional<String> detail() {
        return Optional.empty();
    }
}
