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
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FileScanner}.
 *
 * <p>The display name flattens path separators into dots, which is not
 * reversible — a filename and a version both legitimately contain dots. The
 * scanner therefore has to record the real repo-relative key alongside it,
 * read back off disk, or the tree browser has nothing to navigate to.</p>
 *
 * @since 2.2.8
 */
final class FileScannerTest {

    @Test
    void recordsTheRealStorageKeyAlongsideTheDottedName(@TempDir final Path temp)
        throws IOException {
        final Path dir = temp.resolve("wkda/services/b2x-vehicle-store-service/1.0.0-SNAPSHOT");
        Files.createDirectories(dir);
        Files.write(dir.resolve("b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom"),
            new byte[64]);
        final List<ArtifactRecord> records = new FileScanner().scan(temp, "services")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Should produce exactly 1 record", records.size(), new IsEqual<>(1)
        );
        final ArtifactRecord record = records.get(0);
        MatcherAssert.assertThat(
            "The real repo-relative key must be recorded",
            record.pathPrefix(),
            new IsEqual<>(
                "wkda/services/b2x-vehicle-store-service/1.0.0-SNAPSHOT/b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom"
            )
        );
        MatcherAssert.assertThat(
            "The display name keeps its flattened form",
            record.name(),
            new IsEqual<>(
                "wkda.services.b2x-vehicle-store-service.1.0.0-SNAPSHOT.b2x-vehicle-store-service-1.0.0-20210414.085244-1.pom"
            )
        );
    }

    @Test
    void recordsAKeyForAFileAtTheRepositoryRoot(@TempDir final Path temp) throws IOException {
        Files.write(temp.resolve("top-level.txt"), new byte[8]);
        final ArtifactRecord record = new FileScanner().scan(temp, "files")
            .collect(Collectors.toList()).get(0);
        MatcherAssert.assertThat(
            "A root-level file still records its key",
            record.pathPrefix(), new IsEqual<>("top-level.txt")
        );
    }

    @Test
    void neverLeavesTheKeyUnrecorded(@TempDir final Path temp) throws IOException {
        final Path nested = temp.resolve("a/b/c");
        Files.createDirectories(nested);
        Files.write(nested.resolve("thing-2.0.jar"), new byte[16]);
        Files.write(temp.resolve("loose.bin"), new byte[4]);
        final List<ArtifactRecord> records = new FileScanner().scan(temp, "files")
            .collect(Collectors.toList());
        MatcherAssert.assertThat(
            "Both files are scanned", records.size(), new IsEqual<>(2)
        );
        for (final ArtifactRecord record : records) {
            MatcherAssert.assertThat(
                "Every record carries a key — a null is what broke browse",
                record.pathPrefix(), new IsNot<>(new IsNull<>())
            );
        }
    }
}
