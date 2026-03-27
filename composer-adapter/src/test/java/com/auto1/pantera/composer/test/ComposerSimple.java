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
package com.auto1.pantera.composer.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple sample of composer for using in tests.
 * @since 0.4
 */
public final class ComposerSimple {
    /**
     * Repository url.
     */
    private final String url;

    /**
     * Package name.
     */
    private final String pkg;

    /**
     * Version of package for uploading.
     */
    private final String vers;

    /**
     * Ctor with default value for package name and version.
     * @param url Repository url
     */
    public ComposerSimple(final String url) {
        this(url, "vendor/package", "1.1.2");
    }

    /**
     * Ctor.
     * @param url Repository url
     * @param pkg Package name
     * @param vers Version of package for uploading
     */
    public ComposerSimple(final String url, final String pkg, final String vers) {
        this.url = url;
        this.pkg = pkg;
        this.vers = vers;
    }

    /**
     * Write composer to specified path.
     * @param path Path to save
     * @throws IOException In case of failure with writing.
     */
    public void writeTo(final Path path) throws IOException {
        Files.write(path, this.value());
    }

    /**
     * Bytes with composer json.
     * @return Composer sample.
     */
    private byte[] value() {
        return String.join(
            "",
            "{",
            "\"config\":{ \"secure-http\": false },",
            "\"repositories\": [",
            String.format("{\"type\": \"composer\", \"url\": \"%s\"},", this.url),
            "{\"packagist.org\": false} ",
            "],",
            String.format("\"require\": { \"%s\": \"%s\" }", this.pkg, this.vers),
            "}"
        ).getBytes();
    }
}
