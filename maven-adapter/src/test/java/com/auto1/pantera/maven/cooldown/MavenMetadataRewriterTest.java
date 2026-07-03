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
package com.auto1.pantera.maven.cooldown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link MavenMetadataRewriter}.
 * Round-trip: parse -> filter -> rewrite -> parse again -> assert filtered model matches.
 *
 * @since 2.2.0
 */
final class MavenMetadataRewriterTest {

    private MavenMetadataParser parser;
    private MavenMetadataFilter filter;
    private MavenMetadataRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.parser = new MavenMetadataParser();
        this.filter = new MavenMetadataFilter();
        this.rewriter = new MavenMetadataRewriter();
    }

    @Test
    void roundTripPreservesFilteredVersions() throws Exception {
        // Parse original
        final Document original = this.parser.parse(
            MavenMetadataRewriterTest.loadFixture()
        );
        // Filter 3 versions
        this.filter.filter(
            original, Set.of("2.0.0", "3.0.0-alpha", "3.0.0-beta")
        );
        this.filter.updateLatest(original, "3.0.0");
        // Rewrite to bytes
        final byte[] rewritten = this.rewriter.rewrite(original);
        assertThat(rewritten.length, is(greaterThan(0)));
        // Parse again
        final Document reparsed = this.parser.parse(rewritten);
        final List<String> versions = this.parser.extractVersions(reparsed);
        assertThat(versions, hasSize(7));
        assertThat(
            versions,
            contains(
                "1.0.0", "1.1.0", "1.2.0",
                "2.1.0", "2.2.0", "2.3.0",
                "3.0.0"
            )
        );
        assertThat(
            this.parser.getLatestVersion(reparsed).orElse(""),
            equalTo("3.0.0")
        );
    }

    @Test
    void roundTripWithNoFilteringPreservesAll() throws Exception {
        final Document original = this.parser.parse(
            MavenMetadataRewriterTest.loadFixture()
        );
        final byte[] rewritten = this.rewriter.rewrite(original);
        final Document reparsed = this.parser.parse(rewritten);
        final List<String> versions = this.parser.extractVersions(reparsed);
        assertThat(versions, hasSize(10));
        assertThat(
            this.parser.getLatestVersion(reparsed).orElse(""),
            equalTo("3.0.0")
        );
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(
            this.rewriter.contentType(), equalTo("application/xml")
        );
    }

    /**
     * Load the sample maven-metadata.xml fixture from test resources.
     *
     * @return Fixture bytes
     * @throws IOException If reading fails
     */
    private static byte[] loadFixture() throws IOException {
        try (InputStream stream = MavenMetadataRewriterTest.class
            .getResourceAsStream("/cooldown/maven-metadata-sample.xml")) {
            if (stream == null) {
                throw new IOException(
                    "Fixture not found: /cooldown/maven-metadata-sample.xml"
                );
            }
            return stream.readAllBytes();
        }
    }
}
