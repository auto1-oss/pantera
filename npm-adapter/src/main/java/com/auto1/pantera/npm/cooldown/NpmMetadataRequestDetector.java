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
package com.auto1.pantera.npm.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;
import java.util.Optional;

/**
 * Detects npm metadata requests.
 *
 * <p>npm metadata requests are package document fetches (e.g. {@code GET /lodash}
 * or {@code GET /@scope/pkg}). Tarball downloads contain {@code /-/} in the path
 * (e.g. {@code /lodash/-/lodash-4.17.21.tgz}).</p>
 *
 * <p>Note: npm metadata filtering is currently handled by
 * {@code DownloadPackageSlice} directly. This detector is registered in the
 * adapter bundle for completeness and future unification.</p>
 *
 * @since 2.2.0
 */
public final class NpmMetadataRequestDetector implements MetadataRequestDetector {

    @Override
    public boolean isMetadataRequest(final String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return false;
        }
        // Tarball downloads contain /-/ in the path
        if (path.contains("/-/")) {
            return false;
        }
        // Security audit endpoints are not metadata
        if (path.contains("/npm/v1/security/")) {
            return false;
        }
        // User management endpoints
        if (path.contains("/-/user/") || path.contains("/-/v1/login")
            || path.contains("/-/whoami")) {
            return false;
        }
        // Everything else is a package metadata request
        return true;
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        if (!isMetadataRequest(path)) {
            return Optional.empty();
        }
        // Strip leading slash
        String name = path.startsWith("/") ? path.substring(1) : path;
        // Strip trailing slash
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    @Override
    public String repoType() {
        return "npm";
    }
}
