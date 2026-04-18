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
 * Tests for {@link ComposerMetadataParser}.
 *
 * @since 2.2.0
 */
final class ComposerMetadataParserTest {

    private ComposerMetadataParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new ComposerMetadataParser();
    }

    @Test
    void parsesValidComposerMetadata() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {"name": "vendor/package", "version": "1.0.0"},
                        "1.1.0": {"name": "vendor/package", "version": "1.1.0"}
                    }
                }
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(metadata, is(notNullValue()));
        assertThat(metadata.has("packages"), is(true));
    }

    @Test
    void extractsVersionsFromPackageMap() throws Exception {
        final String json = """
            {
                "packages": {
                    "monolog/monolog": {
                        "1.0.0": {"name": "monolog/monolog", "version": "1.0.0"},
                        "1.1.0": {"name": "monolog/monolog", "version": "1.1.0"},
                        "2.0.0": {"name": "monolog/monolog", "version": "2.0.0"},
                        "2.1.0": {"name": "monolog/monolog", "version": "2.1.0"},
                        "3.0.0": {"name": "monolog/monolog", "version": "3.0.0"}
                    }
                }
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, hasSize(5));
        assertThat(
            versions,
            containsInAnyOrder("1.0.0", "1.1.0", "2.0.0", "2.1.0", "3.0.0")
        );
    }

    @Test
    void returnsEmptyListWhenNoPackages() throws Exception {
        final String json = """
            {
                "packages": {}
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, is(empty()));
    }

    @Test
    void returnsEmptyListWhenPackagesKeyMissing() throws Exception {
        final String json = """
            {
                "other-key": "value"
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, is(empty()));
    }

    @Test
    void latestVersionAlwaysEmpty() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/pkg": {
                        "1.0.0": {},
                        "2.0.0": {}
                    }
                }
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> latest = this.parser.getLatestVersion(metadata);
        assertThat(latest.isPresent(), is(false));
    }

    @Test
    void getsPackageName() throws Exception {
        final String json = """
            {
                "packages": {
                    "symfony/console": {
                        "5.0.0": {}
                    }
                }
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> name = this.parser.getPackageName(metadata);
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("symfony/console"));
    }

    @Test
    void returnsEmptyPackageNameWhenNoPackages() throws Exception {
        final String json = """
            {
                "packages": {}
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final Optional<String> name = this.parser.getPackageName(metadata);
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
    void parsesFixtureFile() throws Exception {
        final byte[] fixture = loadFixture("cooldown/composer-packages-sample.json");
        final JsonNode metadata = this.parser.parse(fixture);
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, hasSize(10));
        assertThat(
            versions,
            containsInAnyOrder(
                "1.0.0", "1.1.0", "1.2.0",
                "2.0.0", "2.0.1", "2.1.0", "2.2.0",
                "3.0.0-beta.1", "3.0.0", "3.1.0"
            )
        );
        final Optional<String> name = this.parser.getPackageName(metadata);
        assertThat(name.isPresent(), is(true));
        assertThat(name.get(), equalTo("vendor/sample-lib"));
    }

    @Test
    void handlesLargeMetadata() throws Exception {
        final StringBuilder json = new StringBuilder();
        json.append("{\"packages\":{\"vendor/large\":{");
        for (int idx = 0; idx < 500; idx++) {
            if (idx > 0) {
                json.append(",");
            }
            json.append(String.format(
                "\"%d.0.0\":{\"name\":\"vendor/large\",\"version\":\"%d.0.0\"}",
                idx, idx
            ));
        }
        json.append("}}}");
        final JsonNode metadata = this.parser.parse(
            json.toString().getBytes(StandardCharsets.UTF_8)
        );
        final List<String> versions = this.parser.extractVersions(metadata);
        assertThat(versions, hasSize(500));
    }

    @Test
    void throwsOnEmptyBytes() {
        assertThrows(
            MetadataParseException.class,
            () -> this.parser.parse(new byte[0])
        );
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
                 ComposerMetadataParserTest.class.getClassLoader()
                     .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Fixture not found: " + resource);
            }
            return stream.readAllBytes();
        }
    }
}
