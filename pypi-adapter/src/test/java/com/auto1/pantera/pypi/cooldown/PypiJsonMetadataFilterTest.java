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
package com.auto1.pantera.pypi.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link PypiJsonMetadataFilter}.
 *
 * @since 2.2.0
 */
final class PypiJsonMetadataFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PypiJsonMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.filter = new PypiJsonMetadataFilter(MAPPER);
    }

    @Test
    void noBlockedVersionsPassesReleasesThrough() throws Exception {
        final byte[] body = sample("2.32.0",
            "1.0.0", "1.1.0", "2.32.0").getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result = this.filter.filter(body, Set.of());
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Filtered.class));
        final JsonNode root = MAPPER.readTree(
            ((PypiJsonMetadataFilter.Filtered) result).bytes()
        );
        assertThat(root.get("info").get("version").asText(), equalTo("2.32.0"));
        assertThat(root.get("releases").has("1.0.0"), is(true));
        assertThat(root.get("releases").has("1.1.0"), is(true));
        assertThat(root.get("releases").has("2.32.0"), is(true));
    }

    @Test
    void droppedVersionRemovedFromReleases() throws Exception {
        final byte[] body = sample("2.32.0",
            "1.0.0", "1.1.0", "2.32.0").getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of("1.0.0"));
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Filtered.class));
        final JsonNode root = MAPPER.readTree(
            ((PypiJsonMetadataFilter.Filtered) result).bytes()
        );
        assertThat(root.get("releases").has("1.0.0"), is(false));
        assertThat(root.get("releases").has("1.1.0"), is(true));
        // info.version was 2.32.0 (not blocked) — unchanged.
        assertThat(root.get("info").get("version").asText(), equalTo("2.32.0"));
    }

    @Test
    void blockedLatestSwapsInfoVersionAndUrls() throws Exception {
        final byte[] body = sample("2.32.0",
            "1.0.0", "1.1.0", "2.32.0").getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of("2.32.0"));
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Filtered.class));
        final JsonNode root = MAPPER.readTree(
            ((PypiJsonMetadataFilter.Filtered) result).bytes()
        );
        // Highest remaining is 1.1.0.
        assertThat(root.get("info").get("version").asText(), equalTo("1.1.0"));
        assertThat(root.get("releases").has("2.32.0"), is(false));
        // urls was 2.32.0's files; must swap to 1.1.0's files. Use
        // the deterministic file marker we embedded in sample().
        final JsonNode urls = root.get("urls");
        assertThat(urls.isArray(), is(true));
        assertThat(urls.size(), equalTo(1));
        assertThat(
            urls.get(0).get("filename").asText(),
            equalTo("pkg-1.1.0.tar.gz")
        );
    }

    @Test
    void pep440PreReleaseOrdering() throws Exception {
        // Fallback must pick highest PEP 440 version, respecting pre/post rules.
        // With 2.0.0, 2.0.0b1, 2.0.0.post1 and 2.0.0.post1 blocked,
        // fallback should be 2.0.0 (> 2.0.0b1).
        final byte[] body = sample("2.0.0.post1",
            "2.0.0b1", "2.0.0", "2.0.0.post1").getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of("2.0.0.post1"));
        final JsonNode root = MAPPER.readTree(
            ((PypiJsonMetadataFilter.Filtered) result).bytes()
        );
        assertThat(root.get("info").get("version").asText(), equalTo("2.0.0"));
    }

    @Test
    void pep440PostDominatesBase() throws Exception {
        // With 1.0.0, 1.0.0.post1 available and 1.0.0.post1 NOT blocked,
        // the latest 1.0.0.post1 must stay.
        final byte[] body = sample("1.0.0.post1",
            "1.0.0", "1.0.0.post1").getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of());
        final JsonNode root = MAPPER.readTree(
            ((PypiJsonMetadataFilter.Filtered) result).bytes()
        );
        assertThat(
            root.get("info").get("version").asText(), equalTo("1.0.0.post1")
        );
    }

    @Test
    void everyVersionBlockedReturnsAllBlocked() {
        final byte[] body = sample("2.32.0",
            "1.0.0", "1.1.0", "2.32.0").getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of("1.0.0", "1.1.0", "2.32.0"));
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Result.AllBlocked.class));
    }

    @Test
    void malformedJsonPassesThrough() {
        final byte[] body = "not-json-at-all".getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of("1.0.0"));
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Passthrough.class));
        assertThat(
            ((PypiJsonMetadataFilter.Passthrough) result).bytes(),
            equalTo(body)
        );
    }

    @Test
    void missingReleasesPassesThrough() {
        final byte[] body = "{\"info\":{\"version\":\"1.0.0\"}}"
            .getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of("1.0.0"));
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Passthrough.class));
    }

    @Test
    void missingInfoObjectStillFiltersReleases() throws Exception {
        // If info is missing, we can still drop blocked keys from releases.
        final byte[] body = "{\"releases\":{\"1.0.0\":[],\"1.1.0\":[]}}"
            .getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(body, Set.of("1.0.0"));
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Filtered.class));
        final JsonNode root = MAPPER.readTree(
            ((PypiJsonMetadataFilter.Filtered) result).bytes()
        );
        assertThat(root.get("releases").has("1.0.0"), is(false));
        assertThat(root.get("releases").has("1.1.0"), is(true));
        assertThat(root.get("info"), nullValue());
    }

    @Test
    void nullBlockedSetBehavesLikeEmpty() throws Exception {
        final byte[] body = sample("1.0.0", "1.0.0")
            .getBytes(StandardCharsets.UTF_8);
        final PypiJsonMetadataFilter.Result result = this.filter.filter(body, null);
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Filtered.class));
        final JsonNode root = MAPPER.readTree(
            ((PypiJsonMetadataFilter.Filtered) result).bytes()
        );
        assertThat(root.get("releases").has("1.0.0"), is(true));
    }

    @Test
    void emptyBodyPassesThrough() {
        final PypiJsonMetadataFilter.Result result =
            this.filter.filter(new byte[0], Set.of("1.0.0"));
        assertThat(result, instanceOf(PypiJsonMetadataFilter.Passthrough.class));
    }

    /**
     * Build a minimal PyPI JSON API response. {@code infoVersion}
     * becomes {@code info.version}; {@code urls} lists the first
     * release file for that version; {@code releases} is populated
     * with one file per version string, using a deterministic
     * {@code filename} = {@code pkg-<version>.tar.gz} so tests can
     * confirm {@code urls} was swapped correctly.
     */
    private static String sample(final String infoVersion, final String... versions) {
        final StringBuilder releases = new StringBuilder();
        releases.append("\"releases\":{");
        for (int idx = 0; idx < versions.length; idx++) {
            if (idx > 0) {
                releases.append(',');
            }
            releases.append('"').append(versions[idx]).append("\":[")
                .append(fileObject(versions[idx])).append(']');
        }
        releases.append('}');
        return "{"
            + "\"info\":{\"name\":\"pkg\",\"version\":\"" + infoVersion + "\"},"
            + releases
            + ",\"urls\":[" + fileObject(infoVersion) + "]"
            + "}";
    }

    private static String fileObject(final String version) {
        return "{\"filename\":\"pkg-" + version
            + ".tar.gz\",\"url\":\"https://files.pythonhosted.org/pkg-"
            + version + ".tar.gz\"}";
    }
}
