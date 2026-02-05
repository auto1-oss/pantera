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
 * Tests for {@link PypiGroupSlice} metadata path detection.
 */
public final class PypiGroupSliceTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/simple/",
        "/simple",
        "/simple/requests/",
        "/simple/requests",
        "/simple/django/",
        "/simple/numpy/"
    })
    void detectsMetadataPaths(final String path) {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should be detected as metadata", path),
            detector.test(path),
            Matchers.is(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/simple/requests/requests-2.28.0-py3-none-any.whl",
        "/simple/django/Django-4.0.tar.gz",
        "/packages/requests-2.28.0.tar.gz",
        "/packages/source/r/requests/requests-2.28.0.tar.gz",
        "/simple/numpy/numpy-1.24.0.whl",
        "/simple/package/package-1.0.0.zip",
        "/simple/pkg/pkg-1.0.0.egg"
    })
    void doesNotDetectArtifactPaths(final String path) {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should NOT be detected as metadata", path),
            detector.test(path),
            Matchers.is(false)
        );
    }

    @Test
    void handlesNullAndEmptyPaths() {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
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
    void detectsSimpleIndexRoot() {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        // Root simple index
        MatcherAssert.assertThat(detector.test("/simple"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/simple/"), Matchers.is(true));
    }

    @Test
    void detectsPackageIndex() {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        // Package-specific index pages
        MatcherAssert.assertThat(detector.test("/simple/requests/"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/simple/django/"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/simple/flask"), Matchers.is(true));
    }

    @Test
    void rejectsWheelFiles() {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        // Wheel files are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/simple/requests/requests-2.28.0-py3-none-any.whl"),
            Matchers.is(false)
        );
    }

    @Test
    void rejectsTarGzFiles() {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        // Source distributions are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/simple/django/Django-4.0.tar.gz"),
            Matchers.is(false)
        );
    }

    @Test
    void rejectsZipFiles() {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        // Zip files are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/simple/package/package-1.0.0.zip"),
            Matchers.is(false)
        );
    }

    @Test
    void rejectsEggFiles() {
        final Predicate<String> detector = PypiGroupSlice.createMetadataPathDetector();
        // Egg files are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/simple/pkg/pkg-1.0.0.egg"),
            Matchers.is(false)
        );
    }
}
