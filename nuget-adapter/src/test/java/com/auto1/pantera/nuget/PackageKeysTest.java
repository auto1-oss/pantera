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
package com.auto1.pantera.nuget;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PackageKeys}.
 *
 * @since 0.1
 */
public class PackageKeysTest {

    @Test
    void shouldGenerateRootKey() {
        MatcherAssert.assertThat(
            new PackageKeys("Pantera.Module").rootKey().string(),
            new IsEqual<>("pantera.module")
        );
    }

    @Test
    void shouldGenerateVersionsKey() {
        MatcherAssert.assertThat(
            new PackageKeys("Newtonsoft.Json").versionsKey().string(),
            Matchers.is("newtonsoft.json/index.json")
        );
    }
}
