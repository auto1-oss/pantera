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
package com.auto1.pantera.docker.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * Tests for {@link DockerMetadataRewriter}.
 *
 * @since 2.2.0
 */
final class DockerMetadataRewriterTest {

    private DockerMetadataParser parser;
    private DockerMetadataFilter filter;
    private DockerMetadataRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.parser = new DockerMetadataParser();
        this.filter = new DockerMetadataFilter();
        this.rewriter = new DockerMetadataRewriter();
    }

    @Test
    void roundTripPreservesUnfilteredMetadata() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25", "1.26", "latest"]
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final byte[] rewritten = this.rewriter.rewrite(original);
        final JsonNode reparsed = this.parser.parse(rewritten);
        assertThat(reparsed.get("name").asText(), equalTo("library/nginx"));
        final List<String> tags = this.parser.extractVersions(reparsed);
        assertThat(tags, hasSize(4));
        assertThat(tags, containsInAnyOrder("1.24", "1.25", "1.26", "latest"));
    }

    @Test
    void roundTripAfterFiltering() throws Exception {
        final String json = """
            {
                "name": "library/redis",
                "tags": ["7.0", "7.2", "7.4", "alpine", "latest"]
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(original, Set.of("7.0", "alpine"));
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        final JsonNode reparsed = this.parser.parse(rewritten);
        assertThat(reparsed.get("name").asText(), equalTo("library/redis"));
        final List<String> tags = this.parser.extractVersions(reparsed);
        assertThat(tags, hasSize(3));
        assertThat(tags, containsInAnyOrder("7.2", "7.4", "latest"));
    }

    @Test
    void roundTripAfterFilteringAll() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25"]
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(original, Set.of("1.24", "1.25"));
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        final JsonNode reparsed = this.parser.parse(rewritten);
        assertThat(reparsed.get("name").asText(), equalTo("library/nginx"));
        final List<String> tags = this.parser.extractVersions(reparsed);
        assertThat(tags, hasSize(0));
    }

    @Test
    void rewriteProducesValidJsonBytes() throws Exception {
        final String json = """
            {
                "name": "myorg/myimage",
                "tags": ["v1", "v2"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final byte[] bytes = this.rewriter.rewrite(metadata);
        assertThat(bytes, is(notNullValue()));
        assertThat(bytes.length > 0, is(true));
        // Verify it is valid JSON by re-parsing
        final JsonNode reparsed = this.parser.parse(bytes);
        assertThat(reparsed, is(notNullValue()));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.rewriter.contentType(), equalTo("application/json"));
    }

    @Test
    void roundTripWithFixtureData() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.22", "1.23", "1.24", "1.25", "1.26",
                         "1.27", "stable", "mainline", "alpine", "latest"]
            }
            """;
        final JsonNode original = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(
            original, Set.of("1.22", "1.23", "1.24")
        );
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        final JsonNode reparsed = this.parser.parse(rewritten);
        final List<String> tags = this.parser.extractVersions(reparsed);
        assertThat(tags, hasSize(7));
        assertThat(
            tags,
            containsInAnyOrder("1.25", "1.26", "1.27", "stable", "mainline", "alpine", "latest")
        );
        assertThat(reparsed.get("name").asText(), equalTo("library/nginx"));
    }
}
