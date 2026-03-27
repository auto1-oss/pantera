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
package com.auto1.pantera.hex;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Class for working with resources.
 *
 * @since 0.1
 */
public final class ResourceUtil {
    /**
     * File name.
     */
    private final String name;

    /**
     * Ctor.
     *
     * @param name File name
     */
    public ResourceUtil(final String name) {
        this.name = name;
    }

    /**
     * Obtains resources from context loader.
     *
     * @return File path
     */
    public Path asPath() {
        try {
            return Paths.get(
                Objects.requireNonNull(
                    Thread.currentThread().getContextClassLoader().getResource(this.name)
                ).toURI()
            );
        } catch (final URISyntaxException error) {
            throw new IllegalStateException("Failed to obtain test recourse", error);
        }
    }
}
