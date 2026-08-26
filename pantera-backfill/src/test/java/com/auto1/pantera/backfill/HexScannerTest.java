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
 * Tests for {@link HexScanner}.
 *
 * @since 1.20.13
 */
final class HexScannerTest {

    @Test
    void parsesSimpleTarballFilename(@TempDir final Path temp) throws IOException {
        final Path tarballs = temp.resolve("tarballs");
        Files.createDirectories(tarballs);
        Files.write(tarballs.resolve("phoenix-1.6.0.tar"), new byte[100]);
        final HexScanner scanner = new HexScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "hex-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record", records.size(), new IsEqual<>(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "Name should be phoenix", record.name(), new IsEqual<>("phoenix")
        );
        MatcherAssert.assertThat(
            "Version should be 1.6.0", record.version(), new IsEqual<>("1.6.0")
        );
        MatcherAssert.assertThat(
            "Repo type should be hexpm", record.repoType(), new IsEqual<>("hexpm")
        );
        MatcherAssert.assertThat(
            "Owner should be system", record.owner(), new IsEqual<>("system")
        );
        MatcherAssert.assertThat("Size should be 100", record.size(), new IsEqual<>(100L));
    }

    @Test
    void populatesPathPrefixWithRealStorageKey(@TempDir final Path temp) throws IOException {
        final Path tarballs = temp.resolve("tarballs");
        Files.createDirectories(tarballs);
        Files.write(tarballs.resolve("decimal-2.0.0.tar"), new byte[50]);
        final HexScanner scanner = new HexScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "hex-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            records.get(0).pathPrefix(),
            new IsEqual<>("tarballs/decimal-2.0.0.tar")
        );
    }

    @Test
    void handlesMultipleTarballs(@TempDir final Path temp) throws IOException {
        final Path tarballs = temp.resolve("tarballs");
        Files.createDirectories(tarballs);
        Files.write(tarballs.resolve("phoenix-1.6.0.tar"), new byte[100]);
        Files.write(tarballs.resolve("ecto-3.9.1.tar"), new byte[80]);
        final HexScanner scanner = new HexScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "hex-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(records.size(), new IsEqual<>(2));
    }

    @Test
    void skipsNonTarballFiles(@TempDir final Path temp) throws IOException {
        final Path tarballs = temp.resolve("tarballs");
        Files.createDirectories(tarballs);
        Files.writeString(tarballs.resolve("readme.txt"), "hello");
        final HexScanner scanner = new HexScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "hex-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(records, new IsEmptyCollection<>());
    }

    @Test
    void returnsEmptyWhenTarballsDirMissing(@TempDir final Path temp) throws IOException {
        final HexScanner scanner = new HexScanner();
        final List<ArtifactRecord> records = scanner.scan(temp, "hex-repo")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(records, new IsEmptyCollection<>());
    }
}
