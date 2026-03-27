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

import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test case for {@link Cli}.
 *
 * @since 0.6
 */
final class CliTest {
    @Test
    void testWrongArgumentCount() {
        final IllegalArgumentException err = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> Cli.main(new String[]{})
        );
        Assertions.assertEquals(
            err.getMessage(),
            "Expected repository path but got: []"
        );
    }

    @Test
    void testRunWithCorrectArgument(@TempDir final Path temp) {
        Cli.main(new String[]{"-n=sha256", "-d=sha1", "-f=true", temp.toString()});
    }

    @Test
    void testParseWithWrongArgument(@TempDir final Path temp) {
        final IllegalArgumentException err = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> Cli.main(new String[] {"-naming-policy=sha256", "-digest=sha1", "-lists=true", temp.toString()})
        );
        Assertions.assertTrue(err.getMessage().contains("Can't parse arguments"));
    }
}
