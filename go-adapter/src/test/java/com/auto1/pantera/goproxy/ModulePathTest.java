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
package com.auto1.pantera.goproxy;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link ModulePath}.
 * @since 2.2.9
 */
final class ModulePathTest {

    @ParameterizedTest
    @CsvSource({
        "github.com/!burnt!sushi/toml,github.com/BurntSushi/toml",
        "github.com/!azure/azure-sdk-for-go,github.com/Azure/azure-sdk-for-go",
        "golang.org/x/text,golang.org/x/text",
        "example.com/trailing!,example.com/trailing!"
    })
    void decodesUpperCaseEscapes(final String escaped, final String expected) {
        MatcherAssert.assertThat(new ModulePath(escaped).decoded(), new IsEqual<>(expected));
    }
}
