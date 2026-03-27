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
package com.auto1.pantera.nuget.metadata;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link PackageId}.
 * @since 0.6
 */
class PackageIdTest {

    @Test
    void shouldPreserveOriginal() {
        final String id = "Microsoft.Extensions.Logging";
        MatcherAssert.assertThat(
            new PackageId(id).raw(),
            Matchers.is(id)
        );
    }

    @Test
    void shouldGenerateLower() {
        MatcherAssert.assertThat(
            new PackageId("My.Lib").normalized(),
            Matchers.is("my.lib")
        );
    }

}
