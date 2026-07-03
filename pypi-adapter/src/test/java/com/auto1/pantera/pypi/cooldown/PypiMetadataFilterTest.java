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
package com.auto1.pantera.pypi.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link PypiMetadataFilter}.
 *
 * @since 2.2.0
 */
final class PypiMetadataFilterTest {

    private PypiMetadataParser parser;
    private PypiMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.parser = new PypiMetadataParser();
        this.filter = new PypiMetadataFilter();
    }

    @Test
    void filtersBlockedVersionLinks() throws Exception {
        final PypiSimpleIndex index = this.parser.parse(loadFixture());
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Set.of("1.1.0", "2.0.0")
        );

        // Original has 10 links; 1.1.0 has 1 link, 2.0.0 has 2 links (tar.gz + whl) = 3 removed
        assertThat(filtered.links(), hasSize(7));

        // Verify blocked versions are gone
        for (final PypiSimpleIndex.Link link : filtered.links()) {
            assertThat(
                "Version " + link.version() + " should not be 1.1.0",
                "1.1.0".equals(link.version()), is(false)
            );
            assertThat(
                "Version " + link.version() + " should not be 2.0.0",
                "2.0.0".equals(link.version()), is(false)
            );
        }
    }

    @Test
    void preservesUnblockedVersions() throws Exception {
        final PypiSimpleIndex index = this.parser.parse(loadFixture());
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Set.of("2.0.0")
        );

        // 2.0.0 has 2 links (tar.gz + whl), so 10 - 2 = 8
        assertThat(filtered.links(), hasSize(8));

        // Verify 1.0.0 is still present
        final boolean has100 = filtered.links().stream()
            .anyMatch(link -> "1.0.0".equals(link.version()));
        assertThat("1.0.0 should be preserved", has100, is(true));
    }

    @Test
    void returnsUnmodifiedWhenNoBlockedVersions() throws Exception {
        final PypiSimpleIndex index = this.parser.parse(loadFixture());
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Collections.emptySet()
        );

        assertThat(filtered.links(), hasSize(10));
    }

    @Test
    void filtersAllVersions() throws Exception {
        final String html = """
            <!DOCTYPE html><html><body>
            <a href="pkg-1.0.0.tar.gz#sha256=a">pkg-1.0.0.tar.gz</a>
            <a href="pkg-2.0.0.tar.gz#sha256=b">pkg-2.0.0.tar.gz</a>
            <a href="pkg-3.0.0.tar.gz#sha256=c">pkg-3.0.0.tar.gz</a>
            </body></html>
            """;
        final PypiSimpleIndex index = this.parser.parse(
            html.getBytes(StandardCharsets.UTF_8)
        );
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Set.of("1.0.0", "2.0.0", "3.0.0")
        );

        assertThat(filtered.links(), hasSize(0));
    }

    @Test
    void preservesLinksWithUnparseableVersion() throws Exception {
        final String html = """
            <!DOCTYPE html><html><body>
            <a href="pkg-1.0.0.tar.gz#sha256=a">pkg-1.0.0.tar.gz</a>
            <a href="some-unknown-file.txt#sha256=b">some-unknown-file.txt</a>
            <a href="pkg-2.0.0.tar.gz#sha256=c">pkg-2.0.0.tar.gz</a>
            </body></html>
            """;
        final PypiSimpleIndex index = this.parser.parse(
            html.getBytes(StandardCharsets.UTF_8)
        );
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Set.of("1.0.0", "2.0.0")
        );

        // The unknown file link should be preserved
        assertThat(filtered.links(), hasSize(1));
        assertThat(filtered.links().get(0).filename(), equalTo("some-unknown-file.txt"));
    }

    @Test
    void filtersPreReleaseVersions() throws Exception {
        final PypiSimpleIndex index = this.parser.parse(loadFixture());
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Set.of("2.1.0a1", "3.0.0rc1")
        );

        // 2.1.0a1 and 3.0.0rc1 each have 1 link = 2 removed
        assertThat(filtered.links(), hasSize(8));

        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat("2.1.0a1 should be removed", remaining.contains("2.1.0a1"), is(false));
        assertThat("3.0.0rc1 should be removed", remaining.contains("3.0.0rc1"), is(false));
    }

    @Test
    void updateLatestReturnsUnchanged() throws Exception {
        final PypiSimpleIndex index = this.parser.parse(loadFixture());
        final PypiSimpleIndex updated = this.filter.updateLatest(index, "1.0.0");

        // PyPI has no latest concept, so the index should be unchanged
        assertThat(updated.links(), hasSize(index.links().size()));
    }

    @Test
    void preservesLinkAttributes() throws Exception {
        final PypiSimpleIndex index = this.parser.parse(loadFixture());
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Set.of("0.9.0")
        );

        // Find the whl link for 1.3.0 which has data-dist-info-metadata
        final PypiSimpleIndex.Link whlLink = filtered.links().stream()
            .filter(link -> "my_pkg-1.3.0-py3-none-any.whl".equals(link.filename()))
            .findFirst()
            .orElseThrow();

        assertThat(whlLink.requiresPython(), equalTo("&gt;=3.8"));
        assertThat(whlLink.distInfoMetadata(), equalTo("sha256=meta1"));
    }

    private static byte[] loadFixture() throws IOException {
        try (InputStream input = PypiMetadataFilterTest.class.getResourceAsStream(
            "/cooldown/pypi-simple-index-sample.html"
        )) {
            if (input == null) {
                throw new IOException("Fixture not found: /cooldown/pypi-simple-index-sample.html");
            }
            return input.readAllBytes();
        }
    }
}
