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

import java.nio.charset.StandardCharsets;
import javax.json.Json;

/**
 * Simple sample of package for using in tests.
 * @since 0.4
 */
public class PackageSimple {
    /**
     * Repository url.
     */
    private final String url;

    /**
     * Package name.
     */
    private final String name;

    /**
     * Ctor.
     * @param url Repository url
     */
    public PackageSimple(final String url) {
        this(url, "vendor/package");
    }

    /**
     * Ctor.
     * @param url Repository url
     * @param name Package name
     */
    public PackageSimple(final String url, final String name) {
        this.url = url;
        this.name = name;
    }

    /**
     * Bytes with json package with specified version.
     * @return Package sample.
     */
    public byte[] withSetVersion() {
        return Json.createObjectBuilder()
            .add("name", this.name)
            .add("version", "1.1.2")
            .add(
                "dist",
                Json.createObjectBuilder()
                    .add("url", this.url)
                    .add("type", "zip")
            ).build()
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Bytes with json package without version.
     * @return Package sample.
     */
    public byte[] withoutVersion() {
        return Json.createObjectBuilder()
            .add("name", this.name)
            .add(
                "dist",
                Json.createObjectBuilder()
                    .add("url", this.url)
                    .add("type", "zip")
            ).build()
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }
}
