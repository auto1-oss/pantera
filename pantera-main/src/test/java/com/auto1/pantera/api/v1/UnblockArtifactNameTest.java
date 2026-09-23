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
package com.auto1.pantera.api.v1;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Test for {@link UnblockArtifactName}.
 * @since 2.2.9
 */
final class UnblockArtifactNameTest {

    @ParameterizedTest
    @CsvSource({
        "maven-proxy,software.amazon.awssdk:annotations,software.amazon.awssdk.annotations",
        "maven-proxy,software.amazon.awssdk.annotations,software.amazon.awssdk.annotations",
        "gradle-proxy,com.acme:lib,com.acme.lib",
        "maven,com/acme/lib,com.acme.lib",
        "npm-proxy,@scope/pkg,@scope/pkg",
        "docker-proxy,library/alpine,library/alpine",
        "pypi-proxy,requests,requests"
    })
    void normalisesToTheStoredBlockKey(
        final String type, final String requested, final String expected
    ) {
        MatcherAssert.assertThat(
            new UnblockArtifactName(type, requested).value(), new IsEqual<>(expected)
        );
    }
}
