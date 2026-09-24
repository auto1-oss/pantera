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
package com.auto1.pantera.api.v1.admin;

import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link MavenCoordinate}: {@code groupId:artifactId} from the index path.
 *
 * @since 2.2.9
 */
final class MavenCoordinateTest {

    @Test
    void derivesFromAVersionPath() {
        MatcherAssert.assertThat(
            new MavenCoordinate(
                "com.fasterxml.jackson.core.jackson-databind",
                "com/fasterxml/jackson/core/jackson-databind/2.17.0"
            ).coordinate(),
            new IsEqual<>(Optional.of("com.fasterxml.jackson.core:jackson-databind"))
        );
    }

    @Test
    void derivesFromAnArtifactPathWithSlashes() {
        MatcherAssert.assertThat(
            new MavenCoordinate("org.example.foo.bar", "/org/example/foo/bar/").coordinate(),
            new IsEqual<>(Optional.of("org.example.foo:bar"))
        );
    }

    @Test
    void derivesFromAFilePath() {
        MatcherAssert.assertThat(
            new MavenCoordinate(
                "org.example.bar", "org/example/bar/1.0/bar-1.0.jar"
            ).coordinate(),
            new IsEqual<>(Optional.of("org.example:bar"))
        );
    }

    @Test
    void refusesAPathOfAnotherArtifact() {
        MatcherAssert.assertThat(
            new MavenCoordinate("org.example.bar", "org/other/baz/1.0").coordinate(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void keepsAnAlreadyColonSeparatedName() {
        MatcherAssert.assertThat(
            new MavenCoordinate("org.example:bar", null).coordinate(),
            new IsEqual<>(Optional.of("org.example:bar"))
        );
    }

    @Test
    void readsASlashSeparatedName() {
        MatcherAssert.assertThat(
            new MavenCoordinate("org/example/bar", null).coordinate(),
            new IsEqual<>(Optional.of("org.example:bar"))
        );
    }

    @Test
    void cannotSplitADottedNameWithoutAPath() {
        MatcherAssert.assertThat(
            new MavenCoordinate("org.example.bar", null).coordinate(),
            new IsEqual<>(Optional.empty())
        );
    }
}
