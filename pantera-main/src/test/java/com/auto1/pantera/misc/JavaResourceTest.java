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
package com.auto1.pantera.misc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link JavaResource}.
 * @since 0.22
 */
class JavaResourceTest {

    @Test
    void copiesResource(final @TempDir Path temp) throws IOException {
        final String file = "log4j.properties";
        final Path res = temp.resolve(file);
        new JavaResource(file).copy(res);
        MatcherAssert.assertThat(
            Files.exists(res),
            new IsEqual<>(true)
        );
    }

}
