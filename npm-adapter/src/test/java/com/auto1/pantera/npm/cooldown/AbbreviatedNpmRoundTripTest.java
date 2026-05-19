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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Verifies the abbreviated NPM packument round-trip (parse → filter zero
 * versions → rewrite) preserves the JSON tree. Confirms the report's
 * hypothesis that the abbreviated content-type doesn't need a dedicated
 * parser/rewriter pair — the same {@link NpmMetadataParser} +
 * {@link NpmMetadataRewriter} are content-shape-preserving.
 */
final class AbbreviatedNpmRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Abbreviated-shape packument: no {@code readme}, no {@code description},
     * abbreviated {@code versions} entries (only fields pnpm/npm need for
     * resolution). The {@code time} field is present so cooldown can extract
     * release dates without the full packument.
     */
    private static final String ABBREVIATED_JSON = "{"
        + "\"name\":\"acme-pkg\","
        + "\"dist-tags\":{\"latest\":\"1.0.0\"},"
        + "\"versions\":{"
        + "\"0.9.0\":{"
        + "\"name\":\"acme-pkg\",\"version\":\"0.9.0\","
        + "\"dist\":{\"tarball\":\"https://registry/acme-pkg/-/acme-pkg-0.9.0.tgz\","
        + "\"shasum\":\"deadbeef\"}"
        + "},"
        + "\"1.0.0\":{"
        + "\"name\":\"acme-pkg\",\"version\":\"1.0.0\","
        + "\"dist\":{\"tarball\":\"https://registry/acme-pkg/-/acme-pkg-1.0.0.tgz\","
        + "\"shasum\":\"feedface\"}"
        + "}"
        + "},"
        + "\"time\":{"
        + "\"0.9.0\":\"2024-06-01T00:00:00.000Z\","
        + "\"1.0.0\":\"2024-09-01T00:00:00.000Z\""
        + "},"
        + "\"modified\":\"2024-09-01T00:00:00.000Z\""
        + "}";

    @Test
    void roundTripPreservesAbbreviatedShape() throws Exception {
        final byte[] input = ABBREVIATED_JSON.getBytes(StandardCharsets.UTF_8);
        final NpmMetadataParser parser = new NpmMetadataParser();
        final NpmMetadataFilter filter = new NpmMetadataFilter();
        final NpmMetadataRewriter rewriter = new NpmMetadataRewriter();

        final JsonNode parsed = parser.parse(input);
        final JsonNode filtered = filter.filter(parsed, Collections.emptySet());
        final byte[] rewritten = rewriter.rewrite(filtered);

        final JsonNode reparsed = MAPPER.readTree(rewritten);
        final JsonNode original = MAPPER.readTree(input);
        assertThat(reparsed, new IsEqual<>(original));
    }
}
