/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.http.search;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory package index implementation.
 * Simple implementation for basic search functionality.
 *
 * @since 1.1
 */
public final class InMemoryPackageIndex implements PackageIndex {
    
    /**
     * Packages map (name -> metadata).
     */
    private final Map<String, PackageMetadata> packages;
    
    /**
     * Constructor.
     */
    public InMemoryPackageIndex() {
        this.packages = new ConcurrentHashMap<>();
    }
    
    @Override
    public CompletableFuture<List<PackageMetadata>> search(
        final String query,
        final int size,
        final int from
    ) {
        final String lowerQuery = query.toLowerCase(Locale.ROOT);
        
        final List<PackageMetadata> results = this.packages.values().stream()
            .filter(pkg -> this.matches(pkg, lowerQuery))
            .skip(from)
            .limit(size)
            .collect(Collectors.toList());
            
        return CompletableFuture.completedFuture(results);
    }
    
    @Override
    public CompletableFuture<Void> index(final PackageMetadata metadata) {
        this.packages.put(metadata.name(), metadata);
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> remove(final String packageName) {
        this.packages.remove(packageName);
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Check if package matches query.
     * @param pkg Package metadata
     * @param query Query (lowercase)
     * @return True if matches
     */
    private boolean matches(final PackageMetadata pkg, final String query) {
        final String lowerName = pkg.name().toLowerCase(Locale.ROOT);
        final String lowerDesc = pkg.description().toLowerCase(Locale.ROOT);
        
        if (lowerName.contains(query) || lowerDesc.contains(query)) {
            return true;
        }
        
        return pkg.keywords().stream()
            .anyMatch(kw -> kw.toLowerCase(Locale.ROOT).contains(query));
    }
}
