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
 * Tests for {@link NpmGroupSlice} metadata path detection.
 */
public final class NpmGroupSliceTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/lodash",
        "/express",
        "/react",
        "/@types/node",
        "/@babel/core",
        "/@scope/package",
        "/some-package/package.json"
    })
    void detectsMetadataPaths(final String path) {
        final Predicate<String> detector = NpmGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should be detected as metadata", path),
            detector.test(path),
            Matchers.is(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/lodash/-/lodash-4.17.21.tgz",
        "/@types/node/-/node-18.0.0.tgz",
        "/@babel/core/-/core-7.0.0.tgz",
        "/express/4.18.2",
        "/react/18.0.0/index.js",
        "/-/npm/v1/security/audits",
        "/-/v1/login"
    })
    void doesNotDetectArtifactPaths(final String path) {
        final Predicate<String> detector = NpmGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should NOT be detected as metadata", path),
            detector.test(path),
            Matchers.is(false)
        );
    }

    @Test
    void handlesNullAndEmptyPaths() {
        final Predicate<String> detector = NpmGroupSlice.createMetadataPathDetector();
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
    void detectsUnscopedPackageMetadata() {
        final Predicate<String> detector = NpmGroupSlice.createMetadataPathDetector();
        // Unscoped packages: single path segment
        MatcherAssert.assertThat(detector.test("/lodash"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/express"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/package-with-dashes"), Matchers.is(true));
    }

    @Test
    void detectsScopedPackageMetadata() {
        final Predicate<String> detector = NpmGroupSlice.createMetadataPathDetector();
        // Scoped packages: @scope/package
        MatcherAssert.assertThat(detector.test("/@types/node"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/@babel/core"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/@org/pkg"), Matchers.is(true));
    }

    @Test
    void rejectsTarballPaths() {
        final Predicate<String> detector = NpmGroupSlice.createMetadataPathDetector();
        // Tarballs should NOT be detected as metadata
        MatcherAssert.assertThat(
            detector.test("/lodash/-/lodash-4.17.21.tgz"),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            detector.test("/@types/node/-/node-18.0.0.tgz"),
            Matchers.is(false)
        );
    }
}
