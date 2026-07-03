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
package com.auto1.pantera.http.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link GoMetadataParser}.
 *
 * @since 2.2.0
 */
final class GoMetadataParserTest {

    private GoMetadataParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new GoMetadataParser();
    }

    @Test
    void parsesVersionListFromBytes() {
        final String body = "v0.1.0\nv0.2.0\nv1.0.0\nv1.1.0\nv2.0.0-beta.1\nv2.0.0\n";
        final List<String> versions = this.parser.parse(
            body.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(versions, hasSize(6));
        assertThat(
            versions,
            contains("v0.1.0", "v0.2.0", "v1.0.0", "v1.1.0", "v2.0.0-beta.1", "v2.0.0")
        );
    }

    @Test
    void skipsBlankLines() {
        final String body = "v1.0.0\n\nv1.1.0\n\n\nv1.2.0\n";
        final List<String> versions = this.parser.parse(
            body.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(versions, hasSize(3));
        assertThat(versions, contains("v1.0.0", "v1.1.0", "v1.2.0"));
    }

    @Test
    void trimsWhitespaceFromLines() {
        final String body = "  v1.0.0  \n\tv1.1.0\t\n v1.2.0\n";
        final List<String> versions = this.parser.parse(
            body.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(versions, contains("v1.0.0", "v1.1.0", "v1.2.0"));
    }

    @Test
    void returnsEmptyListForEmptyInput() {
        assertThat(this.parser.parse(new byte[0]), is(empty()));
    }

    @Test
    void returnsEmptyListForNullInput() {
        assertThat(this.parser.parse(null), is(empty()));
    }

    @Test
    void returnsEmptyListForWhitespaceOnly() {
        assertThat(
            this.parser.parse("  \n\n  \n".getBytes(StandardCharsets.UTF_8)),
            is(empty())
        );
    }

    @Test
    void extractVersionsDelegatesToList() {
        final List<String> input = List.of("v1.0.0", "v2.0.0");
        final List<String> extracted = this.parser.extractVersions(input);
        assertThat(extracted, equalTo(input));
    }

    @Test
    void extractVersionsFromNullReturnsEmpty() {
        assertThat(this.parser.extractVersions(null), is(empty()));
    }

    @Test
    void getLatestVersionReturnsLastElement() {
        final List<String> versions = List.of("v0.1.0", "v1.0.0", "v2.0.0");
        final Optional<String> latest = this.parser.getLatestVersion(versions);
        assertThat(latest.isPresent(), is(true));
        assertThat(latest.get(), equalTo("v2.0.0"));
    }

    @Test
    void getLatestVersionReturnsEmptyForEmptyList() {
        assertThat(this.parser.getLatestVersion(List.of()).isPresent(), is(false));
    }

    @Test
    void getLatestVersionReturnsEmptyForNull() {
        assertThat(this.parser.getLatestVersion(null).isPresent(), is(false));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.parser.contentType(), equalTo("text/plain"));
    }

    @Test
    void parsesSingleVersion() {
        final List<String> versions = this.parser.parse(
            "v1.0.0\n".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(versions, hasSize(1));
        assertThat(versions, contains("v1.0.0"));
    }

    @Test
    void parsesSingleVersionWithoutTrailingNewline() {
        final List<String> versions = this.parser.parse(
            "v1.0.0".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(versions, hasSize(1));
        assertThat(versions, contains("v1.0.0"));
    }

    @Test
    void handlesLargeVersionList() {
        final StringBuilder body = new StringBuilder();
        for (int idx = 0; idx < 500; idx++) {
            body.append(String.format("v%d.0.0\n", idx));
        }
        final List<String> versions = this.parser.parse(
            body.toString().getBytes(StandardCharsets.UTF_8)
        );
        assertThat(versions, hasSize(500));
        assertThat(versions.get(0), equalTo("v0.0.0"));
        assertThat(versions.get(499), equalTo("v499.0.0"));
    }
}
