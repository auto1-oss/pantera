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
 * Tests for {@link PypiJsonMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class PypiJsonMetadataRequestDetectorTest {

    private PypiJsonMetadataRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new PypiJsonMetadataRequestDetector();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/pypi/requests/json",
        "/pypi/requests/json/",
        "/pypi/Django/json",
        "/pypi/numpy/json",
        "/pypi/my-package/json",
        "/pypi/my_package/json",
        "/pypi/zope.interface/json",
        "/pypi-proxy/pypi/requests/json",
        "/repo/pypi/flask/json"
    })
    void detectsJsonMetadataRequests(final String path) {
        assertThat(
            "Path should be detected: " + path,
            this.detector.isMetadataRequest(path),
            is(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Simple Index — handled by a different detector.
        "/simple/requests/",
        "/simple/requests",
        "/pypi-proxy/simple/flask/",
        // Incomplete paths.
        "/pypi/requests",
        "/pypi/requests/",
        "/pypi/json",
        "/pypi/",
        // Version-specific endpoint — intentionally not covered by this detector.
        "/pypi/requests/2.32.0/json",
        "/pypi/requests/2.32.0/json/",
        "/pypi-proxy/pypi/numpy/1.0.0/json",
        // Artifact paths.
        "/packages/requests-2.28.0.tar.gz",
        "/packages/ab/cd/requests-2.28.0-py3-none-any.whl",
        // Unrelated paths.
        "/other/path",
        ""
    })
    void rejectsNonJsonMetadataRequests(final String path) {
        assertThat(
            "Path should NOT be detected: " + path,
            this.detector.isMetadataRequest(path),
            is(false)
        );
    }

    @Test
    void rejectsNullPath() {
        assertThat(this.detector.isMetadataRequest(null), is(false));
    }

    @Test
    void extractsPackageNameFromJsonPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi/requests/json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("requests"));
    }

    @Test
    void extractsPackageNameWithTrailingSlash() {
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi/flask/json/"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("flask"));
    }

    @Test
    void extractsPackageNameCaseSensitively() {
        // PEP 503 normalisation happens at the cooldown-lookup layer.
        // The detector returns the raw URL path segment verbatim.
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi/Django/json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("Django"));
    }

    @Test
    void extractsPackageNameFromPrefixedPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi-proxy/pypi/numpy/json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("numpy"));
    }

    @Test
    void extractsHyphenatedPackageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi/my-cool-package/json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("my-cool-package"));
    }

    @Test
    void extractsDottedPackageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi/zope.interface/json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("zope.interface"));
    }

    @Test
    void returnsEmptyForVersionSpecificPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/pypi/requests/2.32.0/json"
        );
        assertThat(name.isPresent(), is(false));
    }

    @Test
    void returnsEmptyForSimpleIndexPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/simple/requests/"
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
}
