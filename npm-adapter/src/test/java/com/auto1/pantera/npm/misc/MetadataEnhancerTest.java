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
package com.auto1.pantera.npm.misc;

import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test cases for {@link MetadataEnhancer}.
 * @since 2.2.5
 */
final class MetadataEnhancerTest {

    @Test
    void emitsRevisionWhenProvided() {
        final JsonObject original = Json.createObjectBuilder()
            .add("name", "pkg")
            .add("versions", Json.createObjectBuilder().add("1.0.0",
                Json.createObjectBuilder().build()))
            .build();
        MatcherAssert.assertThat(
            new MetadataEnhancer(original, "1-abcdef").enhance().getString("_rev"),
            new IsEqual<>("1-abcdef")
        );
    }

    @Test
    void omitsRevisionWhenAbsent() {
        final JsonObject original = Json.createObjectBuilder()
            .add("name", "pkg")
            .add("versions", Json.createObjectBuilder().add("1.0.0",
                Json.createObjectBuilder().build()))
            .build();
        MatcherAssert.assertThat(
            new MetadataEnhancer(original).enhance().containsKey("_rev"),
            new IsEqual<>(false)
        );
    }

    @Test
    void timeIsDerivedFromPublishTimesAndStableAcrossReads() {
        final JsonObject original = Json.createObjectBuilder()
            .add("name", "pkg")
            .add(
                "versions",
                Json.createObjectBuilder()
                    .add(
                        "1.0.0",
                        Json.createObjectBuilder()
                            .add("_publishTime", "2026-01-01T00:00:00Z")
                    )
                    .add(
                        "1.1.0",
                        Json.createObjectBuilder()
                            .add("_publishTime", "2026-02-01T00:00:00Z")
                    )
                    .add("0.9.0", Json.createObjectBuilder())
            )
            .build();
        final JsonObject first = new MetadataEnhancer(original).enhance();
        MatcherAssert.assertThat(
            "modified is the newest publish time, not the request time",
            first.getJsonObject("time").getString("modified"),
            new IsEqual<>("2026-02-01T00:00:00Z")
        );
        MatcherAssert.assertThat(
            "created is the oldest publish time",
            first.getJsonObject("time").getString("created"),
            new IsEqual<>("2026-01-01T00:00:00Z")
        );
        MatcherAssert.assertThat(
            "two reads without a write produce the same body",
            new MetadataEnhancer(original).enhance(),
            new IsEqual<>(first)
        );
    }
}
