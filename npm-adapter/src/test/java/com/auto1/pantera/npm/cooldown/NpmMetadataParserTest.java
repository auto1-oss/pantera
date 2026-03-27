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
package com.auto1.pantera.npm.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Tests for {@link NpmMetadataParser}.
 *
 * @since 1.0
 */
final class NpmMetadataParserTest {

    private NpmMetadataParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new NpmMetadataParser();
    }

    @Test
    void parsesValidNpmMetadata() throws Exception {
        final String json = """
            {
                "name": "lodash",
                "dist-tags": { "latest": "4.17.21" },
                "versions": {
                    "4.17.20": { "name": "lodash", "version": "4.17.20" },
                    "4.17.21": { "name": "lodash", "version": "4.17.21" }
                },
                "time": {
                    "created": "2012-04-23T00:00:00.000Z",
                    "modified": "2021-02-20T00:00:00.000Z",
                    "4.17.20": "2020-08-13T00:00:00.000Z",
                    "4.17.21": "2021-02-20T00:00:00.000Z"
                }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(metadata, is(notNullValue()));
        assertThat(metadata.get("name").asText(), equalTo("lodash"));
    }

    @Test
    void extractsVersionsFromMetadata() throws Exception {
        final String json = """
            {
                "name": "express",
                "versions": {
                    "4.17.0": {},
                    "4.17.1": {},
                    "4.18.0": {},
                    "4.18.1": {},
                    "4.18.2": {}
                }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);

        assertThat(versions, hasSize(5));
        assertThat(versions, containsInAnyOrder("4.17.0", "4.17.1", "4.18.0", "4.18.1", "4.18.2"));
    }

    @Test
    void returnsEmptyListWhenNoVersions() throws Exception {
        final String json = """
            {
                "name": "empty-package"
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);

        assertThat(versions, is(empty()));
    }

    @Test
    void getsLatestVersionFromDistTags() throws Exception {
        final String json = """
            {
                "name": "react",
                "dist-tags": {
                    "latest": "18.2.0",
                    "next": "19.0.0-rc.0",
                    "canary": "19.0.0-canary-123"
                }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> latest = this.parser.getLatestVersion(metadata);

        assertThat(latest.isPresent(), is(true));
        assertThat(latest.get(), equalTo("18.2.0"));
    }

    @Test
    void returnsEmptyWhenNoDistTags() throws Exception {
        final String json = """
            {
                "name": "no-dist-tags",
                "versions": { "1.0.0": {} }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> latest = this.parser.getLatestVersion(metadata);

        assertThat(latest.isPresent(), is(false));
    }

    @Test
    void extractsReleaseDatesFromTimeObject() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "time": {
                    "created": "2020-01-01T00:00:00.000Z",
                    "modified": "2023-06-15T12:30:00.000Z",
                    "1.0.0": "2020-01-01T10:00:00.000Z",
                    "1.1.0": "2021-03-15T14:30:00.000Z",
                    "2.0.0": "2023-06-15T12:30:00.000Z"
                }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Map<String, Instant> dates = this.parser.releaseDates(metadata);

        // Should have 3 version dates (excludes "created" and "modified")
        assertThat(dates.size(), equalTo(3));
        assertThat(dates.containsKey("1.0.0"), is(true));
        assertThat(dates.containsKey("1.1.0"), is(true));
        assertThat(dates.containsKey("2.0.0"), is(true));
        assertThat(dates.containsKey("created"), is(false));
        assertThat(dates.containsKey("modified"), is(false));

        // Verify actual timestamps
        assertThat(dates.get("1.0.0"), equalTo(Instant.parse("2020-01-01T10:00:00.000Z")));
        assertThat(dates.get("2.0.0"), equalTo(Instant.parse("2023-06-15T12:30:00.000Z")));
    }

    @Test
    void returnsEmptyMapWhenNoTimeObject() throws Exception {
        final String json = """
            {
                "name": "no-time",
                "versions": { "1.0.0": {} }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Map<String, Instant> dates = this.parser.releaseDates(metadata);

        assertThat(dates.isEmpty(), is(true));
    }

    @Test
    void getsPackageName() throws Exception {
        final String json = """
            {
                "name": "@scope/package-name",
                "versions": {}
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> name = this.parser.getPackageName(metadata);

        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("@scope/package-name"));
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
    void handlesLargeMetadata() throws Exception {
        // Build metadata with many versions
        final StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"large-package\",\"versions\":{");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(String.format("\"%d.0.0\":{}", i));
        }
        json.append("},\"time\":{");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(String.format("\"%d.0.0\":\"2020-01-01T00:00:00.000Z\"", i));
        }
        json.append("}}");

        final JsonNode metadata = this.parser.parse(json.toString().getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        final Map<String, Instant> dates = this.parser.releaseDates(metadata);

        assertThat(versions, hasSize(500));
        assertThat(dates.size(), equalTo(500));
    }
}
