/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.json.Json;
import javax.json.JsonObject;

/**
 * Per-version file layout for NPM packages.
 * 
 * <p>Eliminates lock contention by storing each version in its own file:</p>
 * <pre>
 * @scope/package/
 *   ├── .versions/
 *   │   ├── 1.0.0.json
 *   │   ├── 1.0.1.json
 *   │   └── 2.0.0.json
 *   ├── -/
 *   │   └── tarballs
 *   └── meta.json (generated on-demand)
 * </pre>
 * 
 * <p>Benefits:</p>
 * <ul>
 *   <li>Each import writes ONE file (no lock contention between versions)</li>
 *   <li>132 versions = 132 parallel writes (not serial!)</li>
 *   <li>Lock-free: Different versions never compete</li>
 *   <li>Self-healing: meta.json regenerated on each read</li>
 * </ul>
 * 
 * @since 1.18.13
 */
public final class PerVersionLayout {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     * 
     * @param storage Storage
     */
    public PerVersionLayout(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Add single version metadata to per-version file.
     * No locking needed - each version writes to its own file.
     *
     * @param packageKey Package key (e.g., "@scope/package")
     * @param version Version string
     * @param versionJson JSON metadata for this version
     * @return Completion stage
     */
    public CompletionStage<Void> addVersion(
        final Key packageKey,
        final String version,
        final JsonObject versionJson
    ) {
        final Key versionFile = this.versionFileKey(packageKey, version);

        // Add publish timestamp to version metadata if not present
        // This allows us to reconstruct the "time" object later
        final JsonObject versionWithTime;
        if (!versionJson.containsKey("_publishTime")) {
            final String now = java.time.Instant.now().toString();
            versionWithTime = Json.createObjectBuilder(versionJson)
                .add("_publishTime", now)
                .build();
        } else {
            versionWithTime = versionJson;
        }

        // Write directly - no locking needed!
        // Each version has its own file, so no contention
        final byte[] bytes = versionWithTime.toString().getBytes(StandardCharsets.UTF_8);
        return this.storage.save(versionFile, new Content.From(bytes))
            .toCompletableFuture();
    }

    /**
     * Generate meta.json by aggregating all version files.
     * This is called on-demand when clients request meta.json.
     * 
     * @param packageKey Package key (e.g., "@scope/package")
     * @return Completion stage with aggregated meta.json
     */
    public CompletionStage<JsonObject> generateMetaJson(final Key packageKey) {
        final Key versionsDir = this.versionsDir(packageKey);
        
        return this.storage.list(versionsDir)
            .thenCompose(versionFiles -> {
                if (versionFiles.isEmpty()) {
                    // No versions found, return empty meta
                    return CompletableFuture.completedFuture(
                        Json.createObjectBuilder()
                            .add("versions", Json.createObjectBuilder())
                            .build()
                    );
                }
                
                // Read all version files in parallel
                final CompletableFuture<JsonObject>[] futures = versionFiles.stream()
                    .filter(key -> key.string().endsWith(".json"))
                    .map(versionFile -> 
                        this.storage.value(versionFile)
                            .thenCompose(Content::asJsonObjectFuture)
                            .toCompletableFuture()
                            .exceptionally(err -> {
                                // If a version file is corrupted, skip it
                                return Json.createObjectBuilder().build();
                            })
                    )
                    .toArray(CompletableFuture[]::new);
                
                // Wait for all version files to be read
                return CompletableFuture.allOf(futures)
                    .thenApply(v -> {
                        // Merge all versions into meta.json structure
                        final var versionsBuilder = Json.createObjectBuilder();
                        final var metaBuilder = Json.createObjectBuilder();
                        
                        String packageName = null;
                        String latestVersion = null;
                        
                        for (CompletableFuture<JsonObject> future : futures) {
                            final JsonObject versionJson = future.join();
                            
                            if (versionJson.isEmpty()) {
                                continue;  // Skip corrupted files
                            }
                            
                            // Extract version number
                            final String version = versionJson.getString("version", null);
                            if (version == null) {
                                continue;
                            }
                            
                            // Extract package name (same for all versions)
                            if (packageName == null) {
                                packageName = versionJson.getString("name", packageKey.string());
                            }
                            
                            // Track latest version (simplified: last alphabetically)
                            if (latestVersion == null || version.compareTo(latestVersion) > 0) {
                                latestVersion = version;
                            }
                            
                            // Add to versions map
                            versionsBuilder.add(version, versionJson);
                        }
                        
                        // Build complete meta.json structure
                        if (packageName != null) {
                            metaBuilder.add("name", packageName);
                        }
                        if (latestVersion != null) {
                            metaBuilder.add("dist-tags", 
                                Json.createObjectBuilder()
                                    .add("latest", latestVersion)
                            );
                        }
                        metaBuilder.add("versions", versionsBuilder.build());
                        
                        return metaBuilder.build();
                    });
            });
    }

    /**
     * Check if package has any versions.
     * 
     * @param packageKey Package key
     * @return True if package has versions
     */
    public CompletionStage<Boolean> hasVersions(final Key packageKey) {
        final Key versionsDir = this.versionsDir(packageKey);
        return this.storage.list(versionsDir)
            .thenApply(keys -> !keys.isEmpty());
    }

    /**
     * Get key for per-version file.
     * 
     * @param packageKey Package key (e.g., "@scope/package")
     * @param version Version string
     * @return Key to .versions/VERSION.json
     */
    private Key versionFileKey(final Key packageKey, final String version) {
        // Sanitize version string (remove invalid filename chars)
        final String sanitized = version.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new Key.From(packageKey, ".versions", sanitized + ".json");
    }

    /**
     * Get key for versions directory.
     * 
     * @param packageKey Package key
     * @return Key to .versions/ directory
     */
    private Key versionsDir(final Key packageKey) {
        return new Key.From(packageKey, ".versions");
    }
}
