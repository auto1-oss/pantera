/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.goproxy.metadata;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GoMetadataMerger}.
 *
 * @since 1.18.0
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class GoMetadataMergerTest {

    /**
     * Merger under test.
     */
    private GoMetadataMerger merger;

    @BeforeEach
    void setUp() {
        this.merger = new GoMetadataMerger();
    }

    @Test
    void mergesVersionsFromMultipleMembers() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", "v1.0.0\nv1.1.0".getBytes(StandardCharsets.UTF_8));
        responses.put("member2", "v2.0.0\nv2.1.0".getBytes(StandardCharsets.UTF_8));
        final byte[] result = this.merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            text,
            Matchers.containsString("v1.0.0")
        );
        MatcherAssert.assertThat(
            text,
            Matchers.containsString("v1.1.0")
        );
        MatcherAssert.assertThat(
            text,
            Matchers.containsString("v2.0.0")
        );
        MatcherAssert.assertThat(
            text,
            Matchers.containsString("v2.1.0")
        );
    }

    @Test
    void deduplicatesVersions() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", "v1.0.0\nv1.1.0".getBytes(StandardCharsets.UTF_8));
        responses.put("member2", "v1.0.0\nv2.0.0".getBytes(StandardCharsets.UTF_8));
        final byte[] result = this.merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);
        // Count occurrences of v1.0.0 - should be exactly 1
        final long count = text.lines()
            .filter(line -> "v1.0.0".equals(line.trim()))
            .count();
        MatcherAssert.assertThat(
            "v1.0.0 should appear exactly once",
            count,
            Matchers.equalTo(1L)
        );
    }

    @Test
    void sortsBySemver() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", "v2.0.0\nv1.0.0".getBytes(StandardCharsets.UTF_8));
        responses.put("member2", "v1.5.0\nv1.10.0".getBytes(StandardCharsets.UTF_8));
        final byte[] result = this.merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);
        final String[] lines = text.trim().split("\n");
        MatcherAssert.assertThat(
            "Versions should be sorted in semver order",
            lines,
            Matchers.arrayContaining("v1.0.0", "v1.5.0", "v1.10.0", "v2.0.0")
        );
    }

    @Test
    void handlesEmptyResponses() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        final byte[] result = this.merger.merge(responses);
        MatcherAssert.assertThat(
            new String(result, StandardCharsets.UTF_8),
            Matchers.equalTo("")
        );
    }

    @Test
    void handlesSingleResponse() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", "v1.0.0\nv1.1.0".getBytes(StandardCharsets.UTF_8));
        final byte[] result = this.merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);
        final String[] lines = text.trim().split("\n");
        MatcherAssert.assertThat(
            lines,
            Matchers.arrayContaining("v1.0.0", "v1.1.0")
        );
    }

    @Test
    void handlesEmptyLines() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", "v1.0.0\n\nv1.1.0\n".getBytes(StandardCharsets.UTF_8));
        final byte[] result = this.merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);
        // Should not have empty lines in result
        MatcherAssert.assertThat(
            text.contains("\n\n"),
            Matchers.equalTo(false)
        );
    }

    @Test
    void handlesPrereleaseVersions() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", "v1.0.0-alpha\nv1.0.0".getBytes(StandardCharsets.UTF_8));
        responses.put("member2", "v1.0.0-beta".getBytes(StandardCharsets.UTF_8));
        final byte[] result = this.merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);
        final String[] lines = text.trim().split("\n");
        // Prereleases should come before release
        MatcherAssert.assertThat(
            "Prerelease versions should sort correctly",
            lines,
            Matchers.arrayContaining("v1.0.0-alpha", "v1.0.0-beta", "v1.0.0")
        );
    }
}
