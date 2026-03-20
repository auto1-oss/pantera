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
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.maven.MetadataXml;
import org.cactoos.list.ListOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link DeployMetadata}.
 * @since 0.8
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class DeployMetadataTest {

    @Test
    void readsReleaseFieldValue() {
        final String release = "1.098";
        MatcherAssert.assertThat(
            new DeployMetadata(
                new MetadataXml("com.auto1.pantera", "abc").get(
                    new MetadataXml.VersionTags(
                        "12", release, new ListOf<>(release, "0.3", "12", "0.1")
                    )
                )
            ).release(),
            new IsEqual<>(release)
        );
    }

    @Test
    void throwsExceptionIfMetadataInvalid() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new DeployMetadata(
                new MetadataXml("com.auto1.pantera", "abc").get(
                    new MetadataXml.VersionTags("0.3", "12", "0.1")
                )
            ).release()
        );
    }

    @Test
    void readsSnapshotVersions() {
        final String one = "0.1-SNAPSHOT";
        final String two = "0.2-SNAPSHOT";
        final String three = "3.1-SNAPSHOT";
        MatcherAssert.assertThat(
            new DeployMetadata(
                new MetadataXml("com.example", "logger").get(
                    new MetadataXml.VersionTags(one, "0.7", "13", two, "0.145", three)
                )
            ).snapshots(),
            Matchers.containsInAnyOrder(one, three, two)
        );
    }

}
