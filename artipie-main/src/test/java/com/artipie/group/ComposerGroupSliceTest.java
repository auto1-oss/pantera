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
 * Tests for {@link ComposerGroupSlice} metadata path detection.
 */
public final class ComposerGroupSliceTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/packages.json",
        "/repo/packages.json",
        "/p/vendor/package.json",
        "/p2/vendor/package.json",
        "/p/monolog/monolog.json",
        "/p2/symfony/console.json"
    })
    void detectsMetadataPaths(final String path) {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should be detected as metadata", path),
            detector.test(path),
            Matchers.is(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/dist/vendor/package/1.0.0/package.zip",
        "/packages/vendor/package/1.0.0.zip",
        "/vendor/package.zip",
        "/p/vendor/package.zip",
        "/p2/vendor/package.tar.gz"
    })
    void doesNotDetectArtifactPaths(final String path) {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should NOT be detected as metadata", path),
            detector.test(path),
            Matchers.is(false)
        );
    }

    @Test
    void handlesNullAndEmptyPaths() {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
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
    void detectsPackagesJson() {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        // Main packages.json file
        MatcherAssert.assertThat(detector.test("/packages.json"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/repo/packages.json"), Matchers.is(true));
    }

    @Test
    void detectsProviderFiles() {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        // Provider files under /p/ (Composer 1.x format)
        MatcherAssert.assertThat(detector.test("/p/monolog/monolog.json"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/p/vendor/package.json"), Matchers.is(true));
    }

    @Test
    void detectsP2ProviderFiles() {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        // Provider files under /p2/ (Composer 2.x format)
        MatcherAssert.assertThat(detector.test("/p2/symfony/console.json"), Matchers.is(true));
        MatcherAssert.assertThat(detector.test("/p2/vendor/package.json"), Matchers.is(true));
    }

    @Test
    void rejectsZipFiles() {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        // Zip files are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/dist/vendor/package/1.0.0/package.zip"),
            Matchers.is(false)
        );
        MatcherAssert.assertThat(
            detector.test("/p/vendor/package.zip"),
            Matchers.is(false)
        );
    }

    @Test
    void rejectsTarGzFiles() {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        // Tar.gz files are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/p2/vendor/package.tar.gz"),
            Matchers.is(false)
        );
    }

    @Test
    void requiresJsonExtensionForProviderPaths() {
        final Predicate<String> detector = ComposerGroupSlice.createMetadataPathDetector();
        // Provider paths without .json extension should not be detected
        MatcherAssert.assertThat(detector.test("/p/vendor/package"), Matchers.is(false));
        MatcherAssert.assertThat(detector.test("/p2/vendor/package"), Matchers.is(false));
    }
}
