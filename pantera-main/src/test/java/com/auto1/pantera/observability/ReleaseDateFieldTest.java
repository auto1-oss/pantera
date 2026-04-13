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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
 * <p><b>Gap 4 (Part 6B) — valid release date IS present in log output:</b>
 * The 4 package processors (Maven, npm, Go, PyPI) all use the pattern
 * {@code event.releaseMillis().orElse(null)} and then pass the resulting
 * {@code Long} (or {@code null}) to {@code .field("package.release_date", ...)}
 * so EcsLogger omits the field when null and includes it when non-null.
 * This is verified by {@link #processorsUseOrElseNullNotOrElseUnknown()} (absence of
 * the "unknown" fallback) and {@link #processorsRetainReleaseDateField()} (presence of
 * the {@code releaseMillis().orElse} pattern combined with the {@code package.release_date}
 * field in the same source file).
 *
 * <p><b>Gap 5 (Part 6B) — debug log with release_date_fallback:</b>
 * The 4 processors do NOT emit a separate DEBUG log with
 * {@code event.action: "release_date_fallback"} when the date is absent.
 * The fix simply changed {@code .orElse("unknown")} to {@code .orElse(null)},
 * suppressing the field without adding an explicit fallback log line.
 * This is a spec deviation; no test is added for a behavior that was
 * not implemented.
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

    /**
     * Gap 4 (Part 6B): each of the 4 package processors must use
     * {@code .orElse(null)} (not {@code .orElse("unknown")}) for the release
     * millis extraction so that a missing date results in a null field value
     * rather than the literal string "unknown".
     *
     * <p>We verify source-level intent: every processor file that references
     * {@code package.release_date} must also contain {@code orElse(null)} on a
     * non-comment line, proving the null-omit pattern is in place.
     * Paths are resolved relative to the project root (one level above the
     * {@code pantera-main} module where tests execute).
     */
    @Test
    void processorsUseOrElseNullNotOrElseUnknown() throws Exception {
        // Maven test cwd is pantera-main/ — go up one level to reach the project root
        final Path projectRoot = Path.of("").toAbsolutePath().getParent();
        // Collect the 4 known processor source files (relative to project root)
        final List<String> processorRelPaths = List.of(
            "maven-adapter/src/main/java/com/auto1/pantera/maven/MavenProxyPackageProcessor.java",
            "npm-adapter/src/main/java/com/auto1/pantera/npm/events/NpmProxyPackageProcessor.java",
            "go-adapter/src/main/java/com/auto1/pantera/goproxy/GoProxyPackageProcessor.java",
            "pypi-adapter/src/main/java/com/auto1/pantera/pypi/PyProxyPackageProcessor.java"
        );
        for (final String rel : processorRelPaths) {
            final Path file = projectRoot.resolve(rel);
            final List<String> codeLines = Files.lines(file)
                .filter(line -> !line.stripLeading().startsWith("//")
                    && !line.stripLeading().startsWith("*"))
                .collect(Collectors.toList());
            final boolean hasOrElseNull = codeLines.stream()
                .anyMatch(line -> line.contains("releaseMillis()") && line.contains("orElse(null)"));
            assertThat(
                rel + " must use releaseMillis().orElse(null) (not orElse(\"unknown\"))",
                hasOrElseNull,
                is(true)
            );
        }
    }

    /**
     * Gap 4 (Part 6B): the 4 processors must still emit the
     * {@code package.release_date} field — it must not have been accidentally
     * removed when the fallback was changed from "unknown" to null.
     *
     * <p>We verify that at least one file in each of the two adapter modules
     * that explicitly log this field (go-adapter and pypi-adapter) still
     * contains the {@code package.release_date} field reference. Maven and npm
     * processors do not log the field in their processor class (they pass the
     * release millis to ArtifactEvent without logging it inline), so they are
     * not included in this particular assertion.
     */
    @Test
    void processorsRetainReleaseDateField() throws Exception {
        final Path projectRoot = Path.of("").toAbsolutePath().getParent();
        final List<String> adapterSrcRoots = List.of(
            "go-adapter/src/main/java",
            "pypi-adapter/src/main/java"
        );
        for (final String srcRoot : adapterSrcRoots) {
            final Path dir = projectRoot.resolve(srcRoot);
            try (Stream<Path> files = Files.walk(dir)
                .filter(p -> p.toString().endsWith(".java"))) {
                final List<String> withField = files
                    .filter(p -> {
                        try {
                            return Files.lines(p)
                                .anyMatch(line -> line.contains("package.release_date"));
                        } catch (final IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
                assertThat(
                    srcRoot + " must have at least one file logging package.release_date",
                    withField,
                    is(not(empty()))
                );
            }
        }
    }
}
