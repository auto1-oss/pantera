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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Key;

/**
 * Docker registry root key.
 * @since 0.1
 */
public final class RegistryRoot extends Key.Wrap {

    /**
     * Registry root key.
     */
    public static final RegistryRoot V2 = new RegistryRoot("v2");

    /**
     * Ctor.
     * @param version Registry version
     */
    private RegistryRoot(final String version) {
        super(new Key.From("docker", "registry", version));
    }
}
