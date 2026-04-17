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
package com.auto1.pantera.composer.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link ComposerMetadataRewriter}.
 *
 * @since 2.2.0
 */
final class ComposerMetadataRewriterTest {

    private ComposerMetadataParser parser;
    private ComposerMetadataFilter filter;
    private ComposerMetadataRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.parser = new ComposerMetadataParser();
        this.filter = new ComposerMetadataFilter();
        this.rewriter = new ComposerMetadataRewriter();
    }

    @Test
    void roundTripPreservesUnfilteredMetadata() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {"name": "vendor/package", "version": "1.0.0"},
                        "2.0.0": {"name": "vendor/package", "version": "2.0.0"}
                    }
                }
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final byte[] rewritten = this.rewriter.rewrite(original);
        final JsonNode reparsed = this.parser.parse(rewritten);
        final List<String> versions = this.parser.extractVersions(reparsed);
        assertThat(versions, hasSize(2));
        assertThat(versions, containsInAnyOrder("1.0.0", "2.0.0"));
    }

    @Test
    void roundTripAfterFiltering() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {"name": "vendor/package", "version": "1.0.0"},
                        "1.1.0": {"name": "vendor/package", "version": "1.1.0"},
                        "2.0.0": {"name": "vendor/package", "version": "2.0.0"}
                    }
                }
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(original, Set.of("1.1.0"));
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        final JsonNode reparsed = this.parser.parse(rewritten);
        final List<String> versions = this.parser.extractVersions(reparsed);
        assertThat(versions, hasSize(2));
        assertThat(versions, containsInAnyOrder("1.0.0", "2.0.0"));
    }

    @Test
    void roundTripFixtureAfterFiltering() throws Exception {
        final byte[] fixture = loadFixture("cooldown/composer-packages-sample.json");
        final JsonNode original = this.parser.parse(fixture);
        final JsonNode filtered = this.filter.filter(
            original, Set.of("2.0.0", "3.0.0-beta.1", "3.1.0")
        );
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        assertThat(rewritten, is(notNullValue()));
        assertThat(rewritten.length > 0, is(true));
        final JsonNode reparsed = this.parser.parse(rewritten);
        final List<String> versions = this.parser.extractVersions(reparsed);
        assertThat(versions, hasSize(7));
        assertThat(
            versions,
            containsInAnyOrder(
                "1.0.0", "1.1.0", "1.2.0",
                "2.0.1", "2.1.0", "2.2.0", "3.0.0"
            )
        );
        assertThat(
            this.parser.getPackageName(reparsed).orElse(""),
            equalTo("vendor/sample-lib")
        );
    }

    @Test
    void preservesVersionObjectFields() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {
                            "name": "vendor/package",
                            "version": "1.0.0",
                            "require": {"php": ">=8.0"},
                            "dist": {
                                "type": "zip",
                                "url": "https://example.com/1.0.0.zip",
                                "shasum": "abc123"
                            }
                        }
                    }
                }
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final byte[] rewritten = this.rewriter.rewrite(original);
        final JsonNode reparsed = this.parser.parse(rewritten);
        final JsonNode version = reparsed.get("packages").get("vendor/package").get("1.0.0");
        assertThat(version.get("name").asText(), equalTo("vendor/package"));
        assertThat(version.get("require").get("php").asText(), equalTo(">=8.0"));
        assertThat(version.get("dist").get("type").asText(), equalTo("zip"));
        assertThat(version.get("dist").get("shasum").asText(), equalTo("abc123"));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.rewriter.contentType(), equalTo("application/json"));
    }

    @Test
    void producesValidJsonBytes() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {}
                    }
                }
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final byte[] rewritten = this.rewriter.rewrite(original);
        final String output = new String(rewritten, StandardCharsets.UTF_8);
        // Verify it's valid JSON by reparsing
        final JsonNode reparsed = this.parser.parse(rewritten);
        assertThat(reparsed, is(notNullValue()));
        assertThat(reparsed.has("packages"), is(true));
    }

    @Test
    void roundTripAllBlockedLeavesEmptyVersionMap() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {},
                        "2.0.0": {}
                    }
                }
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(original, Set.of("1.0.0", "2.0.0"));
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        final JsonNode reparsed = this.parser.parse(rewritten);
        final List<String> versions = this.parser.extractVersions(reparsed);
        assertThat(versions, hasSize(0));
        assertThat(reparsed.get("packages").get("vendor/package").size(), equalTo(0));
    }

    /**
     * Load a test fixture from classpath.
     *
     * @param resource Resource path
     * @return File content as bytes
     * @throws IOException If reading fails
     */
    private static byte[] loadFixture(final String resource) throws IOException {
        try (InputStream stream =
                 ComposerMetadataRewriterTest.class.getClassLoader()
                     .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Fixture not found: " + resource);
            }
            return stream.readAllBytes();
        }
    }
}
