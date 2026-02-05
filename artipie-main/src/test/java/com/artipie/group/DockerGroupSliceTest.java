/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Predicate;

/**
 * Tests for {@link DockerGroupSlice} metadata path detection.
 */
public final class DockerGroupSliceTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/v2/library/alpine/tags/list",
        "/v2/myrepo/myimage/tags/list",
        "/v2/org/repo/image/tags/list",
        "/v2/_catalog",
        "/v2/_catalog?n=100"
    })
    void detectsMetadataPaths(final String path) {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should be detected as metadata", path),
            detector.test(path),
            Matchers.is(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/v2/",
        "/v2/library/alpine/manifests/latest",
        "/v2/library/alpine/manifests/sha256:abc123",
        "/v2/library/alpine/blobs/sha256:abc123",
        "/v2/myrepo/myimage/blobs/uploads/",
        "/v2/myrepo/myimage/blobs/uploads/uuid"
    })
    void doesNotDetectArtifactPaths(final String path) {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should NOT be detected as metadata", path),
            detector.test(path),
            Matchers.is(false)
        );
    }

    @Test
    void handlesNullAndEmptyPaths() {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            "Null path should return false",
            detector.test(null),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            "Empty path should return false",
            detector.test(""),
            Matchers.is(false)
        );
    }

    @Test
    void detectsTagListEndpoint() {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        // Tag list endpoint
        MatcherAssert.assertThat(
            detector.test("/v2/library/alpine/tags/list"),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            detector.test("/v2/myorg/myrepo/tags/list"),
            Matchers.is(true)
        );
    }

    @Test
    void detectsCatalogEndpoint() {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        // Catalog endpoint
        MatcherAssert.assertThat(detector.test("/v2/_catalog"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/v2/_catalog?n=50"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/v2/_catalog?n=50&last=repo"), Matchers.is(true));
    }

    @Test
    void rejectsManifestPaths() {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        // Manifests are artifacts, not list metadata
        MatcherAssert.assertThat(
            detector.test("/v2/library/alpine/manifests/latest"),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            detector.test("/v2/library/alpine/manifests/sha256:abc123"),
            Matchers.is(false)
        );
    }

    @Test
    void rejectsBlobPaths() {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        // Blobs are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/v2/library/alpine/blobs/sha256:abc123"),
            Matchers.is(false)
        );
    }

    @Test
    void rejectsVersionCheckPath() {
        final Predicate<String> detector = DockerGroupSlice.createMetadataPathDetector();
        // Version check endpoint is not metadata
        MatcherAssert.assertThat(detector.test("/v2/"), Matchers.is(false));
    }
}
