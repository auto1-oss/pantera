/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.misc;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import java.time.Instant;

/**
 * Generates abbreviated package metadata for npm clients.
 * 
 * <p>Abbreviated format (application/vnd.npm.install-v1+json) contains only essential fields
 * needed for package installation, reducing response size by 80-90%.</p>
 * 
 * <p>Format specification:
 * <pre>
 * {
 *   "name": "package-name",
 *   "modified": "2024-01-15T10:30:00.000Z",
 *   "dist-tags": {"latest": "1.0.0"},
 *   "versions": {
 *     "1.0.0": {
 *       "name": "package-name",
 *       "version": "1.0.0",
 *       "dist": {
 *         "tarball": "http://...",
 *         "shasum": "...",
 *         "integrity": "sha512-..."
 *       },
 *       "dependencies": {...},
 *       "devDependencies": {...},
 *       "peerDependencies": {...},
 *       "optionalDependencies": {...},
 *       "bundleDependencies": [...],
 *       "bin": {...},
 *       "engines": {...}
 *     }
 *   }
 * }
 * </pre>
 * </p>
 *
 * @since 1.19
 */
public final class AbbreviatedMetadata {
    
    /**
     * Essential version fields to keep in abbreviated format.
     */
    private static final String[] ESSENTIAL_FIELDS = {
        "name", "version", "dist", "dependencies", "devDependencies",
        "peerDependencies", "optionalDependencies", "bundleDependencies",
        "bin", "engines", "os", "cpu", "deprecated", "hasInstallScript"
    };
    
    /**
     * Full package metadata.
     */
    private final JsonObject full;
    
    /**
     * Ctor.
     * 
     * @param full Full package metadata
     */
    public AbbreviatedMetadata(final JsonObject full) {
        this.full = full;
    }
    
    /**
     * Generate abbreviated metadata.
     * 
     * @return Abbreviated JSON metadata
     */
    public JsonObject generate() {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        
        // Add top-level fields
        if (this.full.containsKey("name")) {
            builder.add("name", this.full.getString("name"));
        }
        
        // Add modified timestamp (current time or from 'time' object)
        final String modified = this.extractModifiedTime();
        builder.add("modified", modified);
        
        // CRITICAL: Include full 'time' object for pnpm compatibility
        // pnpm's time-based resolution mode requires version timestamps
        // See: https://pnpm.io/settings#registrysupportstimefield
        if (this.full.containsKey("time")) {
            builder.add("time", this.full.getJsonObject("time"));
        }
        
        // Add dist-tags
        if (this.full.containsKey("dist-tags")) {
            builder.add("dist-tags", this.full.getJsonObject("dist-tags"));
        }
        
        // Add abbreviated versions
        if (this.full.containsKey("versions")) {
            final JsonObject versions = this.full.getJsonObject("versions");
            final JsonObjectBuilder versionsBuilder = Json.createObjectBuilder();
            
            for (String version : versions.keySet()) {
                final JsonObject fullVersion = versions.getJsonObject(version);
                versionsBuilder.add(version, this.abbreviateVersion(fullVersion));
            }
            
            builder.add("versions", versionsBuilder.build());
        }
        
        return builder.build();
    }
    
    /**
     * Extract modified timestamp from metadata.
     * 
     * @return ISO 8601 timestamp
     */
    private String extractModifiedTime() {
        if (this.full.containsKey("time")) {
            final JsonObject time = this.full.getJsonObject("time");
            if (time.containsKey("modified")) {
                return time.getString("modified");
            }
        }
        // Fall back to current time
        return Instant.now().toString();
    }
    
    /**
     * Abbreviate a single version metadata.
     * 
     * @param fullVersion Full version metadata
     * @return Abbreviated version metadata
     */
    private JsonObject abbreviateVersion(final JsonObject fullVersion) {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        
        for (String field : ESSENTIAL_FIELDS) {
            if (fullVersion.containsKey(field)) {
                final JsonValue value = fullVersion.get(field);
                builder.add(field, value);
            }
        }
        
        return builder.build();
    }
}
