/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.misc;

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
                if (!key.startsWith("_publish") && !key.equals("_time")) {
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
            } catch (Exception ignored) {
                // Fall through to next check
            }
        }

        // Check if version has a _time field
        if (versionMeta.containsKey("_time")) {
            try {
                return Instant.parse(versionMeta.getString("_time"));
            } catch (Exception ignored) {
                // Fall through to next check
            }
        }

        // Check if version has a publishTime field
        if (versionMeta.containsKey("publishTime")) {
            try {
                return Instant.parse(versionMeta.getString("publishTime"));
            } catch (Exception ignored) {
                // Fall through to default
            }
        }

        // Fall back to current time
        return Instant.now();
    }
}
