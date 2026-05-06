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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.http.log.EcsLogger;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import java.time.Instant;

/**
 * Enhances npm package metadata with complete fields required by npm/yarn/pnpm.
 * 
 * <p>Adds missing fields that clients expect:</p>
 * <ul>
 *   <li><b>time</b> object - Package publication timestamps</li>
 *   <li><b>users</b> object - Star/unstar functionality</li>
 *   <li><b>_attachments</b> - Tarball metadata (if missing)</li>
 * </ul>
 *
 * @since 1.19
 */
public final class MetadataEnhancer {
    
    /**
     * Original metadata.
     */
    private final JsonObject original;
    
    /**
     * Ctor.
     * 
     * @param original Original metadata JSON
     */
    public MetadataEnhancer(final JsonObject original) {
        this.original = original;
    }
    
    /**
     * Enhance metadata with complete fields.
     *
     * @return Enhanced metadata
     */
    public JsonObject enhance() {
        final JsonObjectBuilder builder = Json.createObjectBuilder(this.original);

        // Strip internal fields from versions before exposing to clients
        if (this.original.containsKey("versions")) {
            builder.add("versions", this.stripInternalFields(this.original.getJsonObject("versions")));
        }

        // Add dist-tags if missing or null (REQUIRED by npm/pnpm clients)
        if (!this.original.containsKey("dist-tags") 
            || this.original.isNull("dist-tags")) {
            builder.add("dist-tags", this.generateDistTags());
        }

        // Add time object if missing
        if (!this.original.containsKey("time")) {
            builder.add("time", this.generateTimeObject());
        }

        // Add users object if missing (empty by default)
        // P1.2: Can be populated with real star data via NpmStarRepository
        if (!this.original.containsKey("users")) {
            builder.add("users", Json.createObjectBuilder().build());
        }

        // Ensure _attachments exists (some tools depend on it)
        if (!this.original.containsKey("_attachments")) {
            builder.add("_attachments", Json.createObjectBuilder().build());
        }

        return builder.build();
    }
    
    /**
     * Enhance metadata with complete fields and star data.
     * 
     * @param usersObject Users object from star repository
     * @return Enhanced metadata with real star data
     */
    public JsonObject enhanceWithStars(final JsonObject usersObject) {
        final JsonObjectBuilder builder = Json.createObjectBuilder(this.original);
        
        // Add time object if missing
        if (!this.original.containsKey("time")) {
            builder.add("time", this.generateTimeObject());
        }
        
        // P1.2: Add users object with real star data
        builder.add("users", usersObject);
        
        // Ensure _attachments exists
        if (!this.original.containsKey("_attachments")) {
            builder.add("_attachments", Json.createObjectBuilder().build());
        }
        
        return builder.build();
    }
    
    /**
     * Generate dist-tags object with latest tag.
     *
     * <p>Finds the highest stable version (excluding prereleases) and sets it as "latest".
     * If no stable versions exist, uses the highest version overall.</p>
     *
     * <p>Structure:
     * <pre>
     * {
     *   "latest": "1.0.1"
     * }
     * </pre>
     * </p>
     *
     * @return Dist-tags object
     */
    private JsonObject generateDistTags() {
        final JsonObjectBuilder tagsBuilder = Json.createObjectBuilder();

        if (this.original.containsKey("versions")) {
            final JsonObject versions = this.original.getJsonObject("versions");

            // Find latest stable version using DescSortedVersions
            final java.util.List<String> stableVersions = new com.auto1.pantera.npm.misc.DescSortedVersions(
                versions,
                true  // excludePrereleases = true
            ).value();

            if (!stableVersions.isEmpty()) {
                // Use highest stable version
                tagsBuilder.add("latest", stableVersions.get(0));
            } else {
                // No stable versions - use highest version overall (including prereleases)
                final java.util.List<String> allVersions = new com.auto1.pantera.npm.misc.DescSortedVersions(
                    versions,
                    false  // excludePrereleases = false
                ).value();

                if (!allVersions.isEmpty()) {
                    tagsBuilder.add("latest", allVersions.get(0));
                }
            }
        }

        return tagsBuilder.build();
    }

    /**
     * Generate time object from version metadata.
     *
     * <p>Structure:
     * <pre>
     * {
     *   "created": "2020-01-01T00:00:00.000Z",
     *   "modified": "2024-01-15T10:30:00.000Z",
     *   "1.0.0": "2020-01-01T00:00:00.000Z",
     *   "1.0.1": "2020-06-15T00:00:00.000Z"
     * }
     * </pre>
     * </p>
     *
     * @return Time object
     */
    private JsonObject generateTimeObject() {
        final JsonObjectBuilder timeBuilder = Json.createObjectBuilder();
        final Instant now = Instant.now();

        // Track earliest and latest timestamps
        Instant earliest = now;
        Instant latest = now;

        // Extract timestamps from versions if available
        if (this.original.containsKey("versions")) {
            final JsonObject versions = this.original.getJsonObject("versions");

            for (String version : versions.keySet()) {
                final JsonObject versionMeta = versions.getJsonObject(version);

                // Try to extract timestamp from version metadata
                final Instant versionTime = this.extractVersionTime(versionMeta);

                // Add per-version timestamp
                timeBuilder.add(version, versionTime.toString());

                // Track earliest/latest
                if (versionTime.isBefore(earliest)) {
                    earliest = versionTime;
                }
                if (versionTime.isAfter(latest)) {
                    latest = versionTime;
                }
            }
        }

        // Add created and modified timestamps
        timeBuilder.add("created", earliest.toString());
        timeBuilder.add("modified", latest.toString());

        return timeBuilder.build();
    }

    /**
     * Strip internal fields from version metadata.
     * Removes fields like _publishTime that are used internally but should not be exposed to clients.
     *
     * @param versions Original versions object
     * @return Cleaned versions object
     */
    private JsonObject stripInternalFields(final JsonObject versions) {
        final JsonObjectBuilder cleaned = Json.createObjectBuilder();

        for (String version : versions.keySet()) {
            final JsonObject versionMeta = versions.getJsonObject(version);
            final JsonObjectBuilder versionCleaned = Json.createObjectBuilder();

            // Copy all fields except internal ones
            for (String key : versionMeta.keySet()) {
                if (!key.startsWith("_publish") && !"_time".equals(key)) {
                    versionCleaned.add(key, versionMeta.get(key));
                }
            }

            cleaned.add(version, versionCleaned.build());
        }

        return cleaned.build();
    }
    
    /**
     * Extract timestamp from version metadata.
     * Falls back to current time if not available.
     *
     * @param versionMeta Version metadata
     * @return Timestamp
     */
    private Instant extractVersionTime(final JsonObject versionMeta) {
        // Check if version has a _publishTime field (added by PerVersionLayout)
        if (versionMeta.containsKey("_publishTime")) {
            try {
                return Instant.parse(versionMeta.getString("_publishTime"));
            } catch (final Exception ex) {
                EcsLogger.debug("com.auto1.pantera.npm")
                    .message("Failed to parse _publishTime field")
                    .error(ex)
                    .log();
            }
        }

        // Check if version has a _time field
        if (versionMeta.containsKey("_time")) {
            try {
                return Instant.parse(versionMeta.getString("_time"));
            } catch (final Exception ex) {
                EcsLogger.debug("com.auto1.pantera.npm")
                    .message("Failed to parse _time field")
                    .error(ex)
                    .log();
            }
        }

        // Check if version has a publishTime field
        if (versionMeta.containsKey("publishTime")) {
            try {
                return Instant.parse(versionMeta.getString("publishTime"));
            } catch (final Exception ex) {
                EcsLogger.debug("com.auto1.pantera.npm")
                    .message("Failed to parse publishTime field")
                    .error(ex)
                    .log();
            }
        }

        // Fall back to current time
        return Instant.now();
    }
}
