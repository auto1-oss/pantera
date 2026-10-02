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
package com.auto1.pantera.composer;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link MetadataLinks}.
 */
final class MetadataLinksTest {

    /**
     * Base resolved for the request.
     */
    private static final String BASE = "https://packages.example.com/php-api";

    /**
     * Rewriter for the {@code php-api} repository.
     */
    private final MetadataLinks links = new MetadataLinks("php-api");

    @ParameterizedTest
    @CsvSource({
        // uploaded under a url: on a host the repository is no longer reached by
        "https://legacy.example.com/artifactory/php-api/artifacts/acme/api/1.0/acme-api-1.0.zip,"
            + "https://packages.example.com/php-api/artifacts/acme/api/1.0/acme-api-1.0.zip",
        // imported archive layout
        "https://legacy.example.com/artifactory/php-api/acme/stable/api/1.0/acme-api-1.0.zip,"
            + "https://packages.example.com/php-api/acme/stable/api/1.0/acme-api-1.0.zip",
        // JFrog-style API path with the direct-dists alias
        "https://legacy.example.com/artifactory/api/composer/php-api/direct-dists/acme/stable/api/1.0/a.zip,"
            + "https://packages.example.com/php-api/acme/stable/api/1.0/a.zip",
        // already under the resolved base
        "https://packages.example.com/php-api/artifacts/acme/api/1.0/a.zip,"
            + "https://packages.example.com/php-api/artifacts/acme/api/1.0/a.zip",
        // repository-relative, as stored without a configured url:
        "artifacts/acme/api/1.0/a.zip,https://packages.example.com/php-api/artifacts/acme/api/1.0/a.zip",
        "/acme/stable/api/1.0/a.zip,https://packages.example.com/php-api/acme/stable/api/1.0/a.zip",
        // API route without and with a global prefix
        "https://h.example.com/api/composer/php-api/artifacts/a.zip,https://packages.example.com/php-api/artifacts/a.zip",
        "https://h.example.com/test_prefix/api/composer/php-api/artifacts/a.zip,"
            + "https://packages.example.com/php-api/artifacts/a.zip",
        // a dist hosted elsewhere stays where it is
        "https://api.github.com/repos/acme/api/zipball/0123abc,https://api.github.com/repos/acme/api/zipball/0123abc",
        // even when a path segment deeper down is named like the repository
        "https://api.github.com/repos/acme/php-api/zipball/0123abc,"
            + "https://api.github.com/repos/acme/php-api/zipball/0123abc",
        // another repository on the same host stays where it is
        "https://packages.example.com/php-other/artifacts/a.zip,https://packages.example.com/php-other/artifacts/a.zip"
    })
    void reRootsDistUrlsThatPointIntoTheRepository(final String stored, final String expected) {
        MatcherAssert.assertThat(this.links.dist(stored, MetadataLinksTest.BASE), new IsEqual<>(expected));
    }

    @Test
    void reRootsUnderTheGroupBaseWhenServedThroughAGroup() {
        MatcherAssert.assertThat(
            this.links.dist(
                "https://legacy.example.com/artifactory/php-api/artifacts/acme/api/1.0/a.zip",
                "https://packages.example.com/php_group"
            ),
            new IsEqual<>("https://packages.example.com/php_group/artifacts/acme/api/1.0/a.zip")
        );
    }

    @Test
    void rewritesEveryVersionOfObjectKeyedMetadata() {
        final JsonObject served = MetadataLinksTest.json(
            this.links.packages(
                MetadataLinksTest.bytes(
                    "{\"packages\":{\"acme/api\":{"
                        + "\"1.0\":{\"name\":\"acme/api\",\"uid\":7,\"dist\":{\"type\":\"zip\","
                        + "\"url\":\"https://legacy.example.com/artifactory/php-api/artifacts/a-1.0.zip\","
                        + "\"shasum\":\"abc\"}},"
                        + "\"2.0\":{\"name\":\"acme/api\",\"dist\":{\"type\":\"zip\","
                        + "\"url\":\"https://legacy.example.com/artifactory/php-api/artifacts/a-2.0.zip\"}}"
                        + "}}}"
                ),
                MetadataLinksTest.BASE
            )
        ).getJsonObject("packages").getJsonObject("acme/api");
        MatcherAssert.assertThat(
            "1.0 is re-rooted",
            served.getJsonObject("1.0").getJsonObject("dist").getString("url"),
            new IsEqual<>("https://packages.example.com/php-api/artifacts/a-1.0.zip")
        );
        MatcherAssert.assertThat(
            "2.0 is re-rooted",
            served.getJsonObject("2.0").getJsonObject("dist").getString("url"),
            new IsEqual<>("https://packages.example.com/php-api/artifacts/a-2.0.zip")
        );
        MatcherAssert.assertThat(
            "other dist fields are kept",
            served.getJsonObject("1.0").getJsonObject("dist").getString("shasum"),
            new IsEqual<>("abc")
        );
        MatcherAssert.assertThat(
            "other version fields are kept",
            served.getJsonObject("1.0").getInt("uid"),
            new IsEqual<>(7)
        );
    }

    @Test
    void rewritesArrayMetadataAndKeepsVersionsWithoutADist() {
        final JsonObject served = MetadataLinksTest.json(
            this.links.packages(
                MetadataLinksTest.bytes(
                    "{\"minified\":\"composer/2.0\",\"packages\":{\"acme/api\":["
                        + "{\"version\":\"2.0\",\"dist\":{\"url\":\"artifacts/a-2.0.zip\"}},"
                        + "{\"version\":\"1.0\"},"
                        + "{\"version\":\"0.9\",\"dist\":\"__unset\"}"
                        + "]}}"
                ),
                MetadataLinksTest.BASE
            )
        );
        MatcherAssert.assertThat(
            "relative dist is made absolute",
            served.getJsonObject("packages").getJsonArray("acme/api").getJsonObject(0)
                .getJsonObject("dist").getString("url"),
            new IsEqual<>("https://packages.example.com/php-api/artifacts/a-2.0.zip")
        );
        MatcherAssert.assertThat(
            "versions without an object dist are kept as stored",
            served.getJsonObject("packages").getJsonArray("acme/api").toString(),
            new IsEqual<>(
                "[{\"version\":\"2.0\",\"dist\":{\"url\":"
                    + "\"https://packages.example.com/php-api/artifacts/a-2.0.zip\"}},"
                    + "{\"version\":\"1.0\"},{\"version\":\"0.9\",\"dist\":\"__unset\"}]"
            )
        );
        MatcherAssert.assertThat(
            "top-level fields are kept",
            served.getString("minified"),
            new IsEqual<>("composer/2.0")
        );
    }

    @Test
    void rootsTheMetadataLinksOfPackagesJsonAtTheBase() {
        final JsonObject served = MetadataLinksTest.json(
            this.links.root(
                MetadataLinksTest.bytes(
                    "{\"packages\":{},"
                        + "\"metadata-url\":\"https://legacy.example.com/php-api/p2/%package%.json\","
                        + "\"available-packages-url\":\"/p2/available-packages.json\"}"
                ),
                MetadataLinksTest.BASE
            )
        );
        MatcherAssert.assertThat(
            served.getString("metadata-url"),
            new IsEqual<>("https://packages.example.com/php-api/p2/%package%.json")
        );
        MatcherAssert.assertThat(
            served.getString("available-packages-url"),
            new IsEqual<>("https://packages.example.com/php-api/p2/available-packages.json")
        );
    }

    /**
     * @param json JSON text
     * @return UTF-8 bytes
     */
    private static byte[] bytes(final String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param bytes UTF-8 JSON
     * @return Parsed object
     */
    private static JsonObject json(final byte[] bytes) {
        return Json.createReader(new StringReader(new String(bytes, StandardCharsets.UTF_8))).readObject();
    }
}
