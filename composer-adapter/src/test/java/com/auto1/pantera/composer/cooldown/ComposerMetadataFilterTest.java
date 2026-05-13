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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ComposerMetadataFilter}.
 *
 * @since 2.2.0
 */
final class ComposerMetadataFilterTest {

    private ComposerMetadataParser parser;
    private ComposerMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.parser = new ComposerMetadataParser();
        this.filter = new ComposerMetadataFilter();
    }

    @Test
    void filtersBlockedVersionsFromPackageMap() throws Exception {
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
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.1.0", "2.0.0"));
        final JsonNode pkgNode = filtered.get("packages").get("vendor/package");
        assertThat(pkgNode.has("1.0.0"), is(true));
        assertThat(pkgNode.has("1.1.0"), is(false));
        assertThat(pkgNode.has("2.0.0"), is(false));
    }

    @Test
    void returnsUnmodifiedWhenNoBlockedVersions() throws Exception {
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
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Collections.emptySet());
        final JsonNode pkgNode = filtered.get("packages").get("vendor/package");
        assertThat(pkgNode.has("1.0.0"), is(true));
        assertThat(pkgNode.has("2.0.0"), is(true));
    }

    @Test
    void handlesBlockingAllVersions() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {},
                        "2.0.0": {},
                        "3.0.0": {}
                    }
                }
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(
            metadata, Set.of("1.0.0", "2.0.0", "3.0.0")
        );
        final JsonNode pkgNode = filtered.get("packages").get("vendor/package");
        assertThat(pkgNode.size(), equalTo(0));
    }

    @Test
    void handlesBlockingNonExistentVersions() throws Exception {
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
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("9.9.9"));
        final JsonNode pkgNode = filtered.get("packages").get("vendor/package");
        assertThat(pkgNode.has("1.0.0"), is(true));
        assertThat(pkgNode.has("2.0.0"), is(true));
    }

    @Test
    void preservesVersionMetadata() throws Exception {
        final String json = """
            {
                "packages": {
                    "vendor/package": {
                        "1.0.0": {
                            "name": "vendor/package",
                            "version": "1.0.0",
                            "require": {"php": ">=7.4"},
                            "dist": {"url": "https://example.com/1.0.0.zip"}
                        },
                        "2.0.0": {
                            "name": "vendor/package",
                            "version": "2.0.0"
                        }
                    }
                }
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("2.0.0"));
        final JsonNode remaining = filtered.get("packages").get("vendor/package").get("1.0.0");
        assertThat(remaining.get("name").asText(), equalTo("vendor/package"));
        assertThat(remaining.get("version").asText(), equalTo("1.0.0"));
        assertThat(remaining.get("require").get("php").asText(), equalTo(">=7.4"));
        assertThat(remaining.has("dist"), is(true));
    }

    @Test
    void updateLatestIsNoOp() throws Exception {
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
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode updated = this.filter.updateLatest(metadata, "1.0.0");
        // Composer has no latest tag; updateLatest is a no-op
        assertThat(updated, is(metadata));
    }

    @Test
    void filtersFixtureThreeOfTen() throws Exception {
        final byte[] fixture = loadFixture("cooldown/composer-packages-sample.json");
        final JsonNode metadata = this.parser.parse(fixture);
        final JsonNode filtered = this.filter.filter(
            metadata, Set.of("2.0.0", "3.0.0-beta.1", "3.1.0")
        );
        final List<String> remaining = this.parser.extractVersions(filtered);
        assertThat(remaining, hasSize(7));
        assertThat(
            remaining,
            containsInAnyOrder(
                "1.0.0", "1.1.0", "1.2.0",
                "2.0.1", "2.1.0", "2.2.0", "3.0.0"
            )
        );
    }

    @Test
    void handlesEmptyPackagesObject() throws Exception {
        final String json = """
            {
                "packages": {}
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.0.0"));
        assertThat(filtered.get("packages").size(), equalTo(0));
    }

    @Test
    void handlesMissingPackagesKey() throws Exception {
        final String json = """
            {
                "other": "value"
            }
            """;
        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.0.0"));
        assertThat(filtered.has("other"), is(true));
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
                 ComposerMetadataFilterTest.class.getClassLoader()
                     .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Fixture not found: " + resource);
            }
            return stream.readAllBytes();
        }
    }
}
