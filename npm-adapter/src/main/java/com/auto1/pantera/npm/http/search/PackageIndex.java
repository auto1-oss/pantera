/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
