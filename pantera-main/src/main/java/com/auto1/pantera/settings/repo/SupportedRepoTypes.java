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
package com.auto1.pantera.settings.repo;

import java.util.Set;
import java.util.TreeSet;

/**
 * The repository types this server can serve -- exactly the types
 * {@code RepositorySlices} wires. The repository API refuses any other type
 * at write time: a stored config of an unknown type was accepted with 200
 * and then failed every request to the repository with a 500.
 *
 * @since 2.2.9
 */
public final class SupportedRepoTypes {

    /**
     * Supported types.
     */
    private static final Set<String> TYPES = Set.of(
        "file", "file-proxy", "file-group",
        "maven", "maven-proxy", "maven-group",
        "gradle", "gradle-proxy", "gradle-group",
        "npm", "npm-proxy", "npm-group",
        "pypi", "pypi-proxy", "pypi-group",
        "docker", "docker-proxy", "docker-group",
        "go", "go-proxy", "go-group",
        "php", "php-proxy", "php-group",
        "gem", "gem-group",
        "helm", "rpm", "nuget", "deb", "conda", "conan", "hexpm"
    );

    /**
     * Whether a type is supported.
     * @param type Repository type
     * @return True when {@code RepositorySlices} can serve it
     */
    public boolean contains(final String type) {
        return type != null && TYPES.contains(type);
    }

    /**
     * All supported types, sorted (for error messages and tests).
     * @return Sorted types
     */
    public Set<String> all() {
        return new TreeSet<>(TYPES);
    }
}
