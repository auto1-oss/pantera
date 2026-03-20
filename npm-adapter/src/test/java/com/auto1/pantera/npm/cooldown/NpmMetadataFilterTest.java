/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link NpmMetadataFilter}.
 *
 * @since 1.0
 */
final class NpmMetadataFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NpmMetadataParser parser;
    private NpmMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.parser = new NpmMetadataParser();
        this.filter = new NpmMetadataFilter();
    }

    @Test
    void filtersBlockedVersionsFromVersionsObject() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "versions": {
                    "1.0.0": { "name": "test-package", "version": "1.0.0" },
                    "1.1.0": { "name": "test-package", "version": "1.1.0" },
                    "2.0.0": { "name": "test-package", "version": "2.0.0" }
                }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.1.0", "2.0.0"));

        // Should only have 1.0.0 remaining
        assertThat(filtered.get("versions").has("1.0.0"), is(true));
        assertThat(filtered.get("versions").has("1.1.0"), is(false));
        assertThat(filtered.get("versions").has("2.0.0"), is(false));
    }

    @Test
    void filtersBlockedVersionsFromTimeObject() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "versions": {
                    "1.0.0": {},
                    "1.1.0": {},
                    "2.0.0": {}
                },
                "time": {
                    "created": "2020-01-01T00:00:00.000Z",
                    "modified": "2023-01-01T00:00:00.000Z",
                    "1.0.0": "2020-01-01T00:00:00.000Z",
                    "1.1.0": "2021-01-01T00:00:00.000Z",
                    "2.0.0": "2023-01-01T00:00:00.000Z"
                }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Set.of("1.1.0"));

        // Time object should still have created, modified, 1.0.0, 2.0.0 but not 1.1.0
        assertThat(filtered.get("time").has("created"), is(true));
        assertThat(filtered.get("time").has("modified"), is(true));
        assertThat(filtered.get("time").has("1.0.0"), is(true));
        assertThat(filtered.get("time").has("1.1.0"), is(false));
        assertThat(filtered.get("time").has("2.0.0"), is(true));
    }

    @Test
    void returnsUnmodifiedWhenNoBlockedVersions() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "versions": { "1.0.0": {}, "2.0.0": {} }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filter(metadata, Collections.emptySet());

        // Should be unchanged
        assertThat(filtered.get("versions").has("1.0.0"), is(true));
        assertThat(filtered.get("versions").has("2.0.0"), is(true));
    }

    @Test
    void updatesLatestDistTag() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "dist-tags": { "latest": "2.0.0" },
                "versions": { "1.0.0": {}, "2.0.0": {} }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode updated = this.filter.updateLatest(metadata, "1.0.0");

        assertThat(updated.get("dist-tags").get("latest").asText(), equalTo("1.0.0"));
    }

    @Test
    void createsDistTagsIfMissing() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "versions": { "1.0.0": {} }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode updated = this.filter.updateLatest(metadata, "1.0.0");

        assertThat(updated.has("dist-tags"), is(true));
        assertThat(updated.get("dist-tags").get("latest").asText(), equalTo("1.0.0"));
    }

    @Test
    void preservesOtherDistTags() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "dist-tags": {
                    "latest": "2.0.0",
                    "beta": "3.0.0-beta.1",
                    "next": "3.0.0-rc.1"
                },
                "versions": { "1.0.0": {}, "2.0.0": {} }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode updated = this.filter.updateLatest(metadata, "1.0.0");

        assertThat(updated.get("dist-tags").get("latest").asText(), equalTo("1.0.0"));
        assertThat(updated.get("dist-tags").get("beta").asText(), equalTo("3.0.0-beta.1"));
        assertThat(updated.get("dist-tags").get("next").asText(), equalTo("3.0.0-rc.1"));
    }

    @Test
    void filtersDistTagPointingToBlockedVersion() throws Exception {
        final String json = """
            {
                "name": "test-package",
                "dist-tags": {
                    "latest": "2.0.0",
                    "beta": "3.0.0-beta.1"
                }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        final JsonNode filtered = this.filter.filterDistTag(metadata, "beta", Set.of("3.0.0-beta.1"));

        assertThat(filtered.get("dist-tags").has("latest"), is(true));
        assertThat(filtered.get("dist-tags").has("beta"), is(false));
    }

    @Test
    void handlesComplexMetadata() throws Exception {
        final String json = """
            {
                "name": "@scope/complex-package",
                "description": "A complex package",
                "dist-tags": { "latest": "3.0.0" },
                "versions": {
                    "1.0.0": { "name": "@scope/complex-package", "version": "1.0.0", "dependencies": {} },
                    "2.0.0": { "name": "@scope/complex-package", "version": "2.0.0", "dependencies": {} },
                    "3.0.0": { "name": "@scope/complex-package", "version": "3.0.0", "dependencies": {} }
                },
                "time": {
                    "created": "2020-01-01T00:00:00.000Z",
                    "1.0.0": "2020-01-01T00:00:00.000Z",
                    "2.0.0": "2021-01-01T00:00:00.000Z",
                    "3.0.0": "2023-01-01T00:00:00.000Z"
                },
                "maintainers": [{ "name": "test", "email": "test@example.com" }],
                "repository": { "type": "git", "url": "https://github.com/test/test" }
            }
            """;

        final JsonNode metadata = this.parser.parse(json.getBytes(StandardCharsets.UTF_8));
        
        // Filter 2.0.0 and 3.0.0
        final JsonNode filtered = this.filter.filter(metadata, Set.of("2.0.0", "3.0.0"));
        
        // Update latest to 1.0.0
        final JsonNode updated = this.filter.updateLatest(filtered, "1.0.0");

        // Verify versions
        assertThat(updated.get("versions").has("1.0.0"), is(true));
        assertThat(updated.get("versions").has("2.0.0"), is(false));
        assertThat(updated.get("versions").has("3.0.0"), is(false));

        // Verify time
        assertThat(updated.get("time").has("1.0.0"), is(true));
        assertThat(updated.get("time").has("2.0.0"), is(false));
        assertThat(updated.get("time").has("3.0.0"), is(false));

        // Verify latest updated
        assertThat(updated.get("dist-tags").get("latest").asText(), equalTo("1.0.0"));

        // Verify other fields preserved
        assertThat(updated.get("name").asText(), equalTo("@scope/complex-package"));
        assertThat(updated.get("description").asText(), equalTo("A complex package"));
        assertThat(updated.has("maintainers"), is(true));
        assertThat(updated.has("repository"), is(true));
    }
}
