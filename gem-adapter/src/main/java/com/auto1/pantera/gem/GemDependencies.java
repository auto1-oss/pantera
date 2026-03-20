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
package com.auto1.pantera.gem;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Set;

/**
 * Gem repository provides dependencies info in custom binary format.
 * User can request dependencies for multiple gems
 * and receive merged result for dependencies info.
 *
 * @since 1.3
 */
public interface GemDependencies {

    /**
     * Find dependencies for gems provided.
     * @param gems Set of gem paths
     * @return Binary dependencies data
     */
    ByteBuffer dependencies(Set<? extends Path> gems);
}
