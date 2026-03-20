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
package com.auto1.pantera.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.cactoos.list.ListOf;

/**
 * Class for storing maven settings xml.
 * @since 0.12
 */
public final class MavenSettings {
    /**
     * List with settings.
     */
    private final List<String> settings;

    /**
     * Ctor.
     * @param port Port for repository url.
     */
    public MavenSettings(final int port) {
        this.settings = Collections.unmodifiableList(
            new ListOf<String>(
                "<settings>",
                "    <profiles>",
                "        <profile>",
                "            <id>pantera</id>",
                "            <repositories>",
                "                <repository>",
                "                    <id>my-maven</id>",
                String.format("<url>http://host.testcontainers.internal:%d/my-maven/</url>", port),
                "                </repository>",
                "            </repositories>",
                "        </profile>",
                "    </profiles>",
                "    <activeProfiles>",
                "        <activeProfile>pantera</activeProfile>",
                "    </activeProfiles>",
                "</settings>"
            )
        );
    }

    /**
     * Write maven settings to the specified path.
     * @param path Path for writing
     * @throws IOException In case of exception during writing.
     */
    public void writeTo(final Path path) throws IOException {
        Files.write(
            path.resolve("settings.xml"),
            this.settings
        );
    }
}
