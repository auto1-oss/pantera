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
package com.auto1.pantera.docker.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link DockerMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class DockerMetadataRequestDetectorTest {

    private DockerMetadataRequestDetector detector;

    @BeforeEach
    void setUp() {
        this.detector = new DockerMetadataRequestDetector();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/v2/library/nginx/tags/list",
        "/v2/myorg/myimage/tags/list",
        "/v2/a/b/c/tags/list",
        "/v2/single/tags/list"
    })
    void detectsTagsListRequests(final String path) {
        assertThat(this.detector.isMetadataRequest(path), is(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/v2/library/nginx/manifests/latest",
        "/v2/library/nginx/blobs/sha256:abc123",
        "/v2/library/nginx/blobs/uploads/uuid123",
        "/v2/_catalog",
        "/v2/",
        "/v2/library/nginx/tags/list/extra",
        "/library/nginx/tags/list",
        "/v2/tags/list",
        "/v1/library/nginx/tags/list"
    })
    void rejectsNonTagsListRequests(final String path) {
        assertThat(this.detector.isMetadataRequest(path), is(false));
    }

    @Test
    void extractsSimpleImageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/v2/library/nginx/tags/list"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("library/nginx"));
    }

    @Test
    void extractsNestedImageName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/v2/myorg/subgroup/myimage/tags/list"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("myorg/subgroup/myimage"));
    }

    @Test
    void extractsSingleSegmentName() {
        final Optional<String> name = this.detector.extractPackageName(
            "/v2/ubuntu/tags/list"
        );
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("ubuntu"));
    }

    @Test
    void returnsEmptyForNonMetadataPath() {
        final Optional<String> name = this.detector.extractPackageName(
            "/v2/library/nginx/manifests/latest"
        );
        assertThat(name.isPresent(), is(false));
    }

    @Test
    void returnsDockerRepoType() {
        assertThat(this.detector.repoType(), equalTo("docker"));
    }
}
