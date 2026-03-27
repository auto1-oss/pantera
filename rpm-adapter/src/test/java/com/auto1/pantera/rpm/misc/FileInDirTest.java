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
package com.auto1.pantera.rpm.misc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link FileInDir}.
 * @since 0.9
 */
final class FileInDirTest {

    @Test
    void findsFile(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("some_file.txt");
        Files.write(file, "abs123".getBytes());
        MatcherAssert.assertThat(
            new FileInDir(tmp).find("_file.t"),
            new IsEqual<>(file)
        );
    }

    @Test
    void doesNotFindFile(@TempDir final Path tmp) throws IOException {
        Files.write(tmp.resolve("a_file.txt"), "abc123".getBytes());
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new FileInDir(tmp).find("_notfile.t")
        );
    }

    @Test
    void doesNotUseRegexToFind(@TempDir final Path tmp) throws IOException {
        Files.write(tmp.resolve("fileXtxt"), "ab123".getBytes());
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new FileInDir(tmp).find("file.txt")
        );
    }
}
