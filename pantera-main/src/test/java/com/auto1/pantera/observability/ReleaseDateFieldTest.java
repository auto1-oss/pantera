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
package com.auto1.pantera.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies Fix B: release_date field must never fall back to the string "unknown".
 *
 * <p>Prior to this fix, some code paths used
 * {@code releaseDate().orElse("unknown")} which wrote the literal string
 * "unknown" into the {@code package.release_date} ECS field, making it
 * unsearchable as a date and polluting dashboards.
 *
 * <p>The correct fix is to omit the field entirely when the date is absent,
 * which is what the current cooldown and DB code does.
 *
 * @since 2.1.0
 */
final class ReleaseDateFieldTest {

    /**
     * No main source file may combine {@code .orElse("unknown")} with a {@code release_date}
     * field on the same non-comment line.
     *
     * <p>Walks all {@code .java} files under {@code src/main/java} trees project-wide and
     * fails if any file contains both string literals on the same uncommented line — a proxy
     * for the anti-pattern where a release date falls back to the string "unknown" instead of
     * being omitted.
     *
     * @throws Exception on I/O error
     */
    @Test
    void noUnknownReleaseDateInMainSources() throws Exception {
        final Path root = Path.of("").toAbsolutePath();
        try (Stream<Path> files = Files.walk(root)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> p.toString().contains("/src/main/java/"))) {
            final long violations = files
                .filter(p -> {
                    try {
                        return Files.lines(p)
                            .filter(line -> !line.stripLeading().startsWith("//")
                                && !line.stripLeading().startsWith("*"))
                            .anyMatch(line -> line.contains("orElse(\"unknown\")")
                                && line.contains("release_date"));
                    } catch (final IOException e) {
                        return false;
                    }
                })
                .count();
            assertThat(
                "No main-source .java file should use .orElse(\"unknown\") for release_date on a code line",
                violations,
                is(0L)
            );
        }
    }
}
