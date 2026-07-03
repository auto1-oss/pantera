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
package com.auto1.pantera.maven.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;

import java.util.Optional;

/**
 * Maven metadata request detector implementing cooldown SPI.
 * Detects requests for {@code maven-metadata.xml} and extracts the
 * package (groupId:artifactId) path from the URL.
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code /com/example/my-lib/maven-metadata.xml} - metadata request,
 *       package = {@code com/example/my-lib}</li>
 *   <li>{@code /com/example/my-lib/1.0.0/my-lib-1.0.0.jar} - artifact download,
 *       not a metadata request</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class MavenMetadataRequestDetector implements MetadataRequestDetector {

    /**
     * The filename that identifies a Maven metadata request.
     */
    private static final String METADATA_FILE = "maven-metadata.xml";

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "maven";

    @Override
    public boolean isMetadataRequest(final String path) {
        return path != null && path.endsWith(METADATA_FILE);
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        if (!this.isMetadataRequest(path)) {
            return Optional.empty();
        }
        String stripped = path;
        if (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        // Remove trailing "/maven-metadata.xml" or "maven-metadata.xml"
        final int suffixLen = METADATA_FILE.length();
        if (stripped.length() <= suffixLen) {
            return Optional.empty();
        }
        // Strip the filename and the preceding slash
        String packageName = stripped.substring(
            0, stripped.length() - suffixLen
        );
        if (packageName.endsWith("/")) {
            packageName = packageName.substring(0, packageName.length() - 1);
        }
        if (packageName.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(packageName);
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
