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

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DockerMetadataParser}.
 *
 * @since 2.2.0
 */
final class DockerMetadataParserTest {

    private DockerMetadataParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new DockerMetadataParser();
    }

    @Test
    void parsesValidDockerTagsList() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25", "1.26", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(metadata, is(notNullValue()));
        assertThat(metadata.get("name").asText(), equalTo("library/nginx"));
    }

    @Test
    void extractsTagsFromMetadata() throws Exception {
        final String json = """
            {
                "name": "library/redis",
                "tags": ["7.0", "7.2", "7.4", "alpine", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, hasSize(5));
        assertThat(versions, containsInAnyOrder("7.0", "7.2", "7.4", "alpine", "latest"));
    }

    @Test
    void parsesFixtureFile() throws Exception {
        final byte[] bytes = loadFixture("cooldown/docker-tags-list-sample.json");
        final JsonNode metadata = this.parser.parse(bytes);
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, hasSize(10));
        assertThat(
            versions,
            containsInAnyOrder(
                "1.22", "1.23", "1.24", "1.25", "1.26", "1.27",
                "stable", "mainline", "alpine", "latest"
            )
        );
    }

    @Test
    void returnsEmptyListWhenNoTags() throws Exception {
        final String json = """
            {
                "name": "library/empty"
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, is(empty()));
    }

    @Test
    void returnsEmptyListWhenTagsIsNull() throws Exception {
        final String json = """
            {
                "name": "library/nulltags",
                "tags": null
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, is(empty()));
    }

    @Test
    void returnsEmptyListWhenTagsIsNotArray() throws Exception {
        final String json = """
            {
                "name": "library/badtags",
                "tags": "not-an-array"
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, is(empty()));
    }

    @Test
    void getsLatestVersionWhenPresent() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> latest = this.parser.getLatestVersion(metadata);
        assertThat(latest.isPresent(), is(true));
        assertThat(latest.get(), equalTo("latest"));
    }

    @Test
    void returnsEmptyWhenNoLatestTag() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "1.25", "1.26"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> latest = this.parser.getLatestVersion(metadata);
        assertThat(latest.isPresent(), is(false));
    }

    @Test
    void returnsEmptyWhenTagsAbsentForLatest() throws Exception {
        final String json = """
            {
                "name": "library/empty"
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> latest = this.parser.getLatestVersion(metadata);
        assertThat(latest.isPresent(), is(false));
    }

    @Test
    void getsRepositoryName() throws Exception {
        final String json = """
            {
                "name": "myorg/myimage",
                "tags": ["v1"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> name = this.parser.getRepositoryName(metadata);
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("myorg/myimage"));
    }

    @Test
    void returnsEmptyRepositoryNameWhenMissing() throws Exception {
        final String json = """
            {
                "tags": ["v1"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> name = this.parser.getRepositoryName(metadata);
        assertThat(name.isPresent(), is(false));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.parser.contentType(), equalTo("application/json"));
    }

    @Test
    void throwsOnInvalidJson() {
        final byte[] invalid = "not valid json {{{".getBytes(StandardCharsets.UTF_8);
        assertThrows(MetadataParseException.class, () -> this.parser.parse(invalid));
    }

    @Test
    void throwsOnEmptyInput() {
        final byte[] empty = new byte[0];
        assertThrows(MetadataParseException.class, () -> this.parser.parse(empty));
    }

    @Test
    void handlesLargeTagsList() throws Exception {
        final StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"library/large\",\"tags\":[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(String.format("\"%d.0.0\"", i));
        }
        json.append("]}");
        final JsonNode metadata = this.parser.parse(
            json.toString().getBytes(StandardCharsets.UTF_8)
        );
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, hasSize(500));
    }

    @Test
    void extractReleaseDatesReturnsEmpty() throws Exception {
        final String json = """
            {
                "name": "library/nginx",
                "tags": ["1.24", "latest"]
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(this.parser.extractReleaseDates(metadata).isEmpty(), is(true));
    }

    private static byte[] loadFixture(final String resource) throws IOException {
        try (InputStream stream = DockerMetadataParserTest.class.getClassLoader()
            .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Resource not found: " + resource);
            }
            return stream.readAllBytes();
        }
    }
}
