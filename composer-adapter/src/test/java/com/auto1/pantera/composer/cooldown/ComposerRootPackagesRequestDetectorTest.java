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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ComposerRootPackagesRequestDetector}.
 *
 * @since 2.2.0
 */
final class ComposerRootPackagesRequestDetectorTest {

    private ComposerRootPackagesRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new ComposerRootPackagesRequestDetector();
    }

    @Test
    void detectsPackagesJson() {
        assertThat(this.detector.isMetadataRequest("/packages.json"), is(true));
    }

    @Test
    void detectsRepoJson() {
        assertThat(this.detector.isMetadataRequest("/repo.json"), is(true));
    }

    @Test
    void rejectsP2PerPackage() {
        // /p2/<vendor>/<pkg>.json must route to the per-package handler,
        // not the root handler.
        assertThat(
            this.detector.isMetadataRequest("/p2/vendor/package.json"),
            is(false)
        );
    }

    @Test
    void rejectsPackagesPerPackage() {
        assertThat(
            this.detector.isMetadataRequest("/packages/vendor/package.json"),
            is(false)
        );
    }

    @Test
    void rejectsRoot() {
        assertThat(this.detector.isMetadataRequest("/"), is(false));
    }

    @Test
    void rejectsEmpty() {
        assertThat(this.detector.isMetadataRequest(""), is(false));
    }

    @Test
    void rejectsNull() {
        assertThat(this.detector.isMetadataRequest(null), is(false));
    }

    @Test
    void rejectsPackagesWithoutJson() {
        assertThat(this.detector.isMetadataRequest("/packages"), is(false));
    }

    @Test
    void rejectsPackagesWithPrefix() {
        // Exact match only — anything before /packages.json means it's
        // not the root of this repo's URL space.
        assertThat(
            this.detector.isMetadataRequest("/somerepo/packages.json"),
            is(false)
        );
    }

    @Test
    void rejectsSimilarJsonEndpoint() {
        assertThat(
            this.detector.isMetadataRequest("/p2/packages.json"),
            is(false)
        );
    }

    @Test
    void extractPackageNameAlwaysEmpty() {
        assertThat(
            this.detector.extractPackageName("/packages.json").isPresent(),
            is(false)
        );
        assertThat(
            this.detector.extractPackageName("/repo.json").isPresent(),
            is(false)
        );
    }

    @Test
    void repoTypeIsComposer() {
        assertThat(this.detector.repoType(), equalTo("composer"));
    }
}
