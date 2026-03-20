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

import java.nio.file.Path;

/**
 * Gem repository index.
 *
 * @since 1.0
 */
public interface GemIndex {

    /**
     * Update index.
     * @param path Repository index path
     */
    void update(Path path);
}
