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
 * Tests for {@link GoGroupSlice} metadata path detection.
 */
public final class GoGroupSliceTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/github.com/pkg/errors/@v/list",
        "/golang.org/x/tools/@v/list",
        "/github.com/pkg/errors/@v/v0.9.1.info",
        "/golang.org/x/tools/@v/v0.1.0.info",
        "/github.com/pkg/errors/@v/v0.9.1.mod",
        "/golang.org/x/tools/@v/v0.1.0.mod"
    })
    void detectsMetadataPaths(final String path) {
        final Predicate<String> detector = GoGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should be detected as metadata", path),
            detector.test(path),
            Matchers.is(true)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/github.com/pkg/errors/@v/v0.9.1.zip",
        "/golang.org/x/tools/@v/v0.1.0.zip",
        "/github.com/pkg/errors",
        "/sumdb/sum.golang.org/lookup/github.com/pkg/errors@v0.9.1"
    })
    void doesNotDetectArtifactPaths(final String path) {
        final Predicate<String> detector = GoGroupSlice.createMetadataPathDetector();
        MatcherAssert.assertThat(
            String.format("Path '%s' should NOT be detected as metadata", path),
            detector.test(path),
            Matchers.is(false)
        );
    }

    @Test
    void handlesNullAndEmptyPaths() {
        final Predicate<String> detector = GoGroupSlice.createMetadataPathDetector();
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
    void detectsVersionList() {
        final Predicate<String> detector = GoGroupSlice.createMetadataPathDetector();
        // Version list endpoint
        MatcherAssert.assertThat(
            detector.test("/github.com/pkg/errors/@v/list"),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            detector.test("/golang.org/x/tools/@v/list"),
            Matchers.is(true)
        );
    }

    @Test
    void detectsVersionInfo() {
        final Predicate<String> detector = GoGroupSlice.createMetadataPathDetector();
        // Version info files (.info)
        MatcherAssert.assertThat(
            detector.test("/github.com/pkg/errors/@v/v0.9.1.info"),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            detector.test("/golang.org/x/tools/@v/v0.1.0-beta.1.info"),
            Matchers.is(true)
        );
    }

    @Test
    void detectsModFile() {
        final Predicate<String> detector = GoGroupSlice.createMetadataPathDetector();
        // Module files (.mod)
        MatcherAssert.assertThat(
            detector.test("/github.com/pkg/errors/@v/v0.9.1.mod"),
            Matchers.is(true)
        );
        MatcherAssert.assertThat(
            detector.test("/golang.org/x/tools/@v/v0.1.0.mod"),
            Matchers.is(true)
        );
    }

    @Test
    void rejectsZipFiles() {
        final Predicate<String> detector = GoGroupSlice.createMetadataPathDetector();
        // Zip files are artifacts, not metadata
        MatcherAssert.assertThat(
            detector.test("/github.com/pkg/errors/@v/v0.9.1.zip"),
            Matchers.is(false)
        );
    }
}
