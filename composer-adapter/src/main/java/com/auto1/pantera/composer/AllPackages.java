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
package com.auto1.pantera.composer;

import com.auto1.pantera.asto.Key;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Key for all packages value.
 *
 * @since 0.1
 */
public final class AllPackages implements Key {

    @Override
    public String string() {
        return "packages.json";
    }

    @Override
    public Optional<Key> parent() {
        return Optional.empty();
    }

    @Override
    public List<String> parts() {
        return Collections.singletonList(this.string());
    }
}
