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
package com.auto1.pantera.rpm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cactoos.io.InputOf;
import org.cactoos.io.Sha256DigestOf;
import org.cactoos.text.HexOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test case for {@link FileChecksum}.
 *
 * @since 0.8
 */
final class FileChecksumTest {

    @Test
    void generatesValidChecksum(@TempDir final Path tmp) throws Exception {
        final Path target = tmp.resolve("test.bin");
        Files.write(target, "hello".getBytes(StandardCharsets.UTF_8));
        MatcherAssert.assertThat(
            new FileChecksum(target, Digest.SHA256).hex(),
            new IsEqual<>(new HexOf(new Sha256DigestOf(new InputOf(target))).asString())
        );
    }
}
