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
package com.auto1.pantera.rpm.meta;

import com.auto1.pantera.asto.test.TestResource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PackagesCount}.
 *
 * @since 0.11
 */
class PackagesCountTest {

    @Test
    void shouldReadValue() throws Exception {
        MatcherAssert.assertThat(
            new PackagesCount(
                new TestResource("repodata/primary.xml.example").asPath()
            ).value(),
            new IsEqual<>(2)
        );
    }

    @Test
    void shouldFailIfAttributeIsMissing(final @TempDir Path dir) throws Exception {
        final PackagesCount count = new PackagesCount(
            Files.write(dir.resolve("empty.xml"), "".getBytes())
        );
        Assertions.assertThrows(IllegalArgumentException.class, count::value);
    }

    @Test
    void shouldFailIfAttributeIsTooFarFromStart(final @TempDir Path dir) throws Exception {
        final PackagesCount count = new PackagesCount(
            Files.write(
                dir.resolve("big.xml"),
                "\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n<metadata packages=\"123\"/>".getBytes()
            )
        );
        Assertions.assertThrows(IllegalArgumentException.class, count::value);
    }
}
