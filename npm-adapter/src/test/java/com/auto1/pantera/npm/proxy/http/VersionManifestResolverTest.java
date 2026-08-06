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
package com.auto1.pantera.npm.proxy.http;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

final class VersionManifestResolverTest {

    @Test
    void parsesUnscopedPackageAndVersion() {
        final VersionManifestResolver.PackageRef ref =
            VersionManifestResolver.parse("pnpm/11.5.1").orElseThrow();
        MatcherAssert.assertThat("package", ref.pkg(), new IsEqual<>("pnpm"));
        MatcherAssert.assertThat("reference", ref.ref(), new IsEqual<>("11.5.1"));
    }

    @Test
    void parsesScopedPackageAndVersion() {
        final VersionManifestResolver.PackageRef ref =
            VersionManifestResolver.parse("@types/node/22.0.0").orElseThrow();
        MatcherAssert.assertThat("package", ref.pkg(), new IsEqual<>("@types/node"));
        MatcherAssert.assertThat("reference", ref.ref(), new IsEqual<>("22.0.0"));
    }

    @Test
    void treatsScopedPackageWithoutVersionAsAPackument() {
        // THE ambiguity: /@types/node is a package name, not (pkg=@types, ref=node).
        MatcherAssert.assertThat(
            VersionManifestResolver.parse("@types/node").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void treatsBarePackageAsAPackument() {
        MatcherAssert.assertThat(
            VersionManifestResolver.parse("pnpm").isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void rejectsDashAndEmptyReferences() {
        MatcherAssert.assertThat(
            "dash", VersionManifestResolver.parse("pnpm/-").isPresent(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "empty", VersionManifestResolver.parse("pnpm/").isPresent(), new IsEqual<>(false)
        );
    }
}
