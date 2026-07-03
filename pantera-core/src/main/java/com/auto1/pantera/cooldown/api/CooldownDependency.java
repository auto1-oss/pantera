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
package com.auto1.pantera.cooldown.api;

import java.util.Objects;

/**
 * Dependency that was blocked together with the main artifact.
 */
public final class CooldownDependency {

    private final String artifact;
    private final String version;

    public CooldownDependency(final String artifact, final String version) {
        this.artifact = Objects.requireNonNull(artifact);
        this.version = Objects.requireNonNull(version);
    }

    public String artifact() {
        return this.artifact;
    }

    public String version() {
        return this.version;
    }
}
