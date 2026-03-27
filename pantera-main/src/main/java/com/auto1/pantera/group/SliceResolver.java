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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.Slice;

/**
 * Resolver of slices by repository name and port.
 */
@FunctionalInterface
public interface SliceResolver {
    /**
     * Resolve slice by repository name, port, and nesting depth.
     * @param name Repository name
     * @param port Server port
     * @param depth Nesting depth (0 for top-level, incremented for nested groups)
     * @return Resolved slice
     */
    Slice slice(Key name, int port, int depth);
}

