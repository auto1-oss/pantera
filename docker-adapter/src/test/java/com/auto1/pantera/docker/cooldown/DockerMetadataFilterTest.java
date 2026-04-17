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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link DockerMetadataFilter}.
 *
 * @since 2.2.0
 */
final class DockerMetadataFilterTest {

    private DockerMetadataParser parser;
    private DockerMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.parser = new DockerMetadataParser();
        this.filter = new DockerMetadataFilter();
    }

    @Test
    void filtersBlockedTagsFromArray() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25", "1.26", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.25", "latest"));
        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat(remaining, hasSize(2));
        assertThat(remaining, containsInAnyOrder("1.24", "1.26"));
    }

    @Test
    void filtersThreeOfTenTags() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.22", "1.23", "1.24", "1.25", "1.26",
                         "1.27", "stable", "mainline", "alpine", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(
            metadata, Set.of("1.22", "1.23", "1.24")
        );
        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat(remaining, hasSize(7));
        assertThat(
            remaining,
            containsInAnyOrder("1.25", "1.26", "1.27", "stable", "mainline", "alpine", "latest")
        );
    }

    @Test
    void returnsUnmodifiedWhenNoBlockedVersions() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Collections.emptySet());
        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat(remaining, hasSize(3));
        assertThat(remaining, containsInAnyOrder("1.24", "1.25", "latest"));
    }

    @Test
    void filtersAllTags() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.24", "1.25"));
        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat(remaining, is(empty()));
    }

    @Test
    void preservesNameField() throws Exception {
        final String json = """
            {
                "name": "myorg/myimage",
                "tags": ["v1", "v2", "v3"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("v2"));
        assertThat(filtered.get("name").asText(), equalTo("myorg/myimage"));
    }

    @Test
    void ignoresBlockedVersionsNotInTags() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(
            metadata, Set.of("9.99.99", "nonexistent")
        );
        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat(remaining, hasSize(2));
        assertThat(remaining, containsInAnyOrder("1.24", "1.25"));
    }

    @Test
    void handlesMetadataWithNoTagsField() throws Exception {
        final String json = """
            {
                "name": "library/empty"
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.0"));
        assertThat(filtered.get("name").asText(), equalTo("library/empty"));
    }

    @Test
    void updateLatestIsNoOp() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode updated = this.filter.updateLatest(metadata, "1.24");
        // Docker tags/list has no separate "latest" pointer; updateLatest is a no-op
        final List<String> tags = this.parser.extractVersions(updated);
        assertThat(tags, hasSize(3));
        assertThat(tags, containsInAnyOrder("1.24", "1.25", "latest"));
    }

    @Test
    void handlesSingleTagBlocked() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("latest"));
        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat(remaining, is(empty()));
    }
}
