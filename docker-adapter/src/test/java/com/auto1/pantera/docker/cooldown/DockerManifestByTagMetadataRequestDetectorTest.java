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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link DockerManifestByTagMetadataRequestDetector}.
 *
 * @since 2.2.0
 */
final class DockerManifestByTagMetadataRequestDetectorTest {

    private final DockerManifestByTagMetadataRequestDetector detector =
        new DockerManifestByTagMetadataRequestDetector();

    @Test
    void matchesManifestByTagSimpleName() {
        assertThat(
            this.detector.isMetadataRequest("/v2/library/nginx/manifests/latest"),
            is(true)
        );
        assertThat(
            this.detector.extractPackageName("/v2/library/nginx/manifests/latest")
                .orElse(""),
            equalTo("library/nginx")
        );
        assertThat(
            this.detector.extractTag("/v2/library/nginx/manifests/latest").orElse(""),
            equalTo("latest")
        );
    }

    @Test
    void matchesManifestByTagWithDotsAndHyphens() {
        assertThat(
            this.detector.isMetadataRequest("/v2/foo/bar/manifests/v1.2.3-rc1"),
            is(true)
        );
        assertThat(
            this.detector.extractPackageName("/v2/foo/bar/manifests/v1.2.3-rc1")
                .orElse(""),
            equalTo("foo/bar")
        );
        assertThat(
            this.detector.extractTag("/v2/foo/bar/manifests/v1.2.3-rc1").orElse(""),
            equalTo("v1.2.3-rc1")
        );
    }

    @Test
    void rejectsDigestReferenceSha256() {
        final String path =
            "/v2/library/nginx/manifests/sha256:"
            + "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertThat(this.detector.isMetadataRequest(path), is(false));
        assertThat(this.detector.extractPackageName(path).isPresent(), is(false));
        assertThat(this.detector.extractTag(path).isPresent(), is(false));
    }

    @Test
    void rejectsDigestReferenceSha512() {
        final String path = "/v2/foo/bar/manifests/sha512:abc123";
        assertThat(this.detector.isMetadataRequest(path), is(false));
    }

    @Test
    void rejectsTagsListPath() {
        assertThat(
            this.detector.isMetadataRequest("/v2/library/nginx/tags/list"),
            is(false)
        );
    }

    @Test
    void rejectsBlobsPath() {
        assertThat(
            this.detector.isMetadataRequest("/v2/library/nginx/blobs/sha256:abc"),
            is(false)
        );
    }

    @Test
    void rejectsCatalogPath() {
        assertThat(this.detector.isMetadataRequest("/v2/_catalog"), is(false));
    }

    @Test
    void rejectsBasePath() {
        assertThat(this.detector.isMetadataRequest("/v2/"), is(false));
    }

    @Test
    void rejectsMalformedPaths() {
        assertThat(this.detector.isMetadataRequest(""), is(false));
        assertThat(this.detector.isMetadataRequest("/"), is(false));
        assertThat(this.detector.isMetadataRequest("/v2"), is(false));
        assertThat(this.detector.isMetadataRequest("/v2/manifests/latest"), is(false));
        assertThat(
            this.detector.isMetadataRequest("/v2/nginx/manifests/"),
            is(false)
        );
    }

    @Test
    void repoTypeIsDocker() {
        assertThat(this.detector.repoType(), equalTo("docker"));
    }
}
