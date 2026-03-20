/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
 * Import staging layout for Composer packages - matches NPM pattern.
 * 
 * <p>During bulk imports, stores each version in its own file to avoid lock contention:</p>
 * <pre>
 * .versions/
 *   └── vendor-package/
 *       ├── 1.0.0.json
 *       ├── 1.0.1.json
 *       └── 2.0.0-beta.json
 * </pre>
 * 
 * <p>After import completes, use {@link ComposerImportMerge} to consolidate these into
 * the standard p2/ layout:</p>
 * <pre>
 * p2/
 *   └── vendor/
 *       └── package.json  (contains all versions)
 * </pre>
 * 
 * <p><b>IMPORTANT:</b> This is ONLY used during imports. Normal package uploads use
 * {@link SatisLayout} directly, which provides immediate availability.</p>
 * 
 * @since 1.18.14
 */
public final class ImportStagingLayout {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Repository base URL (e.g., "http://artipie.local/php-api").
     */
    private final Optional<String> baseUrl;

    /**
     * Ctor.
     * 
     * @param storage Storage
     * @param baseUrl Base URL for the repository
     */
    public ImportStagingLayout(final Storage storage, final Optional<String> baseUrl) {
        this.storage = storage;
        this.baseUrl = baseUrl;
    }

    /**
     * Add package version to import staging area.
     * Each version gets its own file - NO locking needed.
     * 
     * @param pack Package to add
     * @param version Version to add (required for imports)
     * @return Completion stage
     */
    public CompletionStage<Void> stagePackageVersion(
        final Package pack,
        final Optional<String> version
    ) {
        if (version.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Version is required for import staging")
            );
        }
        
        return pack.name().thenCompose(packageName -> {
            final String versionStr = version.get();
            
            // Create per-version file: .artipie-import/composer/vendor/package/version.json
            final Key versionKey = this.versionStagingKey(packageName.string(), versionStr);
            
            return pack.json().thenCompose(packageJson -> {
                // Add UID if not present (required by Composer v2)
                final JsonObjectBuilder versionWithUid = Json.createObjectBuilder(packageJson);
                if (!packageJson.containsKey("uid")) {
                    versionWithUid.add("uid", java.util.UUID.randomUUID().toString());
                }
                
                // Build per-version metadata: {packages: {vendor/package: {version: {...}}}}
                final JsonObject perVersionMetadata = Json.createObjectBuilder()
                    .add("packages", Json.createObjectBuilder()
                        .add(packageName.string(), Json.createObjectBuilder()
                            .add(versionStr, versionWithUid.build())
                        )
                    )
                    .build();
                
                final byte[] bytes = perVersionMetadata.toString().getBytes(StandardCharsets.UTF_8);
                
                // NO locking - each version writes to its own file
                return this.storage.save(versionKey, new Content.From(bytes));
            });
        }).toCompletableFuture();
    }

    /**
     * Get key for per-version staging file.
     * 
     * <p>Matches NPM pattern: .versions/vendor-package/version.json</p>
     * 
     * @param packageName Full package name (e.g., "vendor/package")
     * @param version Version string (ALREADY sanitized by MetadataRegenerator)
     * @return Key to .versions/vendor-package/version.json
     */
    private Key versionStagingKey(final String packageName, final String version) {
        // Version is already sanitized by MetadataRegenerator.sanitizeVersion()
        // Just ensure filename safety for filesystem (no additional changes to version itself)
        final String safeFilename = version
            .replaceAll("[/\\\\:]", "-");  // Only replace path separators for filename safety
        
        // Convert vendor/package to vendor-package for directory name (like NPM)
        final String packageDir = packageName.replace("/", "-");
        
        return new Key.From(
            ".versions",
            packageDir,
            safeFilename + ".json"
        );
    }
}
