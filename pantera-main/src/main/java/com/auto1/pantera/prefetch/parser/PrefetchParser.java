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
package com.auto1.pantera.prefetch.parser;

import com.auto1.pantera.prefetch.Coordinate;
import java.nio.file.Path;
import java.util.List;

/**
 * Strategy that extracts prefetch coordinates from a manifest already
 * persisted on disk (a freshly cached pom, package.json, etc.).
 *
 * <p>Implementations must be safe to invoke from any thread, must not
 * throw on malformed input (return an empty list and log instead), and
 * should restrict themselves to direct/runtime dependencies — transitive
 * resolution is the dispatcher's job.</p>
 *
 * @since 2.2.0
 */
public interface PrefetchParser {

    /**
     * Extract direct prefetch coordinates from a manifest file.
     *
     * @param bytesOnDisk Path to the cached manifest (must be readable).
     * @return Direct dependency coordinates; empty if file is missing or malformed.
     */
    List<Coordinate> parse(Path bytesOnDisk);
}
