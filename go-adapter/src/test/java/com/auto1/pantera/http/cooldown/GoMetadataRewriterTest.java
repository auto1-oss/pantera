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
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link GoMetadataRewriter}.
 *
 * @since 2.2.0
 */
final class GoMetadataRewriterTest {

    private GoMetadataRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.rewriter = new GoMetadataRewriter();
    }

    @Test
    void joinsVersionsWithNewline() {
        final byte[] result = this.rewriter.rewrite(
            List.of("v0.1.0", "v0.2.0", "v1.0.0")
        );
        assertThat(
            new String(result, StandardCharsets.UTF_8),
            equalTo("v0.1.0\nv0.2.0\nv1.0.0")
        );
    }

    @Test
    void writesSingleVersion() {
        final byte[] result = this.rewriter.rewrite(List.of("v1.0.0"));
        assertThat(
            new String(result, StandardCharsets.UTF_8),
            equalTo("v1.0.0")
        );
    }

    @Test
    void returnsEmptyBytesForEmptyList() {
        final byte[] result = this.rewriter.rewrite(Collections.emptyList());
        assertThat(result.length, is(0));
    }

    @Test
    void returnsEmptyBytesForNull() {
        final byte[] result = this.rewriter.rewrite(null);
        assertThat(result.length, is(0));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.rewriter.contentType(), equalTo("text/plain"));
    }

    @Test
    void roundTripPreservesContent() {
        final String original = "v0.1.0\nv1.0.0\nv2.0.0-beta.1\nv2.0.0";
        final GoMetadataParser parser = new GoMetadataParser();
        final List<String> parsed = parser.parse(
            original.getBytes(StandardCharsets.UTF_8)
        );
        final byte[] rewritten = this.rewriter.rewrite(parsed);
        assertThat(
            new String(rewritten, StandardCharsets.UTF_8),
            equalTo(original)
        );
    }

    @Test
    void roundTripAfterFilterPreservesRemainingVersions() {
        final String original = "v0.1.0\nv0.2.0\nv1.0.0\nv1.1.0\nv2.0.0";
        final GoMetadataParser parser = new GoMetadataParser();
        final GoMetadataFilter filter = new GoMetadataFilter();
        final List<String> parsed = parser.parse(
            original.getBytes(StandardCharsets.UTF_8)
        );
        final List<String> filtered = filter.filter(
            parsed, java.util.Set.of("v0.2.0", "v1.1.0")
        );
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        assertThat(
            new String(rewritten, StandardCharsets.UTF_8),
            equalTo("v0.1.0\nv1.0.0\nv2.0.0")
        );
    }
}
