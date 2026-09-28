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
package com.auto1.pantera.maven.cooldown;

import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MavenMetadataCoordinates}.
 *
 * @since 2.2.9
 */
final class MavenMetadataCoordinatesTest {

    @ParameterizedTest
    @CsvSource({
        "/com/google/guava/guava/maven-metadata.xml,com.google.guava.guava,",
        "com/google/guava/guava/maven-metadata.xml,com.google.guava.guava,",
        "/com/google/guava/guava/maven-metadata.xml.sha1,com.google.guava.guava,",
        "/com/google/guava/guava/maven-metadata.xml.md5,com.google.guava.guava,",
        "/com/example/my-lib/1.0-SNAPSHOT/maven-metadata.xml,com.example.my-lib,"
            + "snapshot-1.0-SNAPSHOT",
        "/com/example/my-lib/1.0-SNAPSHOT/maven-metadata.xml.sha1,com.example.my-lib,"
            + "snapshot-1.0-SNAPSHOT",
        "/org/apache/maven/plugins/maven-metadata.xml,org.apache.maven.plugins,"
    })
    void mapsMetadataPathsToCooldownCoordinates(
        final String path, final String pkg, final String variant
    ) {
        final MavenMetadataCoordinates coords = new MavenMetadataCoordinates();
        MatcherAssert.assertThat(
            "package of " + path,
            coords.packageName(path), new IsEqual<>(Optional.of(pkg))
        );
        MatcherAssert.assertThat(
            "variant of " + path,
            coords.envelopeVariant(path), new IsEqual<>(Optional.ofNullable(variant))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/maven-metadata.xml",
        "maven-metadata.xml",
        "/com/example/lib/1.0/lib-1.0.jar",
        "/com/example/lib/maven-metadata.xml.asc"
    })
    void rejectsNonMetadataPaths(final String path) {
        MatcherAssert.assertThat(
            new MavenMetadataCoordinates().packageName(path),
            new IsEqual<>(Optional.empty())
        );
    }
}
