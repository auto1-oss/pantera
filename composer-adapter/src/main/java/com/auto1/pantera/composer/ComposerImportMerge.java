/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.composer;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.http.log.EcsLogger;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Merges import staging files into final Satis p2/ layout.
 * 
 * <p>After bulk import completes, this consolidates per-version files from:</p>
 * <pre>
 * .artipie-import/composer/vendor/package/1.0.0.json
 * .artipie-import/composer/vendor/package/1.0.1.json
 * </pre>
 * 
 * <p>Into final Satis layout:</p>
 * <pre>
 * p2/vendor/package.json      (tagged versions)
 * p2/vendor/package~dev.json  (dev branches)
 * </pre>
 * 
 * <p>The merge operation:</p>
 * <ul>
 *   <li>Reads all version files for each package</li>
 *   <li>Separates dev branches from tagged releases</li>
 *   <li>Merges into appropriate p2/ files using proper locking</li>
 *   <li>Cleans up staging area after successful merge</li>
 * </ul>
 * 
 * @since 1.18.14
 */
public final class ComposerImportMerge {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Repository base URL.
     */
    private final Optional<String> baseUrl;

    /**
     * Successful merges counter.
     */
    private final AtomicLong mergedPackages;

    /**
     * Merged versions counter.
     */
    private final AtomicLong mergedVersions;

    /**
     * Failed merges counter.
     */
    private final AtomicLong failedPackages;

    /**
     * Ctor.
     * 
     * @param storage Storage
     * @param baseUrl Base URL for repository
     */
    public ComposerImportMerge(final Storage storage, final Optional<String> baseUrl) {
        this.storage = storage;
        this.baseUrl = baseUrl;
        this.mergedPackages = new AtomicLong(0);
        this.mergedVersions = new AtomicLong(0);
        this.failedPackages = new AtomicLong(0);
    }

    /**
     * Merge all staged imports into final p2/ layout.
     * 
     * @return Completion stage with merge statistics
     */
    public CompletionStage<MergeResult> mergeAll() {
        final Key stagingRoot = new Key.From(".versions");

        EcsLogger.info("com.artipie.composer")
            .message("Starting Composer import merge from staging area")
            .eventCategory("repository")
            .eventAction("import_merge")
            .field("file.directory", stagingRoot.string())
            .log();

        // NOTE: storage.exists() returns false for directories in FileStorage,
        // so we try to list the directory instead
        return this.discoverStagedPackages(stagingRoot)
            .thenCompose(packages -> {
                if (packages.isEmpty()) {
                    EcsLogger.info("com.artipie.composer")
                        .message("No staged imports found, nothing to merge")
                        .eventCategory("repository")
                        .eventAction("import_merge")
                        .eventOutcome("success")
                        .field("file.directory", ".versions/")
                        .log();
                    return CompletableFuture.completedFuture(
                        new MergeResult(0, 0, 0)
                    );
                }

                EcsLogger.info("com.artipie.composer")
                    .message("Found " + packages.size() + " packages to merge")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .log();
                
                // Merge each package sequentially to avoid overwhelming storage
                // (packages are independent, but we serialize to control concurrency)
                CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
                for (final String packageName : packages) {
                    chain = chain.thenCompose(ignored -> this.mergePackage(packageName));
                }
                
                return chain.thenApply(ignored -> new MergeResult(
                    this.mergedPackages.get(),
                    this.mergedVersions.get(),
                    this.failedPackages.get()
                ));
            })
            .thenCompose(result -> {
                // Clean up staging area after successful merge
                if (result.failedPackages == 0) {
                    EcsLogger.info("com.artipie.composer")
                        .message("Merge completed successfully (" + result.mergedPackages + " packages, " + result.mergedVersions + " versions), cleaning up staging area")
                        .eventCategory("repository")
                        .eventAction("import_merge")
                        .eventOutcome("success")
                        .log();
                    return this.cleanupStagingArea(stagingRoot)
                        .thenApply(ignored -> result);
                }
                EcsLogger.warn("com.artipie.composer")
                    .message("Merge completed with " + result.failedPackages + " failures (" + result.mergedPackages + " packages merged), keeping staging area for retry")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .eventOutcome("partial_failure")
                    .log();
                return CompletableFuture.completedFuture(result);
            });
    }

    /**
     * Discover all packages in staging area.
     * 
     * @param stagingRoot Root of staging area (.versions/)
     * @return List of package names (vendor/package format)
     */
    private CompletionStage<List<String>> discoverStagedPackages(final Key stagingRoot) {
        return this.storage.list(stagingRoot)
            .exceptionally(ex -> {
                // If .versions doesn't exist, return empty list
                EcsLogger.debug("com.artipie.composer")
                    .message("Staging area not found or empty")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .field("file.directory", stagingRoot.string())
                    .field("error.message", ex.getMessage())
                    .log();
                return List.of();
            })
            .thenCompose(keys -> {
                // Get list of version files
                final List<Key> versionFiles = keys.stream()
                    .filter(key -> key.string().endsWith(".json"))
                    .collect(Collectors.toList());
                
                if (versionFiles.isEmpty()) {
                    return CompletableFuture.completedFuture(List.of());
                }
                
                // Read first file from each package directory to get actual package name
                final Map<String, Key> dirToFile = versionFiles.stream()
                    .collect(Collectors.toMap(
                        key -> {
                            // Extract directory name (e.g., "wkda-api-abstract" from ".versions/wkda-api-abstract/1.0.0.json")
                            final String path = key.string();
                            final String relative = path.substring(stagingRoot.string().length() + 1);
                            return relative.substring(0, relative.indexOf('/'));
                        },
                        key -> key,
                        (existing, replacement) -> existing  // Keep first file
                    ));
                
                // Read each file to extract actual package name
                final List<CompletableFuture<Optional<String>>> packageFutures = dirToFile.values().stream()
                    .map(this::extractPackageNameFromFile)
                    .collect(Collectors.toList());
                
                return CompletableFuture.allOf(packageFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> packageFutures.stream()
                        .map(CompletableFuture::join)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .distinct()
                        .collect(Collectors.toList())
                    );
            });
    }
    
    /**
     * Extract package name from a version JSON file.
     * 
     * @param fileKey Key to version file
     * @return Package name in vendor/package format
     */
    private CompletableFuture<Optional<String>> extractPackageNameFromFile(final Key fileKey) {
        return this.storage.value(fileKey)
            .thenCompose(Content::asJsonObjectFuture)
            .thenApply(json -> {
                if (!json.containsKey("packages")) {
                    return Optional.<String>empty();
                }
                final JsonObject packages = json.getJsonObject("packages");
                // Get first package name (there should only be one)
                final Optional<String> packageName = packages.keySet().stream()
                    .findFirst()
                    .filter(name -> name.contains("/"));  // Ensure it's vendor/package format
                return packageName;
            })
            .exceptionally(ex -> {
                EcsLogger.warn("com.artipie.composer")
                    .message("Failed to extract package name from file")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .eventOutcome("failure")
                    .field("file.name", fileKey.string())
                    .field("error.message", ex.getMessage())
                    .log();
                return Optional.<String>empty();
            })
            .toCompletableFuture();
    }

    /**
     * Merge all versions of a single package.
     * 
     * @param packageName Package name (vendor/package)
     * @return Completion stage
     */
    private CompletionStage<Void> mergePackage(final String packageName) {
        EcsLogger.debug("com.artipie.composer")
            .message("Merging package")
            .eventCategory("repository")
            .eventAction("import_merge")
            .field("package.name", packageName)
            .log();
        
        // Convert vendor/package to vendor-package for directory name
        final String packageDir = packageName.replace("/", "-");
        final Key packageStagingDir = new Key.From(".versions", packageDir);
        
        return this.storage.list(packageStagingDir)
            .thenCompose(versionKeys -> {
                // Read all version files
                final List<CompletableFuture<Optional<JsonObject>>> versionFutures = versionKeys.stream()
                    .filter(key -> key.string().endsWith(".json"))
                    .map(key -> this.readVersionFile(key))
                    .toList();
                
                return CompletableFuture.allOf(versionFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> versionFutures.stream()
                        .map(CompletableFuture::join)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList())
                    );
            })
            .thenCompose(versionMetadataList -> {
                if (versionMetadataList.isEmpty()) {
                    EcsLogger.warn("com.artipie.composer")
                        .message("No valid version files found for package")
                        .eventCategory("repository")
                        .eventAction("import_merge")
                        .eventOutcome("failure")
                        .field("package.name", packageName)
                        .log();
                    return CompletableFuture.completedFuture(null);
                }
                
                // Separate dev branches from tagged releases
                final Map<String, JsonObject> devVersions = new HashMap<>();
                final Map<String, JsonObject> stableVersions = new HashMap<>();
                
                for (final JsonObject versionMetadata : versionMetadataList) {
                    this.extractVersions(versionMetadata, packageName, devVersions, stableVersions);
                }

                EcsLogger.debug("com.artipie.composer")
                    .message("Package '" + packageName + "' version breakdown: " + stableVersions.size() + " stable, " + devVersions.size() + " dev")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .field("package.name", packageName)
                    .log();
                
                // Merge stable and dev versions into p2/ files
                final CompletionStage<Void> stableMerge = stableVersions.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : this.mergeIntoP2File(packageName, false, stableVersions);
                
                final CompletionStage<Void> devMerge = devVersions.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : this.mergeIntoP2File(packageName, true, devVersions);
                
                return CompletableFuture.allOf(
                    stableMerge.toCompletableFuture(),
                    devMerge.toCompletableFuture()
                ).<Void>thenApply(ignored -> {
                    this.mergedPackages.incrementAndGet();
                    this.mergedVersions.addAndGet(stableVersions.size() + devVersions.size());
                    return null;
                });
            })
            .exceptionally(error -> {
                EcsLogger.error("com.artipie.composer")
                    .message("Failed to merge package")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .eventOutcome("failure")
                    .field("package.name", packageName)
                    .error(error)
                    .log();
                this.failedPackages.incrementAndGet();
                return null;
            });
    }

    /**
     * Extract versions from version metadata.
     * 
     * @param versionMetadata Metadata from version file
     * @param packageName Package name
     * @param devVersions Map to collect dev versions
     * @param stableVersions Map to collect stable versions
     */
    private void extractVersions(
        final JsonObject versionMetadata,
        final String packageName,
        final Map<String, JsonObject> devVersions,
        final Map<String, JsonObject> stableVersions
    ) {
        if (!versionMetadata.containsKey("packages")) {
            return;
        }
        
        final JsonObject packages = versionMetadata.getJsonObject("packages");
        if (!packages.containsKey(packageName)) {
            return;
        }
        
        final JsonObject versions = packages.getJsonObject(packageName);
        for (final Map.Entry<String, JsonValue> entry : versions.entrySet()) {
            final String version = entry.getKey();
            final JsonObject versionData = entry.getValue().asJsonObject();
            
            if (this.isDevBranch(version)) {
                devVersions.put(version, versionData);
            } else {
                stableVersions.put(version, versionData);
            }
        }
    }

    /**
     * Merge versions into p2/ file (stable or dev).
     * 
     * @param packageName Package name
     * @param isDev True for dev file, false for stable
     * @param versions Versions to merge
     * @return Completion stage
     */
    private CompletionStage<Void> mergeIntoP2File(
        final String packageName,
        final boolean isDev,
        final Map<String, JsonObject> versions
    ) {
        final String suffix = isDev ? "~dev.json" : ".json";
        final Key p2Key = new Key.From("p2", packageName + suffix);
        
        // Use exclusive lock for final p2/ file write
        // This is safe because merge runs AFTER all imports complete
        return this.storage.exclusively(
            p2Key,
            target -> target.exists(p2Key)
                .thenCompose(exists -> {
                    final CompletionStage<JsonObject> loadStage;
                    if (exists) {
                        loadStage = target.value(p2Key)
                            .thenCompose(Content::asJsonObjectFuture);
                    } else {
                        loadStage = CompletableFuture.completedFuture(
                            Json.createObjectBuilder()
                                .add("packages", Json.createObjectBuilder())
                                .build()
                        );
                    }
                    return loadStage;
                })
                .thenCompose(existing -> {
                    // Merge versions into existing metadata
                    final JsonObjectBuilder packagesBuilder = Json.createObjectBuilder();
                    if (existing.containsKey("packages")) {
                        final JsonObject packages = existing.getJsonObject("packages");
                        packages.forEach(packagesBuilder::add);
                    }
                    
                    // Add or update versions for this package
                    final JsonObjectBuilder versionsBuilder = Json.createObjectBuilder();
                    if (existing.containsKey("packages") 
                        && existing.getJsonObject("packages").containsKey(packageName)) {
                        final JsonObject existingVersions = existing.getJsonObject("packages")
                            .getJsonObject(packageName);
                        existingVersions.forEach(versionsBuilder::add);
                    }
                    
                    // Add all new versions (will overwrite duplicates)
                    versions.forEach(versionsBuilder::add);
                    
                    packagesBuilder.add(packageName, versionsBuilder.build());
                    
                    final JsonObject updated = Json.createObjectBuilder()
                        .add("packages", packagesBuilder.build())
                        .build();
                    
                    final byte[] bytes = updated.toString().getBytes(StandardCharsets.UTF_8);
                    return target.save(p2Key, new Content.From(bytes));
                })
        ).toCompletableFuture();
    }

    /**
     * Read version file from staging area.
     * 
     * @param key Version file key
     * @return Optional JSON object (empty if read fails)
     */
    private CompletableFuture<Optional<JsonObject>> readVersionFile(final Key key) {
        return this.storage.value(key)
            .thenCompose(content -> content.asBytesFuture())
            .thenApply(bytes -> {
                try {
                    final String json = new String(bytes, StandardCharsets.UTF_8);
                    try (JsonReader reader = Json.createReader(new StringReader(json))) {
                        return Optional.of(reader.readObject());
                    }
                } catch (final Exception error) {
                    EcsLogger.warn("com.artipie.composer")
                        .message("Failed to parse version file")
                        .eventCategory("repository")
                        .eventAction("import_merge")
                        .eventOutcome("failure")
                        .field("file.name", key.string())
                        .field("error.message", error.getMessage())
                        .log();
                    return Optional.<JsonObject>empty();
                }
            })
            .exceptionally(error -> {
                EcsLogger.warn("com.artipie.composer")
                    .message("Failed to read version file")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .eventOutcome("failure")
                    .field("file.name", key.string())
                    .field("error.message", error.getMessage())
                    .log();
                return Optional.empty();
            })
            .toCompletableFuture();
    }

    /**
     * Clean up staging area after successful merge.
     * 
     * @param stagingRoot Root of staging area
     * @return Completion stage
     */
    private CompletionStage<Void> cleanupStagingArea(final Key stagingRoot) {
        return this.storage.list(stagingRoot)
            .thenCompose(keys -> {
                final List<CompletableFuture<Void>> deletions = keys.stream()
                    .map(key -> this.storage.delete(key).toCompletableFuture())
                    .toList();
                
                return CompletableFuture.allOf(deletions.toArray(new CompletableFuture[0]));
            })
            .thenCompose(ignored -> this.storage.delete(stagingRoot))
            .exceptionally(error -> {
                EcsLogger.warn("com.artipie.composer")
                    .message("Failed to cleanup staging area")
                    .eventCategory("repository")
                    .eventAction("import_merge")
                    .eventOutcome("failure")
                    .field("error.message", error.getMessage())
                    .log();
                return null;
            });
    }

    /**
     * Determine if version is a dev branch.
     * 
     * @param version Version string
     * @return True if dev branch
     */
    private boolean isDevBranch(final String version) {
        final String lower = version.toLowerCase();
        return lower.startsWith("dev-")
            || lower.matches(".*\\.x-dev")
            || lower.equals("dev-master")
            || (lower.endsWith("-dev") && !lower.matches(".*\\d+\\.\\d+.*-dev"));
    }

    /**
     * Merge result statistics.
     */
    public static final class MergeResult {
        /**
         * Number of packages merged.
         */
        public final long mergedPackages;

        /**
         * Number of versions merged.
         */
        public final long mergedVersions;

        /**
         * Number of packages that failed to merge.
         */
        public final long failedPackages;

        /**
         * Ctor.
         * 
         * @param mergedPackages Merged packages count
         * @param mergedVersions Merged versions count
         * @param failedPackages Failed packages count
         */
        public MergeResult(
            final long mergedPackages,
            final long mergedVersions,
            final long failedPackages
        ) {
            this.mergedPackages = mergedPackages;
            this.mergedVersions = mergedVersions;
            this.failedPackages = failedPackages;
        }

        @Override
        public String toString() {
            return String.format(
                "MergeResult{packages=%d, versions=%d, failed=%d}",
                this.mergedPackages,
                this.mergedVersions,
                this.failedPackages
            );
        }
    }
}
