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
package com.auto1.pantera.composer.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ComposerMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class ComposerMetadataRequestDetectorTest {

    private ComposerMetadataRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new ComposerMetadataRequestDetector();
    }

    @Test
    void detectsPackagesEndpoint() {
        assertThat(
            this.detector.isMetadataRequest("/packages/vendor/package.json"),
            is(true)
        );
    }

    @Test
    void detectsP2Endpoint() {
        assertThat(
            this.detector.isMetadataRequest("/p2/vendor/package.json"),
            is(true)
        );
    }

    @Test
    void detectsPackagesEndpointWithHyphens() {
        assertThat(
            this.detector.isMetadataRequest("/packages/my-vendor/my-package.json"),
            is(true)
        );
    }

    @Test
    void detectsP2EndpointWithHyphens() {
        assertThat(
            this.detector.isMetadataRequest("/p2/symfony/http-kernel.json"),
            is(true)
        );
    }

    @Test
    void rejectsArchiveDownload() {
        assertThat(
            this.detector.isMetadataRequest("/dist/vendor/package/1.0.0/package-1.0.0.zip"),
            is(false)
        );
    }

    @Test
    void rejectsRootPath() {
        assertThat(
            this.detector.isMetadataRequest("/"),
            is(false)
        );
    }

    @Test
    void rejectsPlainPackagesPath() {
        assertThat(
            this.detector.isMetadataRequest("/packages.json"),
            is(false)
        );
    }

    @Test
    void rejectsNonJsonExtension() {
        assertThat(
            this.detector.isMetadataRequest("/packages/vendor/package.xml"),
            is(false)
        );
    }

    @Test
    void rejectsMissingVendor() {
        assertThat(
            this.detector.isMetadataRequest("/packages/package.json"),
            is(false)
        );
    }

    @Test
    void rejectsExtraPathSegments() {
        assertThat(
            this.detector.isMetadataRequest("/packages/vendor/package/extra.json"),
            is(false)
        );
    }

    @Test
    void extractsPackageNameFromPackagesEndpoint() {
        final Optional<String> name = this.detector.extractPackageName(
            "/packages/vendor/package.json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("vendor/package"));
    }

    @Test
    void extractsPackageNameFromP2Endpoint() {
        final Optional<String> name = this.detector.extractPackageName(
            "/p2/monolog/monolog.json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("monolog/monolog"));
    }

    @Test
    void extractsPackageNameWithHyphens() {
        final Optional<String> name = this.detector.extractPackageName(
            "/p2/symfony/http-foundation.json"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("symfony/http-foundation"));
    }

    @Test
    void returnsEmptyForNonMetadataPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/dist/vendor/package.zip"
        );
        assertThat(name.isPresent(), is(false));
    }

    @Test
    void returnsComposerRepoType() {
        assertThat(this.detector.repoType(), equalTo("composer"));
    }

    @Test
    void rejectsP2WithoutJson() {
        assertThat(
            this.detector.isMetadataRequest("/p2/vendor/package"),
            is(false)
        );
    }

    @Test
    void rejectsEmptyPath() {
        assertThat(
            this.detector.isMetadataRequest(""),
            is(false)
        );
    }
}
