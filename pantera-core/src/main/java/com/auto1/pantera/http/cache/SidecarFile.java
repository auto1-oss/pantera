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
package com.auto1.pantera.http.cache;

import java.util.Objects;

/**
 * Checksum sidecar file generated alongside a cached artifact.
 * For example, Maven generates .sha1, .sha256, .md5 files next to each artifact.
 *
 * @param path Sidecar file path (e.g., "com/example/foo/1.0/foo-1.0.jar.sha256")
 * @param content Sidecar file content (the hex-encoded checksum string as bytes)
 * @since 1.20.13
 */
public record SidecarFile(String path, byte[] content) {

    /**
     * Ctor with validation.
     * @param path Sidecar file path
     * @param content Sidecar file content
     */
    public SidecarFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
    }
}
