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
package com.auto1.pantera.http.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link GoLatestMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class GoLatestMetadataRequestDetectorTest {

    private GoLatestMetadataRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new GoLatestMetadataRequestDetector();
    }

    @Test
    void detectsSimpleModuleLatest() {
        assertThat(
            this.detector.isMetadataRequest("/github.com/foo/bar/@latest"),
            is(true)
        );
    }

    @Test
    void detectsDeepModuleLatest() {
        assertThat(
            this.detector.isMetadataRequest(
                "/golang.org/x/tools/gopls/@latest"
            ),
            is(true)
        );
    }

    @Test
    void rejectsVersionListEndpoint() {
        assertThat(
            this.detector.isMetadataRequest("/github.com/foo/bar/@v/list"),
            is(false)
        );
    }

    @Test
    void rejectsInfoFile() {
        assertThat(
            this.detector.isMetadataRequest(
                "/github.com/foo/bar/@v/v0.9.1.info"
            ),
            is(false)
        );
    }

    @Test
    void rejectsLatestWithTrailingSuffix() {
        // Must be the literal end of the path.
        assertThat(
            this.detector.isMetadataRequest("/github.com/foo/bar/@latest/extra"),
            is(false)
        );
    }

    @Test
    void rejectsNullPath() {
        assertThat(this.detector.isMetadataRequest(null), is(false));
    }

    @Test
    void rejectsEmptyPath() {
        assertThat(this.detector.isMetadataRequest(""), is(false));
    }

    @Test
    void extractsSimpleModuleName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/github.com/foo/bar/@latest"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("github.com/foo/bar"));
    }

    @Test
    void extractsDeepModuleName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/golang.org/x/tools/gopls/@latest"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("golang.org/x/tools/gopls"));
    }

    @Test
    void returnsEmptyForVersionListPath() {
        assertThat(
            this.detector.extractPackageName("/github.com/foo/bar/@v/list")
                .isPresent(),
            is(false)
        );
    }

    @Test
    void returnsEmptyForNullPath() {
        assertThat(
            this.detector.extractPackageName(null).isPresent(),
            is(false)
        );
    }

    @Test
    void returnsEmptyForBareLatestSuffix() {
        // "/@latest" with no module path
        assertThat(
            this.detector.extractPackageName("/@latest").isPresent(),
            is(false)
        );
    }

    @Test
    void returnsEmptyForSuffixWithExtraTail() {
        assertThat(
            this.detector.extractPackageName("/foo/@latest/extra").isPresent(),
            is(false)
        );
    }

    @Test
    void returnsCorrectRepoType() {
        assertThat(this.detector.repoType(), equalTo("go"));
    }
}
