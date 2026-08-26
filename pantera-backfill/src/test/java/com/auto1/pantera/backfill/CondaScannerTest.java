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
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.MatcherAssert;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CondaScanner}.
 *
 * @since 1.20.13
 */
final class CondaScannerTest {

    /**
     * Minimal repodata.json exercising both the {@code packages} and
     * {@code packages.conda} sections, mirroring the structure
     * {@code AstoMergedJson} produces at upload time.
     */
    private static final String REPODATA = "{"
        + "\"packages\":{"
        + "\"numpy-1.21.0-py39_0.tar.bz2\":{"
        + "\"name\":\"numpy\",\"version\":\"1.21.0\",\"arch\":\"x86_64\",\"size\":12345"
        + "}},"
        + "\"packages.conda\":{"
        + "\"scipy-1.7.0-py39h1.conda\":{"
        + "\"name\":\"scipy\",\"version\":\"1.7.0\",\"arch\":\"x86_64\",\"size\":67890"
        + "}}"
        + "}";

    @Test
    void parsesPackagesSection(@TempDir final Path temp) throws IOException {
        final Path archDir = temp.resolve("linux-64");
        Files.createDirectories(archDir);
        Files.writeString(archDir.resolve("repodata.json"), CondaScannerTest.REPODATA);
        Files.write(archDir.resolve("numpy-1.21.0-py39_0.tar.bz2"), new byte[1]);
        Files.write(archDir.resolve("scipy-1.7.0-py39h1.conda"), new byte[1]);
        final CondaScanner scanner = new CondaScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "conda-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce one record per repodata entry", records.size(), new IsEqual<>(2)
        );
    }

    @Test
    void reconstructsNameArchCompositeFromRepodataMetadata(@TempDir final Path temp)
        throws IOException {
        final Path archDir = temp.resolve("linux-64");
        Files.createDirectories(archDir);
        Files.writeString(archDir.resolve("repodata.json"), CondaScannerTest.REPODATA);
        final CondaScanner scanner = new CondaScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "conda-repo")
            .collect(Collectors.toList());
        final ArtifactRecord numpy = records.stream()
            .filter(r -> r.name().startsWith("numpy"))
            .findFirst()
            .orElseThrow();
        MatcherAssert.assertThat(
            "Name should be the exact name_arch composite UpdateSlice would have written",
            numpy.name(),
            new IsEqual<>("numpy_x86_64")
        );
        MatcherAssert.assertThat(
            "Version should come from repodata metadata", numpy.version(), new IsEqual<>("1.21.0")
        );
        MatcherAssert.assertThat("Size should come from repodata metadata", numpy.size(), new IsEqual<>(12345L));
    }

    @Test
    void populatesPathPrefixWithRealArchAndFilename(@TempDir final Path temp) throws IOException {
        final Path archDir = temp.resolve("linux-64");
        Files.createDirectories(archDir);
        Files.writeString(archDir.resolve("repodata.json"), CondaScannerTest.REPODATA);
        final CondaScanner scanner = new CondaScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "conda-repo")
            .collect(Collectors.toList());
        final ArtifactRecord numpy = records.stream()
            .filter(r -> r.name().startsWith("numpy"))
            .findFirst()
            .orElseThrow();
        MatcherAssert.assertThat(
            numpy.pathPrefix(),
            new IsEqual<>("linux-64/numpy-1.21.0-py39_0.tar.bz2")
        );
    }

    @Test
    void skipsArchDirectoriesWithoutRepodata(@TempDir final Path temp) throws IOException {
        final Path archDir = temp.resolve("linux-64");
        Files.createDirectories(archDir);
        Files.write(archDir.resolve("numpy-1.21.0-py39_0.tar.bz2"), new byte[1]);
        final CondaScanner scanner = new CondaScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "conda-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(records, new IsEmptyCollection<>());
    }

    @Test
    void returnsEmptyForRepoWithNoArchDirectories(@TempDir final Path temp) throws IOException {
        final CondaScanner scanner = new CondaScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "conda-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(records, new IsEmptyCollection<>());
    }

    @Test
    void toleratesMalformedRepodataWithoutAborting(@TempDir final Path temp) throws IOException {
        final Path good = temp.resolve("linux-64");
        Files.createDirectories(good);
        Files.writeString(good.resolve("repodata.json"), CondaScannerTest.REPODATA);
        final Path bad = temp.resolve("noarch");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("repodata.json"), "{not valid json");
        final CondaScanner scanner = new CondaScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "conda-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Malformed repodata.json in one arch dir should not block the other",
            records.size(),
            new IsEqual<>(2)
        );
    }
}
