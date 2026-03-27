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
package com.auto1.pantera.debian.metadata;

import java.util.NoSuchElementException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ControlField}.
 * @since 0.1
 */
class ControlFieldTest {

    @Test
    void extractsArchitectureField() {
        MatcherAssert.assertThat(
            new ControlField.Architecture().value(
                String.join(
                    "\n",
                    "Package: aglfn",
                    "Version: 1.7-3",
                    "Architecture: all",
                    "Maintainer: Debian Fonts Task Force <pkg-fonts-devel@lists.alioth.debian.org>",
                    "Installed-Size: 138",
                    "Section: fonts"
                )
            ),
            Matchers.contains("all")
        );
    }

    @Test
    void extractsArchitecturesField() {
        MatcherAssert.assertThat(
            new ControlField.Architecture().value(
                String.join(
                    "\n",
                    "Package: abc",
                    "Version: 0.1",
                    "Architecture: amd64 amd32"
                )
            ),
            Matchers.contains("amd64", "amd32")
        );
    }

    @Test
    void extractsPackageField() {
        MatcherAssert.assertThat(
            new ControlField.Package().value(
                String.join(
                    "\n",
                    "Package: xyz",
                    "Version: 0.3",
                    "Architecture: amd64 intell"
                )
            ),
            Matchers.contains("xyz")
        );
    }

    @Test
    void extractsVersionField() {
        MatcherAssert.assertThat(
            new ControlField.Version().value(
                String.join(
                    "\n",
                    "Package: 123",
                    "Version: 0.987",
                    "Architecture: amd32"
                )
            ),
            Matchers.contains("0.987")
        );
    }

    @Test
    void throwsExceptionWhenElementNotFound() {
        Assertions.assertThrows(
            NoSuchElementException.class,
            () -> new ControlField.Architecture().value("invalid control")
        );
    }

}
