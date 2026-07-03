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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link MavenMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class MavenMetadataRequestDetectorTest {

    private MavenMetadataRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new MavenMetadataRequestDetector();
    }

    @Test
    void detectsMetadataXmlPath() {
        assertThat(
            this.detector.isMetadataRequest(
                "/com/example/my-lib/maven-metadata.xml"
            ),
            is(true)
        );
    }

    @Test
    void detectsNestedGroupMetadataPath() {
        assertThat(
            this.detector.isMetadataRequest(
                "/org/apache/commons/commons-lang3/maven-metadata.xml"
            ),
            is(true)
        );
    }

    @Test
    void rejectsArtifactJarPath() {
        assertThat(
            this.detector.isMetadataRequest(
                "/com/example/my-lib/1.0.0/my-lib-1.0.0.jar"
            ),
            is(false)
        );
    }

    @Test
    void rejectsArtifactPomPath() {
        assertThat(
            this.detector.isMetadataRequest(
                "/com/example/my-lib/1.0.0/my-lib-1.0.0.pom"
            ),
            is(false)
        );
    }

    @Test
    void rejectsChecksumPath() {
        assertThat(
            this.detector.isMetadataRequest(
                "/com/example/my-lib/maven-metadata.xml.sha1"
            ),
            is(false)
        );
    }

    @Test
    void rejectsNullPath() {
        assertThat(
            this.detector.isMetadataRequest(null),
            is(false)
        );
    }

    @Test
    void extractsPackageNameFromMetadataPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/com/example/my-lib/maven-metadata.xml"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("com/example/my-lib"));
    }

    @Test
    void extractsNestedGroupPackageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/org/apache/commons/commons-lang3/maven-metadata.xml"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("org/apache/commons/commons-lang3"));
    }

    @Test
    void returnsEmptyForArtifactPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/com/example/my-lib/1.0.0/my-lib-1.0.0.jar"
        );
        assertThat(name.isPresent(), is(false));
    }

    @Test
    void returnsEmptyForNullPath() {
        final Optional<String> name = this.detector.extractPackageName(null);
        assertThat(name.isPresent(), is(false));
    }

    @Test
    void returnsCorrectRepoType() {
        assertThat(this.detector.repoType(), equalTo("maven"));
    }
}
