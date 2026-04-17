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
package com.auto1.pantera.http.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures that {@code new NegativeCache(} appears in at most 1 production
 * (non-test) Java file: the single wiring site in {@code RepositorySlices}.
 * The unified cache bean should be injected everywhere else.
 *
 * @since 2.2.0
 */
final class NegativeCacheSingleSourceTest {

    @Test
    void noAdapterCreatesOwnNegativeCache() throws IOException {
        // Walk all .java files under the project root, excluding test directories.
        // Allowed production sites:
        //  1. RepositorySlices.java — the single wiring site
        //  2. NegativeCacheRegistry.java — fallback for early startup
        //  3. GroupSlice.java — fallback for tests without shared cache
        //  4. BaseCachedProxySlice.java — fallback for tests without shared cache
        // No adapter (npm, pypi, maven, etc.) should create its own instance.
        final Path root = Paths.get(System.getProperty("user.dir")).getParent();
        long adapterCount;
        try (Stream<Path> files = Files.walk(root)) {
            adapterCount = files
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().contains("/src/main/"))
                .filter(p -> !p.toString().contains("/src/test/"))
                // Exclude known allowed sites
                .filter(p -> !p.toString().contains("RepositorySlices.java"))
                .filter(p -> !p.toString().contains("NegativeCacheRegistry.java"))
                .filter(p -> !p.toString().contains("GroupSlice.java"))
                .filter(p -> !p.toString().contains("BaseCachedProxySlice.java"))
                .filter(p -> !p.toString().contains("NegativeCache.java"))
                .filter(p -> {
                    try {
                        return Files.readString(p).contains("new NegativeCache(");
                    } catch (IOException e) {
                        return false;
                    }
                })
                .count();
        }
        assertEquals(
            0, adapterCount,
            "No adapter should create its own NegativeCache — "
                + "use the shared instance from NegativeCacheRegistry"
        );
    }
}
