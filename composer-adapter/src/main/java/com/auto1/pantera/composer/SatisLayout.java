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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

/**
 * Satis-style repository layout with per-package provider files.
 * 
 * <p>Structure:</p>
 * <pre>
 * packages.json (root metadata with provider references)
 * p2/
 *   └── vendor/
 *       └── package.json (per-package metadata)
 * </pre>
 * 
 * <p>This eliminates lock contention by having each package write to its own file.</p>
 * 
 * @since 1.18.13
 */
public final class SatisLayout {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Repository base URL (e.g., "http://pantera.local/php-api").
     */
    private final Optional<String> baseUrl;

    /**
     * Ctor.
     * 
     * @param storage Storage
     * @param baseUrl Base URL for the repository
     */
    public SatisLayout(final Storage storage, final Optional<String> baseUrl) {
        this.storage = storage;
        this.baseUrl = baseUrl;
    }

    /**
     * Add package version to per-package provider file.
     * No global lock needed - each package has its own file.
     * 
     * @param pack Package to add
     * @param version Version to add (optional)
     * @return Completion stage
     */
    public CompletionStage<Void> addPackageVersion(
        final Package pack,
        final Optional<String> version
    ) {
        return pack.name().thenCompose(packageName -> {
            final Key packageKey = this.packageProviderKey(packageName.string(), version);
            
            return this.storage.exclusively(
                packageKey,  // Lock ONLY this package's file, not global packages.json
                target -> target.exists(packageKey)
                    .thenCompose(exists -> {
                        final CompletionStage<JsonObject> loadStage;
                        if (exists) {
                            loadStage = target.value(packageKey)
                                .thenCompose(Content::asJsonObjectFuture);
                        } else {
                            // Create new per-package provider
                            loadStage = CompletableFuture.completedFuture(
                                Json.createObjectBuilder()
                                    .add("packages", Json.createObjectBuilder())
                                    .build()
                            );
                        }
                        return loadStage;
                    })
                    .thenCompose(existing -> {
                        // Get package JSON and version
                        return pack.json().thenCompose(packageJson -> 
                            pack.version(version).thenApply(ver -> {
                                // Build updated per-package provider
                                final JsonObjectBuilder packagesBuilder = Json.createObjectBuilder();
                                if (existing.containsKey("packages")) {
                                    final JsonObject packages = existing.getJsonObject("packages");
                                    packages.forEach(packagesBuilder::add);
                                }
                                
                                // Add or update versions for this package
                                final JsonObjectBuilder versionsBuilder = Json.createObjectBuilder();
                                if (existing.containsKey("packages") 
                                    && existing.getJsonObject("packages").containsKey(packageName.string())) {
                                    final JsonObject versions = existing.getJsonObject("packages")
                                        .getJsonObject(packageName.string());
                                    versions.forEach(versionsBuilder::add);
                                }
                                
                                // Add the new version with UID for Composer v2
                                ver.ifPresent(v -> {
                                    final JsonObjectBuilder versionWithUid = Json.createObjectBuilder(packageJson);
                                    // Add UID if not present (required by Composer v2)
                                    if (!packageJson.containsKey("uid")) {
                                        versionWithUid.add("uid", java.util.UUID.randomUUID().toString());
                                    }
                                    versionsBuilder.add(v, versionWithUid.build());
                                });
                                packagesBuilder.add(
                                    packageName.string(),
                                    versionsBuilder.build()
                                );
                                
                                return Json.createObjectBuilder()
                                    .add("packages", packagesBuilder.build())
                                    .build();
                            })
                        );
                    })
                    .thenCompose(updated -> {
                        final byte[] bytes = updated.toString().getBytes(StandardCharsets.UTF_8);
                        return target.save(packageKey, new Content.From(bytes));
                    })
            );
        }).toCompletableFuture();
    }

    /**
     * Generate root packages.json with lazy/on-demand metadata loading.
     * Uses metadata-url pattern so Composer requests packages directly without
     * needing a full provider list. This is much faster and scales to millions of packages.
     * 
     * Note: This disables composer search functionality, but all other operations
     * (require, update, install) work perfectly.
     * 
     * @return Completion stage
     */
    public CompletionStage<Void> generateRootPackagesJson() {
        final Key rootKey = new Key.From("packages.json");
        
        // Build minimal packages.json with metadata-url pattern
        final JsonObjectBuilder root = Json.createObjectBuilder();
        
        // Empty packages object - all packages loaded on-demand
        root.add("packages", Json.createObjectBuilder());
        
        // Metadata URL pattern - Composer will replace %package% with actual package name
        // Composer v2 will try both stable and dev files automatically
        final String metadataUrl = this.baseUrl
            .map(url -> url + "/p2/%package%.json")
            .orElse("/p2/%package%.json");
        root.add("metadata-url", metadataUrl);
        
        // Also add available-packages-url for Composer v2 to discover both stable and dev versions
        // This tells Composer to also check for ~dev.json files
        final String availablePackagesUrl = this.baseUrl
            .map(url -> url + "/p2/available-packages.json")
            .orElse("/p2/available-packages.json");
        root.add("available-packages-url", availablePackagesUrl);
        
        final JsonObject rootJson = root.build();
        final byte[] bytes = rootJson.toString().getBytes(StandardCharsets.UTF_8);
        return this.storage.save(rootKey, new Content.From(bytes));
    }

    /**
     * Get key for per-package provider file.
     * 
     * <p>Following Packagist convention:</p>
     * <ul>
     *   <li>p2/vendor/package.json - ALL tagged versions (stable, RC, beta, alpha, etc.)</li>
     *   <li>p2/vendor/package~dev.json - ONLY dev branches (dev-master, x.y.x-dev, etc.)</li>
     * </ul>
     * 
     * @param packageName Full package name (e.g., "vendor/package")
     * @param version Version string to determine if dev branch or tagged release
     * @return Key to p2/vendor/package.json or p2/vendor/package~dev.json
     */
    private Key packageProviderKey(final String packageName, final Optional<String> version) {
        // Determine if this is a dev BRANCH (not a pre-release tag)
        // Dev branches: dev-master, dev-feature, 7.3.x-dev, 2.1.x-dev, etc.
        // Tagged releases (ALL go to main file): 1.0.0, 1.0.0-RC1, 1.0.0-beta, 1.0.0-alpha, etc.
        final boolean isDevBranch = version
            .map(v -> {
                final String lower = v.toLowerCase();
                // Match dev branches: dev-*, *.x-dev, *-dev (but NOT version-RC, version-beta, version-alpha)
                return lower.startsWith("dev-")           // dev-master, dev-feature
                    || lower.matches(".*\\.x-dev")        // 7.3.x-dev, 2.1.x-dev
                    || lower.equals("dev-master")         // explicit dev-master
                    || (lower.endsWith("-dev") && !lower.matches(".*\\d+\\.\\d+.*-dev")); // branch-dev but not version-dev
            })
            .orElse(false);
        
        // Use separate files ONLY for dev branches vs tagged releases
        // Main file (package.json): ALL tagged versions including RC, beta, alpha
        // Dev file (package~dev.json): ONLY dev branches
        final String suffix = isDevBranch ? "~dev.json" : ".json";
        return new Key.From("p2", packageName + suffix);
    }

}
