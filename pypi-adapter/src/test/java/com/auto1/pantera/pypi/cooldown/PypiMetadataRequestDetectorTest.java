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
package com.auto1.pantera.pypi.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link PypiMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class PypiMetadataRequestDetectorTest {

    private PypiMetadataRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new PypiMetadataRequestDetector();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/simple/requests/",
        "/simple/requests",
        "/simple/my-package/",
        "/simple/Django/",
        "/simple/numpy/",
        "/simple/my_package/",
        "/simple/my.package/",
        "/pypi-proxy/simple/requests/",
        "/repo/pypi/simple/flask/"
    })
    void detectsMetadataRequests(final String path) {
        assertThat(
            "Path should be detected as metadata: " + path,
            this.detector.isMetadataRequest(path),
            is(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/packages/requests-2.28.0.tar.gz",
        "/packages/ab/cd/requests-2.28.0-py3-none-any.whl",
        "/simple/",
        "/simple",
        "/pypi/requests/1.0.0/json",
        "/other/path",
        ""
    })
    void rejectsNonMetadataRequests(final String path) {
        assertThat(
            "Path should NOT be detected as metadata: " + path,
            this.detector.isMetadataRequest(path),
            is(false)
        );
    }

    @Test
    void rejectsNullPath() {
        assertThat(this.detector.isMetadataRequest(null), is(false));
    }

    @Test
    void extractsPackageNameFromSimplePath() {
        final Optional<String> name = this.detector.extractPackageName("/simple/requests/");
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("requests"));
    }

    @Test
    void extractsPackageNameWithoutTrailingSlash() {
        final Optional<String> name = this.detector.extractPackageName("/simple/flask");
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("flask"));
    }

    @Test
    void extractsPackageNameFromPrefixedPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi-proxy/simple/Django/"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("Django"));
    }

    @Test
    void returnsEmptyForNonMetadataPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/packages/requests-2.28.0.tar.gz"
        );
        assertThat(name.isPresent(), is(false));
    }

    @Test
    void returnsEmptyForNullPath() {
        assertThat(this.detector.extractPackageName(null).isPresent(), is(false));
    }

    @Test
    void returnsEmptyForEmptyPath() {
        assertThat(this.detector.extractPackageName("").isPresent(), is(false));
    }

    @Test
    void returnsCorrectRepoType() {
        assertThat(this.detector.repoType(), equalTo("pypi"));
    }

    @Test
    void extractsHyphenatedPackageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/simple/my-cool-package/"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("my-cool-package"));
    }

    @Test
    void extractsUnderscoredPackageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/simple/my_cool_package/"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("my_cool_package"));
    }

    @Test
    void extractsDottedPackageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/simple/zope.interface/"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("zope.interface"));
    }
}
