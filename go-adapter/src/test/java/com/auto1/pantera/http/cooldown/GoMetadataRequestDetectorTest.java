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
 * Tests for {@link GoMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class GoMetadataRequestDetectorTest {

    private GoMetadataRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new GoMetadataRequestDetector();
    }

    @Test
    void detectsSimpleModuleVersionList() {
        assertThat(
            this.detector.isMetadataRequest("/github.com/pkg/errors/@v/list"),
            is(true)
        );
    }

    @Test
    void detectsDeepModuleVersionList() {
        assertThat(
            this.detector.isMetadataRequest(
                "/golang.org/x/tools/gopls/@v/list"
            ),
            is(true)
        );
    }

    @Test
    void rejectsModFile() {
        assertThat(
            this.detector.isMetadataRequest(
                "/github.com/pkg/errors/@v/v0.9.1.mod"
            ),
            is(false)
        );
    }

    @Test
    void rejectsInfoFile() {
        assertThat(
            this.detector.isMetadataRequest(
                "/github.com/pkg/errors/@v/v0.9.1.info"
            ),
            is(false)
        );
    }

    @Test
    void rejectsZipFile() {
        assertThat(
            this.detector.isMetadataRequest(
                "/github.com/pkg/errors/@v/v0.9.1.zip"
            ),
            is(false)
        );
    }

    @Test
    void rejectsLatestEndpoint() {
        assertThat(
            this.detector.isMetadataRequest(
                "/github.com/pkg/errors/@latest"
            ),
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
            "/github.com/pkg/errors/@v/list"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("github.com/pkg/errors"));
    }

    @Test
    void extractsDeepModuleName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/golang.org/x/tools/gopls/@v/list"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("golang.org/x/tools/gopls"));
    }

    @Test
    void returnsEmptyForNonMetadataPath() {
        assertThat(
            this.detector.extractPackageName(
                "/github.com/pkg/errors/@v/v0.9.1.mod"
            ).isPresent(),
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
    void returnsEmptyForBareListSuffix() {
        // "/@v/list" with no module path (edge case)
        assertThat(
            this.detector.extractPackageName("/@v/list").isPresent(),
            is(false)
        );
    }

    @Test
    void returnsCorrectRepoType() {
        assertThat(this.detector.repoType(), equalTo("go"));
    }
}
