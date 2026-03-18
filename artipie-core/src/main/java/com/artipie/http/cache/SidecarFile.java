/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.cache;

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
