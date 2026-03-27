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
package com.auto1.pantera.npm.http.search;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Package search index interface.
 *
 * @since 1.1
 */
public interface PackageIndex {
    
    /**
     * Search packages.
     * @param query Search query
     * @param size Maximum results
     * @param from Offset
     * @return Future with matching packages
     */
    CompletableFuture<List<PackageMetadata>> search(String query, int size, int from);
    
    /**
     * Index package.
     * @param metadata Package metadata
     * @return Future that completes when indexed
     */
    CompletableFuture<Void> index(PackageMetadata metadata);
    
    /**
     * Remove package from index.
     * @param packageName Package name
     * @return Future that completes when removed
     */
    CompletableFuture<Void> remove(String packageName);
}
